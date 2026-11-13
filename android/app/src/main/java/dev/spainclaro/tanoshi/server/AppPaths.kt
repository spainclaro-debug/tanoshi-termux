package dev.spainclaro.tanoshi.server

import android.content.Context
import java.io.File

/**
 * Every on-device path the app uses. Tanoshi only ever sees paths below
 * [home], i.e. the app's private files directory. Nothing is written to
 * external storage, and nothing depends on the Termux prefix.
 */
object AppPaths {

    /** Tanoshi's data directory (the value of `TANOSHI_HOME`). */
    fun home(context: Context): File = File(context.filesDir, "tanoshi")

    fun config(context: Context): File = File(home(context), "config.yml")
    fun database(context: Context): File = File(home(context), "tanoshi.db")

    /**
     * Writable directory the server uses for extension preferences. Bundled
     * plugins themselves are executed from [nativeLibraryDir] (read-only),
     * because Android 10+ forbids loading executable code from app-writable
     * storage.
     */
    fun plugins(context: Context): File = File(home(context), "plugins")

    fun downloads(context: Context): File = File(home(context), "downloads")
    fun cache(context: Context): File = File(home(context), "cache")
    fun manga(context: Context): File = File(home(context), "manga")

    /** Log file that mirrors the server's stdout/stderr. */
    fun serverLog(context: Context): File = File(home(context), "server.log")

    /** Directory where the installer extracted the bundled native binaries. */
    fun nativeLibraryDir(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir)

    /**
     * The Tanoshi server executable. It is named `libtanoshi.so` so that the
     * Android package installer extracts it to nativeLibraryDir, which is the
     * only app-owned location that may be executed on Android 10+.
     */
    fun serverBinary(context: Context): File =
        File(nativeLibraryDir(context), "libtanoshi.so")

    fun ensureDirectories(context: Context) {
        listOf(
            home(context),
            plugins(context),
            downloads(context),
            cache(context),
            manga(context),
        ).forEach { it.mkdirs() }
    }
}
