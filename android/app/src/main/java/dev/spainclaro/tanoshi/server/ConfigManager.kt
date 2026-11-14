package dev.spainclaro.tanoshi.server

import android.content.Context
import dev.spainclaro.tanoshi.settings.AppSettings
import java.io.File
import java.security.SecureRandom

/**
 * Owns the Tanoshi `config.yml` and the on-disk data directory layout.
 *
 * Tanoshi only expands literal paths (no `~`, no environment variables), so
 * the generated configuration contains absolute paths below
 * [AppPaths.home]. Tanoshi's `secret` must be exactly 16 characters: it is
 * used as an AES-128 key, and any other length breaks cover-URL decryption.
 *
 * The listen address is *not* part of Tanoshi's config file; the Android build
 * reads the `TANOSHI_BIND` environment variable instead (patched in the
 * bundled binary). The port, however, is authored here.
 */
object ConfigManager {

    private const val MIN_PORT = 1024
    private const val MAX_PORT = 65535

    private val PORT_REGEX = Regex("""(?m)^(\s*['"]?port['"]?\s*:\s*)(\d+)\s*$""")
    private val SECRET_REGEX = Regex("""(?m)^(\s*['"]?secret['"]?\s*:\s*)(["']?)([A-Za-z0-9]*)(["']?)\s*$""")

    fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

    /**
     * Creates the data directory tree and seeds `config.yml` on first launch.
     * An existing configuration is never overwritten.
     */
    fun ensureSeeded(context: Context): File {
        AppPaths.ensureDirectories(context)
        val config = AppPaths.config(context)
        if (!config.exists()) {
            val port = AppSettings.port.takeIf(::isValidPort) ?: AppSettings.DEFAULT_PORT
            writeAtomically(config, defaultConfigText(context, port))
            AppSettings.port = port
        } else {
            repairSecretIfNeeded(config)
        }
        return config
    }

    /** Full configuration file contents with the app's private paths. */
    fun defaultConfigText(context: Context, port: Int): String {
        val home = q(AppPaths.home(context).absolutePath)
        val database = q(AppPaths.database(context).absolutePath)
        val plugins = q(AppPaths.plugins(context).absolutePath)
        val manga = q(AppPaths.manga(context).absolutePath)
        val downloads = q(AppPaths.downloads(context).absolutePath)
        val cache = q(AppPaths.cache(context).absolutePath)
        val secret = generateSecret()
        return """
            # Tanoshi configuration — managed by the Tanoshi Android app.
            # Edit carefully: paths must stay absolute and inside the app's
            # private files directory, and `secret` must stay 16 characters.
            base_url: null
            port: $port
            database_path: $database
            create_database: true
            secret: "$secret"
            update_interval: 3600
            max_concurrent_update_sources: 2
            auto_download_chapters: false
            plugin_path: $plugins
            extension:
              max_concurrent_calls_per_source: 8
              metadata_timeout_secs: 30
              image_timeout_secs: 120
            local_path: $manga
            download_path: $downloads
            cache_path: $cache
            enable_playground: false
            telegram: null
            pushover: null
            gotify: null
            myanimelist: null
            anilist: null
        """.trimIndent() + "\n"
    }

    fun readConfig(context: Context): String? {
        val config = AppPaths.config(context)
        return if (config.isFile) runCatching { config.readText() }.getOrNull() else null
    }

    /**
     * Persists a user-edited config.yml. The file is validated and written
     * atomically; the server must be restarted for changes to take effect.
     */
    fun writeConfig(context: Context, text: String): Result<Unit> {
        val port = parsePort(text)
            ?: return Result.failure(IllegalArgumentException("config.yml must contain a port between $MIN_PORT and $MAX_PORT"))
        if (!isValidPort(port)) {
            return Result.failure(IllegalArgumentException("port $port is outside $MIN_PORT..$MAX_PORT"))
        }
        val secret = parseSecret(text)
            ?: return Result.failure(IllegalArgumentException("config.yml must contain a secret"))
        if (secret.length != 16) {
            return Result.failure(IllegalArgumentException("secret must be exactly 16 characters (AES-128 key), got ${secret.length}"))
        }
        return runCatching {
            AppPaths.ensureDirectories(context)
            writeAtomically(AppPaths.config(context), text.trimEnd() + "\n")
            AppSettings.port = port
        }
    }

    /** Reads the port from config.yml; falls back to the persisted setting. */
    fun currentPort(context: Context): Int {
        val file = AppPaths.config(context)
        if (file.isFile) {
            val parsed = runCatching { parsePort(file.readText()) }.getOrNull()
            if (parsed != null && isValidPort(parsed)) {
                if (AppSettings.port != parsed) AppSettings.port = parsed
                return parsed
            }
        }
        return AppSettings.port
    }

    /**
     * Rewrites only the `port:` line so the rest of a hand-edited config is
     * preserved. Requires a restart of the server.
     */
    fun setPort(context: Context, port: Int): Result<Unit> {
        if (!isValidPort(port)) {
            return Result.failure(IllegalArgumentException("port must be between $MIN_PORT and $MAX_PORT"))
        }
        val file = AppPaths.config(context)
        return runCatching {
            // The settings screen can save a port before the server has ever
            // been started, so the data directory (and with it config.yml the
            // user is editing) may not exist yet. Without this, writing
            // config.yml.tmp failed with ENOENT.
            AppPaths.ensureDirectories(context)
            if (!file.exists()) {
                writeAtomically(file, defaultConfigText(context, port))
            } else {
                val text = file.readText()
                val replaced = if (PORT_REGEX.containsMatchIn(text)) {
                    PORT_REGEX.replaceFirst(text, "$1$port")
                } else {
                    "port: $port\n$text"
                }
                writeAtomically(file, replaced)
            }
            AppSettings.port = port
        }
    }

    fun parsePort(text: String): Int? =
        PORT_REGEX.find(text)?.groupValues?.get(2)?.toIntOrNull()

    fun parseSecret(text: String): String? {
        val match = SECRET_REGEX.find(text) ?: return null
        return match.groupValues[3].ifEmpty { null }
    }

    /**
     * Tanoshi uses the secret as a 16-byte AES key. A user-edited config with a
     * different length would silently break image loading, so repair it (and
     * invalidate existing login tokens, which is unavoidable).
     */
    private fun repairSecretIfNeeded(config: File) {
        val text = runCatching { config.readText() }.getOrNull() ?: return
        val secret = parseSecret(text)
        if (secret != null && secret.length == 16) return
        val replacement = generateSecret()
        val repaired = if (SECRET_REGEX.containsMatchIn(text)) {
            SECRET_REGEX.replaceFirst(text, "$1\"$replacement\"$4")
        } else if (PORT_REGEX.containsMatchIn(text)) {
            PORT_REGEX.replaceFirst(text, "$0\nsecret: \"$replacement\"")
        } else {
            "secret: \"$replacement\"\n$text"
        }
        runCatching { writeAtomically(config, repaired) }
    }

    private fun generateSecret(): String {
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = SecureRandom()
        return buildString(16) {
            repeat(16) { append(alphabet[random.nextInt(alphabet.size)]) }
        }
    }

    private fun q(path: String): String =
        "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun writeAtomically(target: File, text: String) {
        // Defensive: every caller writes below AppPaths.home(), but a missing
        // parent directory must never turn a settings save into an ENOENT.
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
