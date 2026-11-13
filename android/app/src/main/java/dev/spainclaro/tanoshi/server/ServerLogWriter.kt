package dev.spainclaro.tanoshi.server

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Mirrors the server's stdout/stderr (and the service's own status messages) to
 * [AppPaths.serverLog].
 *
 * The file is opened lazily in append mode, every line is flushed immediately
 * (so a crash never loses the tail of the log), and the file is rotated to
 * `server.log.1` once it grows beyond [maxBytes]. Append/flush/rotate all run
 * under a single lock because stdout and stderr are pumped from two separate
 * coroutines.
 *
 * All I/O errors are captured in [lastFailure] instead of being thrown: logging
 * must never take the server down.
 */
class ServerLogWriter(
    private val logFile: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : Closeable {

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var bytesWritten: Long = 0L
    private var closed = false

    /** Human readable description of the last I/O failure, if any. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Appends one timestamped log line (see [LogStream]). */
    fun append(stream: LogStream, text: String, timestampMillis: Long = System.currentTimeMillis()) {
        writeLine(formatLine(timestampMillis, stream.name, text))
    }

    /** Appends an already formatted line with a timestamp prefix. */
    fun appendSystem(text: String, timestampMillis: Long = System.currentTimeMillis()) {
        writeLine(formatLine(timestampMillis, LogStream.SYSTEM.name, text))
    }

    /** The file this writer appends to. */
    fun file(): File = logFile

    private fun writeLine(line: String) {
        synchronized(lock) {
            if (closed) return
            try {
                ensureOpenLocked()
                val out = writer ?: return
                out.write(line)
                out.newLine()
                out.flush()
                bytesWritten += line.length.toLong() + 1L
                if (bytesWritten >= maxBytes) {
                    rotateLocked()
                }
            } catch (e: IOException) {
                lastFailure = e.describe()
                closeWriterQuietlyLocked()
            } catch (t: Throwable) {
                lastFailure = t.describe()
                closeWriterQuietlyLocked()
            }
        }
    }

    private fun ensureOpenLocked() {
        if (writer != null) return
        logFile.parentFile?.mkdirs()
        val output = FileOutputStream(logFile, true)
        bytesWritten = logFile.length()
        writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        if (bytesWritten == 0L) {
            val header = formatLine(
                System.currentTimeMillis(),
                LogStream.SYSTEM.name,
                "Tanoshi Android app log — new session",
            )
            writer?.apply {
                write(header)
                newLine()
                flush()
                bytesWritten += header.length.toLong() + 1L
            }
        }
    }

    private fun rotateLocked() {
        closeWriterQuietlyLocked()
        val backup = File(logFile.parentFile, "${logFile.name}.1")
        if (backup.exists()) backup.delete()
        if (!logFile.renameTo(backup)) {
            logFile.delete()
        }
        bytesWritten = 0L
        // Reopen immediately so the rotation notice itself lands in the new file.
        ensureOpenLocked()
        val notice = formatLine(
            System.currentTimeMillis(),
            LogStream.SYSTEM.name,
            "Log rotated (previous log kept as ${backup.name})",
        )
        writer?.apply {
            write(notice)
            newLine()
            flush()
            bytesWritten += notice.length.toLong() + 1L
        }
        Log.i(TAG, "Rotated server log to ${backup.absolutePath}")
    }

    private fun closeWriterQuietlyLocked() {
        val current = writer ?: return
        writer = null
        try {
            current.flush()
            current.close()
        } catch (ignored: IOException) {
            // Nothing sensible left to do.
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            closeWriterQuietlyLocked()
        }
    }

    private fun formatLine(timestampMillis: Long, streamName: String, text: String): String =
        "${TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMillis))} [${streamName.padEnd(6)}] $text"

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        private const val TAG = "ServerLogWriter"

        /** Rotate once the log grows past ~5 MB. */
        const val DEFAULT_MAX_BYTES: Long = 5L * 1024L * 1024L

        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

        /** Writer for the app's canonical server log file. */
        fun forContext(context: Context): ServerLogWriter =
            ServerLogWriter(AppPaths.serverLog(context))
    }
}
