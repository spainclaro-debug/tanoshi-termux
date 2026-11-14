package dev.spainclaro.tanoshi.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.spainclaro.tanoshi.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Self-update support for sideloaded builds.
 *
 * Reads the public GitHub releases of
 * `spainclaro-debug/tanoshi-termux`, picks the newest `android-v*` release
 * whose numeric version is newer than [currentVersionName], downloads its APK
 * into the app cache and hands it to the system package installer.
 *
 * Only the system installer installs anything: the app never writes to its own
 * installation directory. The user therefore has to grant "Install unknown
 * apps" once; [canInstallPackages] answers that and
 * [openUnknownSourcesSettings] opens the system screen for it.
 *
 * All network and disk work runs on [Dispatchers.IO]; [installUpdate] reports
 * its result on the main thread. Nothing in this class holds an Activity.
 */
class UpdateManager(context: Context) {

    private val appContext: Context = context.applicationContext
    private val packageManager = appContext.packageManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Installed version without the `-debug` suffix of the debug build type,
     * so tags such as `android-v0.2.0` compare against `0.2.0`.
     */
    val currentVersionName: String = normalizeVersionName(BuildConfig.VERSION_NAME)

    private val currentVersion: Version? = parseVersion(currentVersionName)

    // ------------------------------------------------------------------
    // Install permission
    // ------------------------------------------------------------------

    /**
     * Whether Android currently allows this app to install packages
     * (`REQUEST_INSTALL_PACKAGES` is granted by the user in the settings).
     */
    fun canInstallPackages(): Boolean = packageManager.canRequestPackageInstalls()

