package dev.spainclaro.tanoshi.updates

/**
 * A newer GitHub release of the Android app that can be downloaded and
 * installed over the current installation.
 *
 * @property tag release tag as published, e.g. `android-v0.2.0`.
 * @property versionName version parsed from [tag], e.g. `0.2.0`.
 * @property releasePageUrl human readable release page (`html_url`).
 * @property apkUrl direct download URL of the `.apk` asset.
 * @property apkSizeBytes size of the APK asset reported by GitHub.
 * @property notes release body (GitHub markdown, may be empty).
 * @property publishedAt ISO-8601 publication timestamp (`published_at`).
 * @property sha256 lowercase hex digest of the APK when GitHub provides one
 *   (`asset.digest` without its `sha256:` prefix), otherwise `null`.
 */
data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val releasePageUrl: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val notes: String,
    val publishedAt: String,
    val sha256: String?,
)

/** Result of [UpdateManager.checkForUpdate]. */
sealed interface UpdateCheckResult {
    /** A strictly newer [android-v*][UpdateInfo.tag] release is available. */
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult

    /** The installed version is current (or newer than every published release). */
    data object UpToDate : UpdateCheckResult

    /** The check could not be completed; [message] is user readable. */
    data class Failure(val message: String) : UpdateCheckResult
}

/**
 * Failure with a message that can be shown to the user as-is.
 *
 * Used for the [Result] failures returned by
 * [UpdateManager.downloadUpdate] and [UpdateManager.installUpdate].
 */
class UpdateException(message: String) : Exception(message)
