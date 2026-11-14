package dev.spainclaro.tanoshi.server

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hidden "keep-alive" audio loop for the running server.
 *
 * While the Tanoshi server is up this plays a generated one-second silent WAV
 * in a loop at volume 0, with media audio attributes. That keeps an audio path
 * active while the foreground service declares the `mediaPlayback` type, so the
 * app process is in a media-playback foreground state.
 *
 * Why: the Android 12+ "phantom process" killer and several OEM task killers
 * aggressively kill app *child* processes (which is exactly what the bundled
 * `libtanoshi.so` server is) and freeze background apps. Apps that are actively
 * playing media are much less likely to be frozen or killed, so this gives the
 * server a better chance to survive while the app is not in the foreground.
 *
 * This is a **best-effort mitigation, not a guarantee**: it cannot defeat a
 * force-stop, the user denying background activity, or an OEM's most aggressive
 * policies. Nothing is audible (volume is forced to 0), and no MediaSession,
 * media-style notification or audio-focus request is created — the only
 * notification is still the existing server notification. Failures are logged
 * and ignored: the server must keep working without the keep-alive.
 *
 * Threading: [start] generates the WAV and calls `MediaPlayer.prepare()`, so
 * call it from a background thread (the service calls it from
 * [kotlinx.coroutines.Dispatchers.IO]). [stop] only releases resources and may
 * be called from any thread, including the main thread in `Service.onDestroy()`.
 * Both methods are synchronized, idempotent and never throw.
 */
class SilentKeepAlive(context: Context) {

    private val appContext: Context = context.applicationContext

    private var player: MediaPlayer? = null
    private var playing = false

    /** Whether the silent loop is currently playing (best-effort snapshot). */
    @get:Synchronized
    val isPlaying: Boolean
        get() = playing && player != null

    /**
     * Starts the silent loop if it is not running yet. Must be called off the
     * main thread; does nothing (and logs a warning) when it cannot start.
     */
    @Synchronized
    fun start() {
        if (playing && player != null) return
        runCatching { startLocked() }
            .onFailure { t ->
                Log.w(TAG, "Could not start the silent keep-alive audio", t)
                reportFailure("could not be started (${t.describe()})")
                stopPlayerLocked()
            }
    }

    /** Stops and releases the silent loop; safe and idempotent in any state. */
    @Synchronized
    fun stop() {
        runCatching { stopPlayerLocked() }
            .onFailure { t -> Log.w(TAG, "Could not stop the silent keep-alive audio", t) }
    }

    private fun startLocked() {
        val wavFile = ensureSilentWavFile()
        if (wavFile == null) {
            // The failure is already logged; the server keeps running silently.
            reportFailure("could not generate the silent WAV file")
            return
        }
        // Release a previous instance that may have failed mid-playback.
        stopPlayerLocked()
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            // Keeps the CPU awake while the (silent) audio loops. The WAKE_LOCK
            // permission is already declared for the service wake lock.
            mediaPlayer.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
            mediaPlayer.setDataSource(wavFile.absolutePath)
            mediaPlayer.isLooping = true
            // Absolutely inaudible, regardless of the user's media volume.
            mediaPlayer.setVolume(0f, 0f)
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Silent keep-alive audio error (what=$what, extra=$extra)")
                reportFailure("stopped after a playback error (what=$what, extra=$extra)")
                runCatching { stop() }
                true
            }
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
            playing = true
        } catch (t: Throwable) {
            runCatching { mediaPlayer.release() }
            throw t
        }
    }

    private fun stopPlayerLocked() {
        val current = player
        player = null
        playing = false
        if (current == null) return
        runCatching { if (current.isPlaying) current.stop() }
        runCatching { current.release() }
    }

    /**
     * Generates `<cacheDir>/keepalive/silence.wav`: 1 second of 44.1 kHz
     * 16-bit mono PCM silence with a correct RIFF/WAVE header (~88 KB, written
     * with a small zero-filled buffer so nothing large is allocated). Reuses an
     * existing file to avoid rewriting it on every server start.
     */
    private fun ensureSilentWavFile(): File? {
        val directory = File(appContext.cacheDir, KEEPALIVE_DIR)
        val wavFile = File(directory, WAV_FILE_NAME)
        if (wavFile.isFile && wavFile.length() > MIN_EXISTING_WAV_BYTES) return wavFile
        return runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IllegalStateException("Could not create ${directory.absolutePath}")
            }
            FileOutputStream(wavFile).use { output ->
                output.write(createWavHeader())
                val zeros = ByteArray(ZERO_BUFFER_BYTES)
                var remaining = SILENT_DATA_BYTES
                while (remaining > 0) {
                    val chunk = minOf(remaining, zeros.size)
                    output.write(zeros, 0, chunk)
                    remaining -= chunk
                }
                output.flush()
                runCatching { output.fd.sync() }
            }
            wavFile
        }.getOrElse { t ->
            Log.w(TAG, "Could not write the silent keep-alive WAV file", t)
            null
        }
    }

    private fun createWavHeader(): ByteArray {
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put(RIFF_TAG)
        header.putInt(36 + SILENT_DATA_BYTES)
        header.put(WAVE_TAG)
        header.put(FMT_TAG)
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1.toShort()) // PCM format
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(BYTE_RATE)
        header.putShort(BLOCK_ALIGN.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put(DATA_TAG)
        header.putInt(SILENT_DATA_BYTES)
        return header.array()
    }

    /** Mirrors an unexpected keep-alive failure to the app's SYSTEM log. */
    private fun reportFailure(message: String) {
        runCatching {
            ServerStateRepository.appendLog(LogStream.SYSTEM, "Background keep-alive audio $message")
        }
    }

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        private const val TAG = "SilentKeepAlive"

        private const val KEEPALIVE_DIR = "keepalive"
        private const val WAV_FILE_NAME = "silence.wav"
        private const val MIN_EXISTING_WAV_BYTES = 1_024L

        // 1 s of 44.1 kHz / 16-bit / mono PCM silence.
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BLOCK_ALIGN = CHANNELS * BITS_PER_SAMPLE / 8
        private const val BYTE_RATE = SAMPLE_RATE * BLOCK_ALIGN
        private const val SILENT_DATA_BYTES = SAMPLE_RATE * BLOCK_ALIGN
        private const val WAV_HEADER_BYTES = 44
        private const val ZERO_BUFFER_BYTES = 8_192

        private val RIFF_TAG = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WAVE_TAG = "WAVE".toByteArray(Charsets.US_ASCII)
        private val FMT_TAG = "fmt ".toByteArray(Charsets.US_ASCII)
        private val DATA_TAG = "data".toByteArray(Charsets.US_ASCII)
    }
}