    /**
     * Opens the "Install unknown apps" screen for this app, falling back to the
     * app's system settings page when the dedicated screen is unavailable.
     */
    fun openUnknownSourcesSettings() {
        val packageUri = Uri.parse("package:${appContext.packageName}")
        val primary = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(primary)
            return
        } catch (t: RuntimeException) {
            Log.w(TAG, "Could not open the unknown-sources settings screen", t)
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(fallback)
        } catch (t: RuntimeException) {
            Log.w(TAG, "Could not open the app settings screen", t)
        }
    }

    // ------------------------------------------------------------------
    // Checking for updates
    // ------------------------------------------------------------------

    /**
     * Queries GitHub for the newest `android-v*` release and compares it with
     * [currentVersionName]. Never throws; problems are reported as
     * [UpdateCheckResult.Failure] with a user readable message.
     */
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val body = requestReleases()
            val result = parseReleases(body)
            when (result) {
                is UpdateCheckResult.UpdateAvailable ->
                    Log.i(TAG, "Update available: ${result.info.tag} (${result.info.versionName})")
                UpdateCheckResult.UpToDate ->
                    Log.d(TAG, "Installed version $currentVersionName is up to date")
                is UpdateCheckResult.Failure ->
                    Log.w(TAG, "Update check failed: ${result.message}")
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpdateException) {
            UpdateCheckResult.Failure(e.message ?: CHECK_FAILED_MESSAGE)
        } catch (e: IOException) {
            UpdateCheckResult.Failure("$NETWORK_FAILURE_MESSAGE (${e.describe()})")
        } catch (t: Throwable) {
            UpdateCheckResult.Failure("$CHECK_FAILED_MESSAGE: ${t.describe()}")
        }
    }

    // ------------------------------------------------------------------
    // Downloading
    // ------------------------------------------------------------------

    /**
     * Downloads [info]'s APK into `cacheDir/updates/`.
     *
     * The download is written to a `.part` file, checksum/size verified and
     * only then renamed into place. [onProgress] is invoked on
     * [Dispatchers.IO]; `total` is the expected byte count when known (HTTP
     * `Content-Length` or [UpdateInfo.apkSizeBytes]) and `0` otherwise.
     */
    suspend fun downloadUpdate(
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (info.apkUrl.isBlank()) {
                throw UpdateException("This release does not include an APK download")
            }
            if (info.apkSizeBytes in 1L until MIN_APK_BYTES) {
                throw UpdateException("The update APK is unexpectedly small (${info.apkSizeBytes} bytes)")
            }
            val updatesDir = File(appContext.cacheDir, UPDATES_DIR)
            if (updatesDir.exists()) {
                updatesDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            if (!updatesDir.isDirectory && !updatesDir.mkdirs()) {
                throw UpdateException("Could not create the update cache directory")
            }
            val assetName = info.apkUrl.substringAfterLast('/')
                .substringBefore('?')
                .substringBefore('#')
                .ifBlank { FALLBACK_APK_NAME }
            val safeTag = sanitizeFileName(info.tag)
            val targetFile = File(updatesDir, "$safeTag-${sanitizeFileName(assetName)}")
            val partFile = File(targetFile.absolutePath + PART_SUFFIX)
            try {
                downloadToFile(info, partFile, targetFile, onProgress)
            } catch (t: Throwable) {
                runCatching { partFile.delete() }
                throw t
            }
            Log.i(TAG, "Downloaded ${info.tag} to ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            Result.success(targetFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpdateException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(UpdateException("Could not download the update: ${e.describe()}"))
        } catch (t: Throwable) {
            Result.failure(UpdateException("Could not download the update: ${t.describe()}"))
        }
    }

    // ------------------------------------------------------------------
    // Installing
    // ------------------------------------------------------------------

    /**
     * Installs [apk] over the current app through [PackageInstaller].
     *
     * The install runs asynchronously; [onResult] is always invoked exactly
     * once on the main thread. When the system asks for the user's
     * confirmation the corresponding activity is launched directly.
     * The APK must have been produced by [downloadUpdate] (it is fully
     * verified there); it is streamed into the installer session so it is
     * never read into memory.
     */
    fun installUpdate(apk: File, onResult: (Result<Unit>) -> Unit) {
        val delivered = AtomicBoolean(false)
        var timeoutRunnable: Runnable? = null
        var registeredReceiver: BroadcastReceiver? = null

        val unregisterReceiver = {
            val receiver = registeredReceiver
            if (receiver != null) {
                registeredReceiver = null
                runCatching { appContext.unregisterReceiver(receiver) }
            }
        }

        fun deliver(result: Result<Unit>) {
            if (!delivered.compareAndSet(false, true)) return
            mainHandler.post {
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                unregisterReceiver()
                onResult(result)
            }
        }

        if (!apk.isFile) {
            deliver(Result.failure(UpdateException("The downloaded update file is missing; download it again")))
            return
        }
        if (!canInstallPackages()) {
            deliver(Result.failure(UpdateException(INSTALL_PERMISSION_MESSAGE)))
            return
        }

        // A unique action and request code per install attempt keep concurrent
        // attempts (and stale PendingIntents from a previous process) apart.
        val action = ACTION_PREFIX + UUID.randomUUID().toString()
        val requestCode = action.hashCode()
        val pendingIntent = try {
            createStatusPendingIntent(action, requestCode)
        } catch (t: RuntimeException) {
            deliver(Result.failure(UpdateException("Could not prepare the install request: ${t.describe()}")))
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action) return
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                )
                val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.i(TAG, "Install status $status${statusMessage?.let { ": $it" }.orEmpty()}")
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> deliver(Result.success(Unit))

                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmation = intent.getIntentExtra(Intent.EXTRA_INTENT)
                        if (confirmation == null) {
                            deliver(
                                Result.failure(
                                    UpdateException(
                                        "The system installer did not provide a confirmation screen",
                                    ),
                                ),
                            )
                        } else {
                            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                appContext.startActivity(confirmation)
                            } catch (t: RuntimeException) {
                                deliver(
                                    Result.failure(
                                        UpdateException(
                                            "Could not show the system install confirmation: ${t.describe()}",
                                        ),
                                    ),
                                )
                            }
                        }
                    }

                    else -> deliver(
                        Result.failure(UpdateException(describeInstallFailure(status, statusMessage))),
                    )
                }
            }
        }

        registeredReceiver = receiver
        try {
            registerInstallReceiver(receiver, action)
        } catch (t: RuntimeException) {
            registeredReceiver = null
            deliver(Result.failure(UpdateException("Could not listen for the install result: ${t.describe()}")))
            return
        }

        timeoutRunnable = Runnable {
            unregisterReceiver()
            deliver(Result.failure(UpdateException("The installation did not finish in time; try again")))
        }.also { mainHandler.postDelayed(it, INSTALL_TIMEOUT_MS) }

        installScope.launch {
            val installer = packageManager.packageInstaller
            var sessionId = -1
            try {
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                )
                sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)
                try {
                    session.openWrite(SESSION_FILE_NAME, 0L, apk.length()).use { output ->
                        apk.inputStream().use { input ->
                            input.copyTo(output, DOWNLOAD_BUFFER_BYTES)
                        }
                        runCatching { session.fsync(output) }
                    }
                    session.commit(pendingIntent.intentSender)
                } finally {
                    runCatching { session.close() }
                }
                Log.i(TAG, "Started install session $sessionId for ${apk.name}")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (sessionId >= 0) {
                    runCatching { installer.abandonSession(sessionId) }
                }
                Log.w(TAG, "Could not start the update installation", t)
                deliver(
                    Result.failure(
                        UpdateException("Could not start the update installation: ${t.describe()}"),
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // GitHub API
    // ------------------------------------------------------------------

    private fun requestReleases(): String {
        val connection = openUrl(RELEASES_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            val statusCode = connection.responseCode
            val bodyStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = bodyStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (statusCode !in 200..299) {
                throw UpdateException(describeGitHubFailure(statusCode, body))
            }
            return body
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun describeGitHubFailure(statusCode: Int, body: String): String {
        val gitHubMessage = runCatching { JSONObject(body).stringOrNull("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (gitHubMessage != null && gitHubMessage.contains("rate limit", ignoreCase = true)) {
            return RATE_LIMIT_MESSAGE
        }
        if (gitHubMessage != null) {
            return "GitHub API error (HTTP $statusCode): $gitHubMessage"
        }
        if (statusCode == 403 || statusCode == 429) {
            return RATE_LIMIT_MESSAGE
        }
        return "GitHub API returned HTTP $statusCode; try again later"
    }

    private fun parseReleases(body: String): UpdateCheckResult {
        val releases = try {
            JSONArray(body)
        } catch (e: JSONException) {
            return UpdateCheckResult.Failure("Could not read the GitHub release list (${e.describe()})")
        }
        var newestInfo: UpdateInfo? = null
        var newestVersion: Version? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                continue
            }
            val tag = release.stringOrNull("tag_name")?.trim().orEmpty()
            if (!tag.startsWith(TAG_PREFIX)) continue
            val versionName = tag.removePrefix(TAG_PREFIX).trim().removePrefix("v").removePrefix("V")
            val version = parseVersion(versionName) ?: continue
            val asset = pickApkAsset(release.optJSONArray("assets")) ?: continue
            val apkUrl = asset.stringOrNull("browser_download_url")?.trim().orEmpty()
            if (apkUrl.isEmpty()) continue
            val digest = asset.stringOrNull("digest")
            val sha256 = if (digest != null && digest.startsWith(SHA256_PREFIX, ignoreCase = true)) {
                digest.substring(SHA256_PREFIX.length)
                    .trim()
                    .lowercase(Locale.US)
                    .takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val info = UpdateInfo(
                tag = tag,
                versionName = versionName,
                releasePageUrl = release.stringOrNull("html_url").orEmpty(),
                apkUrl = apkUrl,
                apkSizeBytes = asset.optLong("size", 0L),
                notes = release.stringOrNull("body").orEmpty(),
                publishedAt = release.stringOrNull("published_at")
                    ?: release.stringOrNull("created_at").orEmpty(),
                sha256 = sha256,
            )
            if (newestVersion == null || compareVersions(version, newestVersion) > 0) {
                newestVersion = version
                newestInfo = info
            }
        }
        val info = newestInfo ?: return UpdateCheckResult.UpToDate
        val version = newestVersion ?: return UpdateCheckResult.UpToDate
        val local = currentVersion ?: run {
            Log.w(TAG, "Could not parse the installed version \"$currentVersionName\"")
            return UpdateCheckResult.UpToDate
        }
        return if (compareVersions(version, local) > 0) {
            UpdateCheckResult.UpdateAvailable(info)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    /** `app-debug.apk` when published, otherwise the first `.apk` asset. */
    private fun pickApkAsset(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
        var fallback: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.stringOrNull("name") ?: continue
            if (!name.endsWith(APK_SUFFIX, ignoreCase = true)) continue
            if (asset.stringOrNull("browser_download_url").isNullOrBlank()) continue
            if (name.startsWith(PREFERRED_ASSET_PREFIX, ignoreCase = true)) return asset
            if (name.equals(LEGACY_ASSET_NAME, ignoreCase = true) && fallback == null) fallback = asset
            if (fallback == null) fallback = asset
        }
        return fallback
    }

    // ------------------------------------------------------------------
    // Download helpers
    // ------------------------------------------------------------------

    private fun downloadToFile(
        info: UpdateInfo,
        partFile: File,
        targetFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val connection = openUrl(info.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/octet-stream")
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw UpdateException("The update download failed (HTTP $statusCode)")
            }
            val contentLength = connection.contentLengthLong
            val expectedTotal = when {
                contentLength > 0L -> contentLength
                info.apkSizeBytes > 0L -> info.apkSizeBytes
                else -> 0L
            }
            if (expectedTotal > 0L && expectedTotal < MIN_APK_BYTES) {
                throw UpdateException("The update APK is unexpectedly small (${expectedTotal} bytes)")
            }
            val digest = info.sha256?.let { MessageDigest.getInstance("SHA-256") }
            var downloaded = 0L
            var lastProgressAt = 0L
            onProgress(0L, expectedTotal)
            connection.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        downloaded += read.toLong()
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAt >= PROGRESS_THROTTLE_MS) {
                            lastProgressAt = now
                            onProgress(downloaded, expectedTotal)
                        }
                    }
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            }
            onProgress(downloaded, expectedTotal)
            verifyDownload(info, downloaded, contentLength, digest)
            if (targetFile.exists() && !targetFile.delete()) {
                throw UpdateException("Could not replace the previously downloaded update")
            }
            if (!partFile.renameTo(targetFile)) {
                val copyError = runCatching { partFile.copyTo(targetFile, overwrite = true) }
                    .exceptionOrNull()
                if (copyError != null) {
                    throw UpdateException("Could not store the downloaded update: ${copyError.describe()}")
                }
                partFile.delete()
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun verifyDownload(
        info: UpdateInfo,
        downloaded: Long,
        contentLength: Long,
        digest: MessageDigest?,
    ) {
        if (downloaded < MIN_APK_BYTES) {
            throw UpdateException("The downloaded update is unexpectedly small ($downloaded bytes)")
        }
        if (contentLength > 0L && downloaded != contentLength) {
            throw UpdateException("The update download was incomplete ($downloaded of $contentLength bytes); try again")
        }
        if (info.apkSizeBytes > 0L && downloaded != info.apkSizeBytes) {
            throw UpdateException("The update is $downloaded bytes but the release lists ${info.apkSizeBytes} bytes; try again")
        }
        val expected = info.sha256 ?: return
        val computed = digest?.digest()?.toHexString()
            ?: throw UpdateException("Could not verify the update checksum")
        if (!computed.equals(expected, ignoreCase = true)) {
            throw UpdateException("The downloaded update failed its checksum check; try again")
        }
    }

    // ------------------------------------------------------------------
    // PackageInstaller helpers
    // ------------------------------------------------------------------

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerInstallReceiver(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun createStatusPendingIntent(action: String, requestCode: Int): PendingIntent {
        // Package-scoped (not component-set) broadcast: enough to count as an
        // explicit Intent for the mutable-PendingIntent rules of API 31/34,
        // while still being delivered to the runtime-registered receiver.
        val intent = Intent(action).setPackage(appContext.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The installer fills its status extras into this PendingIntent.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
    }

    @Suppress("DEPRECATION")
    private fun Intent.getIntentExtra(name: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Intent::class.java)
        } else {
            getParcelableExtra(name)
        }

    private fun describeInstallFailure(status: Int, statusMessage: String?): String {
        val reason = statusMessage?.takeIf { it.isNotBlank() } ?: when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "the installation was cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "the installation was blocked by the system or a device policy"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "the update conflicts with the installed app (different signing key or app variant?)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "the update is not compatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID -> "the downloaded file is not a valid APK"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "there is not enough free storage space"
            else -> "the system installer reported error code $status"
        }
        return "The update could not be installed: $reason"
    }

    // ------------------------------------------------------------------
    // Version handling
    // ------------------------------------------------------------------

    private fun normalizeVersionName(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.endsWith(DEBUG_SUFFIX, ignoreCase = true)) {
            trimmed.dropLast(DEBUG_SUFFIX.length).trim()
        } else {
            trimmed
        }
    }

    /**
     * Parses `0.2.0`, `v0.2.0`, `0.2.0-beta.1`, ... Numeric components are
     * compared numerically; a build suffix (`-debug`) was already removed and
     * any remaining pre-release suffix makes the version older than the plain
     * release it is based on, but still newer than anything numerically lower.
     */
    private fun parseVersion(raw: String): Version? {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null
        val withoutBuildMetadata = trimmed.substringBefore('+')
        val core = withoutBuildMetadata.substringBefore('-')
        val prerelease = withoutBuildMetadata.substringAfter('-', "").takeIf { it.isNotEmpty() }
        val numbers = core.split('.')
            .map { component -> component.takeWhile { it.isDigit() }.toIntOrNull() ?: return null }
        if (numbers.isEmpty()) return null
        return Version(numbers, prerelease)
    }

    private fun compareVersions(left: Version, right: Version): Int {
        val componentCount = maxOf(left.numbers.size, right.numbers.size)
        for (index in 0 until componentCount) {
            val leftPart = left.numbers.getOrElse(index) { 0 }
            val rightPart = right.numbers.getOrElse(index) { 0 }
            if (leftPart != rightPart) return leftPart.compareTo(rightPart)
        }
        return comparePrerelease(left.prerelease, right.prerelease)
    }

    private fun comparePrerelease(left: String?, right: String?): Int {
        if (left == null && right == null) return 0
        if (left == null) return 1
        if (right == null) return -1
        val leftParts = left.split('.')
        val rightParts = right.split('.')
        val partCount = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until partCount) {
            val leftPart = leftParts.getOrNull(index) ?: return -1
            val rightPart = rightParts.getOrNull(index) ?: return 1
            val leftNumber = leftPart.toIntOrNull()
            val rightNumber = rightPart.toIntOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> leftPart.compareTo(rightPart)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    // ------------------------------------------------------------------
    // Small utilities
    // ------------------------------------------------------------------

    private fun openUrl(rawUrl: String): URL = URI.create(rawUrl).toURL()

    private fun sanitizeFileName(name: String): String =
        name.replace(NON_FILE_SAFE, "_").take(120).ifEmpty { FALLBACK_APK_NAME }

    private fun ByteArray.toHexString(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    private fun JSONObject.stringOrNull(name: String): String? = opt(name) as? String

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    /** Numeric, pre-release aware version used for comparisons. */
    private data class Version(val numbers: List<Int>, val prerelease: String?)

    companion object {
        private const val TAG = "UpdateManager"

        private const val RELEASES_URL =
            "https://api.github.com/repos/spainclaro-debug/tanoshi-termux/releases?per_page=50"
        private const val TAG_PREFIX = "android-v"
        private const val APK_SUFFIX = ".apk"
        private const val PREFERRED_ASSET_PREFIX = "tanoshi-mobile-server"
    private const val LEGACY_ASSET_NAME = "app-debug.apk"
        private const val SHA256_PREFIX = "sha256:"
        private const val USER_AGENT = "Tanoshi-Android"
        private const val GITHUB_API_VERSION = "2022-11-28"

        private const val NETWORK_TIMEOUT_MS = 15_000
        private const val MIN_APK_BYTES = 10L * 1024L * 1024L
        private const val UPDATES_DIR = "updates"
        private const val PART_SUFFIX = ".part"
        private const val FALLBACK_APK_NAME = "tanoshi-update.apk"
        private const val SESSION_FILE_NAME = "tanoshi-update.apk"

        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_THROTTLE_MS = 100L
        private const val INSTALL_TIMEOUT_MS = 10L * 60L * 1000L

        private const val ACTION_PREFIX = "dev.spainclaro.tanoshi.updates.INSTALL_RESULT."
        private const val DEBUG_SUFFIX = "-debug"
        private const val HEX_DIGITS = "0123456789abcdef"

        private const val CHECK_FAILED_MESSAGE = "The update check failed"
        private const val NETWORK_FAILURE_MESSAGE =
            "Could not reach GitHub; check your internet connection and try again"
        private const val RATE_LIMIT_MESSAGE = "GitHub API rate limit reached, try again later"
        private const val INSTALL_PERMISSION_MESSAGE =
            "Tanoshi is not allowed to install updates. Allow \"Install unknown apps\" for Tanoshi, then try again"

        private val NON_FILE_SAFE = Regex("[^A-Za-z0-9._-]")
    }
}
