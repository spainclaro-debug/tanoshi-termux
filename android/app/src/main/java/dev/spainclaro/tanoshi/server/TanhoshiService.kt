package dev.spainclaro.tanoshi.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.spainclaro.tanoshi.R
import dev.spainclaro.tanoshi.settings.AppSettings
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the bundled Tanoshi server process.
 *
 * The service starts the native executable (`libtanoshi.so`, extracted by the
 * APK into `nativeLibraryDir`), pumps its stdout/stderr into
 * [ServerStateRepository] and the on-disk log, probes the HTTP port until the
 * server answers, and keeps the process alive with a partial wake lock.
 *
 * Only the companion [start]/[stop]/[restart] entry points and the
 * [ServerStateRepository] flows are part of the public contract; the UI never
 * needs to bind to the service.
 */
class TanhoshiService : Service() {

    /**
     * Job/scope for every blocking operation. All of them run on
     * [Dispatchers.IO]; the main thread only ever builds a notification and
     * launches a coroutine.
     */
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    /** Guards the run/stop bookkeeping below. Never held across blocking I/O. */
    private val stateLock = Any()

    /** Guards notification creation/updates. */
    private val notificationLock = Any()

    /** The coroutine that spawns and supervises the current server process. */
    private var runJob: Job? = null

    /** The coroutine performing a stop/restart, if one is in flight. */
    private var stopJob: Job? = null

    /** The live server process, if one has been spawned. */
    private var activeProcess: Process? = null

    /**
     * Incremented on every start and every stop request. A supervisor coroutine
     * whose generation no longer matches is stale (a stop/restart superseded
     * it) and must not touch the shared state.
     */
    private var runGeneration = 0

    /** Set when the user asks for a start while a stop is still running. */
    private var startRequestedWhileStopping = false

    /** True once [onDestroy] ran; no new coroutine may start work. */
    private var destroyed = false

    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var logWriter: ServerLogWriter

    @Volatile
    private var foregroundActive = false

    // -----------------------------------------------------------------------
    // Service lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        // TanoshiApp already initialises this; repeated init is harmless and
        // keeps the service usable if it is ever started directly (e.g. tests).
        AppSettings.init(applicationContext)
        createNotificationChannel()
        logWriter = ServerLogWriter.forContext(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_STOP -> {
                AppSettings.serverShouldRun = false
                // Always present a foreground notification first: the service
                // may just have been created via startForegroundService and
                // would otherwise hit the 5 second ANR deadline.
                showState(RunState.STOPPING)
                beginStop()
            }

            ACTION_RESTART -> {
                AppSettings.serverShouldRun = true
                showState(RunState.STARTING)
                beginRestart()
            }

            ACTION_START -> {
                AppSettings.serverShouldRun = true
                showState(RunState.STARTING)
                beginStart()
            }

            else -> {
                // Null action: the system recreated the service (START_STICKY)
                // after killing the app process.
                if (AppSettings.serverShouldRun) {
                    Log.i(TAG, "Service recreated by the system; resuming the server")
                    showState(RunState.STARTING)
                    beginStart()
                } else {
                    Log.i(TAG, "Service recreated by the system, but the server should stay stopped")
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                if (action != null) Log.w(TAG, "Unknown service action: $action")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val previousState = ServerStateRepository.state.value.state
        val process = synchronized(stateLock) {
            destroyed = true
            runGeneration++
            val current = activeProcess
            activeProcess = null
            current
        }
        // Cancel the supervisor; whatever it was waiting on (a delay, a port
        // probe) stops immediately. Blocking reads unblock when the process dies.
        serviceScope.cancel()
        if (process != null) {
            Log.i(TAG, "Service destroyed while the server was running; terminating it")
            runCatching { process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
        }
        runCatching { logWriter.close() }
        releaseWakeLock()
        synchronized(notificationLock) {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            foregroundActive = false
        }
        if (previousState == RunState.STARTING ||
            previousState == RunState.RUNNING ||
            previousState == RunState.STOPPING
        ) {
            ServerStateRepository.update {
                it.copy(state = RunState.STOPPED, pid = null, startedAtMillis = null)
            }
        }
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Start / stop coordination
    // -----------------------------------------------------------------------

    /** Starts the server; no-op when it is already starting/running. */
    private fun beginStart() {
        synchronized(stateLock) {
            if (destroyed) return
            if (stopJob?.isActive == true) {
                // A stop/restart is in flight; start once it has finished.
                startRequestedWhileStopping = true
                return
            }
            if (runJob?.isActive == true) {
                // Already starting/running: make sure the foreground
                // notification reflects the real state instead of the
                // "starting" text repeated from onStartCommand.
                showState(ServerStateRepository.state.value.state)
                return
            }
            startInternalLocked()
        }
    }

    /** Stops the running server (or no-ops when nothing is running). */
    private fun beginStop() {
        synchronized(stateLock) {
            if (destroyed) return
            if (stopJob?.isActive == true) return
            runGeneration++
            ServerStateRepository.update { it.copy(state = RunState.STOPPING) }
            stopJob = serviceScope.launch { stopServer() }
        }
        showState(RunState.STOPPING)
    }

    /** Restarts the server, stopping first when it is currently active. */
    private fun beginRestart() {
        var needsStop = false
        synchronized(stateLock) {
            if (destroyed) return
            if (stopJob?.isActive == true) {
                startRequestedWhileStopping = true
                return
            }
            needsStop = runJob?.isActive == true
            if (needsStop) startRequestedWhileStopping = true
        }
        if (needsStop) {
            beginStop()
        } else {
            synchronized(stateLock) {
                if (!destroyed) startInternalLocked()
            }
        }
    }

    /** Caller must hold [stateLock]. */
    private fun startInternalLocked() {
        if (destroyed) return
        if (runJob?.isActive == true) return
        val generation = ++runGeneration
        ServerStateRepository.update {
            it.copy(
                state = RunState.STARTING,
                pid = null,
                startedAtMillis = null,
                lastError = null,
            )
        }
        acquireWakeLock()
        runJob = serviceScope.launch { runServer(generation) }
        showState(RunState.STARTING)
    }

    private suspend fun stopServer() {
        showState(RunState.STOPPING)
        logSystem("Stopping the Tanoshi server…")

        val process = synchronized(stateLock) { activeProcess }
        val run = synchronized(stateLock) { runJob }

        if (process != null) {
            terminateProcess(process)
        }

        var drained = true
        if (run != null) {
            drained = withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) {
                run.join()
                true
            } ?: false
        }
        if (!drained) {
            logSystem(
                "The server supervisor did not finish within ${STOP_JOIN_TIMEOUT_MS / 1000} s; " +
                    "forcing shutdown",
            )
            run?.cancel()
            synchronized(stateLock) { activeProcess }?.let { terminateProcess(it) }
        }

        val shouldStart = synchronized(stateLock) {
            activeProcess = null
            if (runJob === run) runJob = null
            ServerStateRepository.update {
                it.copy(state = RunState.STOPPED, pid = null, startedAtMillis = null, lastError = null)
            }
            val pending = startRequestedWhileStopping
            startRequestedWhileStopping = false
            stopJob = null
            pending
        }
        logSystem("Tanoshi server stopped")

        if (shouldStart) {
            synchronized(stateLock) {
                if (!destroyed) startInternalLocked()
            }
        } else {
            releaseWakeLock()
            stopForegroundAndSelf()
        }
    }

    // -----------------------------------------------------------------------
    // Process supervision
    // -----------------------------------------------------------------------

    private suspend fun runServer(generation: Int) {
        try {
            runServerInternal(generation)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "The server supervisor failed unexpectedly", t)
            val process = synchronized(stateLock) {
                val current = activeProcess
                activeProcess = null
                current
            }
            if (process != null) {
                runCatching { process.destroy() }
                runCatching { if (process.isAlive) process.destroyForcibly() }
            }
            if (!isStale(generation)) {
                failStartup("Unexpected error while running the Tanoshi server: ${t.describe()}")
            }
        }
    }

    private suspend fun runServerInternal(generation: Int) = coroutineScope {
        val app = applicationContext

        val spec = try {
            if (isStale(generation)) return@coroutineScope
            buildLaunchSpec(app)
        } catch (e: StartupFailure) {
            failStart(generation, e.message ?: "Could not prepare the Tanoshi server")
            return@coroutineScope
        } catch (t: Throwable) {
            failStart(generation, "Could not prepare the Tanoshi server: ${t.describe()}")
            return@coroutineScope
        }

        if (isStale(generation)) return@coroutineScope

        ServerStateRepository.update {
            it.copy(
                state = RunState.STARTING,
                pid = null,
                port = spec.port,
                bindAddress = spec.bindAddress,
                startedAtMillis = System.currentTimeMillis(),
                lastError = null,
            )
        }
        showState(RunState.STARTING)
        logSystem(
            "Starting ${spec.binary.name} on ${spec.bindAddress}:${spec.port} " +
                "(TANOSHI_HOME=${spec.home.absolutePath})",
        )

        val process = try {
            spec.newProcessBuilder().start()
        } catch (t: Throwable) {
            failStart(
                generation,
                "Could not launch the bundled Tanoshi binary (${spec.binary.absolutePath}): " +
                    "${t.describe()}",
            )
            return@coroutineScope
        }

        val accepted = synchronized(stateLock) {
            if (destroyed || isStaleLocked(generation)) {
                false
            } else {
                activeProcess = process
                true
            }
        }
        if (!accepted) {
            logSystem("Stop requested while the server process was starting; terminating it")
            terminateProcess(process)
            return@coroutineScope
        }

        val pid = processPid(process)
        ServerStateRepository.update { it.copy(state = RunState.STARTING, pid = pid) }
        logSystem("Tanoshi server process started (pid ${pid ?: "unknown"})")
        showState(RunState.STARTING)

        val stdoutJob = launch {
            pump(process.inputStream.bufferedReader(Charsets.UTF_8), LogStream.STDOUT)
        }
        val stderrJob = launch {
            pump(process.errorStream.bufferedReader(Charsets.UTF_8), LogStream.STDERR)
        }

        var startupError: String? = null
        var exitCode: Int? = null

        when (awaitReadiness(generation, process, spec.port)) {
            Readiness.READY -> {
                if (!isStale(generation)) {
                    ServerStateRepository.update {
                        it.copy(
                            state = RunState.RUNNING,
                            pid = pid,
                            port = spec.port,
                            bindAddress = spec.bindAddress,
                            startedAtMillis = System.currentTimeMillis(),
                            lastError = null,
                        )
                    }
                    showState(RunState.RUNNING)
                    logSystem("Tanoshi server is ready at http://${spec.bindAddress}:${spec.port}/")
                }
                exitCode = awaitExit(process)
            }

            Readiness.PROCESS_EXITED -> {
                exitCode = processExitCode(process)
                startupError = "The Tanoshi server exited before it became ready " +
                    "(exit code ${exitCode ?: "unknown"}); see the log above for details"
            }

            Readiness.TIMEOUT -> {
                val message = "The Tanoshi server did not answer on " +
                    "http://127.0.0.1:${spec.port}/ within ${READY_TIMEOUT_MS / 1000} seconds"
                logSystem(message)
                startupError = message
                terminateProcess(process)
                exitCode = processExitCode(process)
            }

            Readiness.ABORTED -> {
                terminateProcess(process)
                exitCode = processExitCode(process)
            }
        }

        // Drain the output pipes before reporting the exit so the log holds the
        // final lines (panic messages, port-in-use errors, …).
        stdoutJob.join()
        stderrJob.join()
        handleRunFinished(generation, process, pid, exitCode, startupError)
    }

    private fun buildLaunchSpec(app: Context): LaunchSpec {
        ConfigManager.ensureSeeded(app)
        val binary = AppPaths.serverBinary(app)
        if (!binary.isFile) {
            throw StartupFailure(
                "The bundled Tanoshi binary is missing (${binary.absolutePath}). " +
                    "Reinstall the app.",
            )
        }
        if (!binary.canExecute()) {
            Log.w(TAG, "${binary.absolutePath} is not marked executable; trying to launch it anyway")
        }
        val home = AppPaths.home(app)
        home.mkdirs()
        return LaunchSpec(
            binary = binary,
            home = home,
            filesDir = app.filesDir,
            nativeLibraryDir = AppPaths.nativeLibraryDir(app),
            cacheDir = app.cacheDir,
            port = ConfigManager.currentPort(app),
            bindAddress = if (AppSettings.exposeOnLan) BIND_ANY else BIND_LOOPBACK,
        )
    }

    private fun LaunchSpec.newProcessBuilder(): ProcessBuilder {
        val builder = ProcessBuilder(binary.absolutePath)
        builder.directory(home)
        builder.redirectErrorStream(false)
        val environment = builder.environment()
        environment["TANOSHI_HOME"] = home.absolutePath
        environment["HOME"] = filesDir.absolutePath
        environment["TANOSHI_LOG"] = "info"
        environment["TANOSHI_BIND"] = bindAddress
        environment["LD_LIBRARY_PATH"] = buildLibraryPath(nativeLibraryDir)
        environment["TMPDIR"] = cacheDir.absolutePath
        environment["RUST_BACKTRACE"] = "1"
        return builder
    }

    /**
     * An exec'd binary resolves its `DT_NEEDED` libraries through
     * `LD_LIBRARY_PATH` (plus the default linker search path), so the app's
     * `nativeLibraryDir` — where `libarchive.so` and the plugins live — has to
     * come first while the platform directories stay reachable.
     */
    private fun buildLibraryPath(nativeDir: File): String {
        val entries = LinkedHashSet<String>()
        entries.add(nativeDir.absolutePath)
        System.getenv("LD_LIBRARY_PATH")
            ?.split(':')
            ?.filter { it.isNotBlank() }
            ?.forEach { entries.add(it) }
        listOf("/system/lib64", "/vendor/lib64", "/system/lib", "/vendor/lib").forEach {
            entries.add(it)
        }
        return entries.joinToString(":")
    }

    private suspend fun awaitReadiness(
        generation: Int,
        process: Process,
        port: Int,
    ): Readiness {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        var lastFailure: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isStale(generation)) return Readiness.ABORTED
            if (!process.isAlive) return Readiness.PROCESS_EXITED
            when (val outcome = probeServer(port)) {
                is ProbeOutcome.Answered -> {
                    logSystem("Tanoshi answered with HTTP ${outcome.statusCode}; it is ready")
                    return Readiness.READY
                }

                is ProbeOutcome.NoAnswer -> lastFailure = outcome.reason
            }
            delay(READY_POLL_INTERVAL_MS)
        }
        if (!process.isAlive) return Readiness.PROCESS_EXITED
        if (isStale(generation)) return Readiness.ABORTED
        lastFailure?.let { logSystem("The Tanoshi server was still unreachable at timeout: $it") }
        return Readiness.TIMEOUT
    }

    /** One short GET against the local server; any HTTP response means "ready". */
    private fun probeServer(port: Int): ProbeOutcome {
        val connection = try {
            URL("http://127.0.0.1:$port/").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        } catch (t: Throwable) {
            return ProbeOutcome.NoAnswer(t.describe())
        }
        return try {
            connection.connectTimeout = PROBE_CONNECT_TIMEOUT_MS
            connection.readTimeout = PROBE_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Connection", "close")
            ProbeOutcome.Answered(connection.responseCode)
        } catch (e: IOException) {
            ProbeOutcome.NoAnswer(e.describe())
        } catch (t: Throwable) {
            ProbeOutcome.NoAnswer(t.describe())
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun pump(reader: BufferedReader, stream: LogStream) {
        try {
            reader.use { source ->
                while (true) {
                    val raw = source.readLine() ?: return
                    val line = raw.trimEnd('\r')
                    if (line.isBlank()) continue
                    ServerStateRepository.appendLog(stream, line)
                    logWriter.append(stream, line)
                }
            }
        } catch (e: IOException) {
            // Expected: the pipe closes when the server process exits.
            Log.d(TAG, "Log stream $stream closed: ${e.describe()}")
        } catch (t: Throwable) {
            Log.w(TAG, "Log reader for $stream stopped", t)
        }
    }

    private fun awaitExit(process: Process): Int? = try {
        process.waitFor()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (t: Throwable) {
        null
    }

    private fun processExitCode(process: Process): Int? =
        runCatching { process.exitValue() }.getOrNull()

    /**
     * PID of the server child process, best effort.
     *
     * Android's [Process] deliberately has no `pid()`: that is a Java 9 API
     * Android never shipped (only `isAlive()`, `destroyForcibly()` and the
     * timed `waitFor` arrived in API 26). `ProcessBuilder.start()` returns
     * `java.lang.UNIXProcess`, whose private `final int pid` field can be read
     * reflectively. If that ever changes, the state simply carries no PID (the
     * UI tolerates `null`), so this must never throw.
     */
    private fun processPid(process: Process): Int? = runCatching {
        val pidField = process.javaClass.getDeclaredField("pid")
        pidField.isAccessible = true
        (pidField.get(process) as? Number)?.toInt()
    }.getOrNull()

    private suspend fun handleRunFinished(
        generation: Int,
        process: Process,
        pid: Int?,
        exitCode: Int?,
        startupError: String?,
    ) {
        synchronized(stateLock) {
            if (activeProcess === process) activeProcess = null
        }
        logSystem(
            "Tanoshi server process ${pid ?: "?"} exited " +
                "(code ${exitCode?.toString() ?: "unknown"})",
        )
        if (isStale(generation)) {
            // A stop/restart is in flight and owns the state transitions.
            return
        }
        val message = startupError ?: when (exitCode) {
            null -> "The Tanoshi server process stopped unexpectedly"
            0 -> "The Tanoshi server exited unexpectedly (exit code 0)"
            else -> "The Tanoshi server exited unexpectedly (exit code $exitCode); " +
                "see the log for details"
        }
        // The server is not restartable by itself: surface the error and make
        // sure a sticky service restart does not resurrect it behind the
        // user's back.
        AppSettings.serverShouldRun = false
        ServerStateRepository.update {
            it.copy(state = RunState.ERROR, pid = null, startedAtMillis = null, lastError = message)
        }
        showState(RunState.ERROR, message)
        logSystem(message)
        releaseWakeLock()
        stopForegroundAndSelf()
    }

    private suspend fun terminateProcess(process: Process) {
        val pid = processPid(process)
        // Extension workers are spawned as children of the server process;
        // remember them before the parent disappears so they can be reaped too.
        val children = pid?.let { readChildPids(it.toLong()) } ?: emptyList()

        logSystem("Sending SIGTERM to the Tanoshi server${pid?.let { " (pid $it)" } ?: ""}")
        runCatching { process.destroy() }

        var waited = 0L
        while (process.isAlive && waited < STOP_TIMEOUT_MS) {
            delay(STOP_POLL_INTERVAL_MS)
            waited += STOP_POLL_INTERVAL_MS
        }

        if (process.isAlive) {
            logSystem("The server did not exit within ${STOP_TIMEOUT_MS / 1000} s; killing it")
            runCatching { process.destroyForcibly() }
            var forcedWait = 0L
            while (process.isAlive && forcedWait < FORCE_KILL_TIMEOUT_MS) {
                delay(STOP_POLL_INTERVAL_MS)
                forcedWait += STOP_POLL_INTERVAL_MS
            }
        }

        killChildProcesses(children)
    }

    /** PIDs from `/proc/<pid>/task/<pid>/children`; empty when unreadable. */
    private fun readChildPids(pid: Long): List<Long> {
        return try {
            val childrenFile = File("/proc/$pid/task/$pid/children")
            if (!childrenFile.isFile) return emptyList()
            childrenFile.readText().trim()
                .split(WHITESPACE)
                .mapNotNull { it.toLongOrNull() }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun killChildProcesses(pids: List<Long>) {
        for (pid in pids) {
            val childPid = pid.toInt()
            if (childPid <= 1) continue
            runCatching { android.os.Process.killProcess(childPid) }
                .onFailure { Log.w(TAG, "Could not kill leftover child process $childPid", it) }
        }
    }

    // -----------------------------------------------------------------------
    // Repository / log / notification helpers
    // -----------------------------------------------------------------------

    private fun logSystem(message: String) {
        ServerStateRepository.appendLog(LogStream.SYSTEM, message)
        logWriter.append(LogStream.SYSTEM, message)
        Log.i(TAG, message)
    }

    private fun failStart(generation: Int, message: String) {
        if (isStale(generation)) return
        failStartup(message)
    }

    private fun failStartup(message: String) {
        AppSettings.serverShouldRun = false
        ServerStateRepository.update {
            it.copy(state = RunState.ERROR, pid = null, startedAtMillis = null, lastError = message)
        }
        showState(RunState.ERROR, message)
        logSystem(message)
        releaseWakeLock()
        stopForegroundAndSelf()
    }

    private fun isStale(generation: Int): Boolean = synchronized(stateLock) {
        isStaleLocked(generation)
    }

    /** Caller must hold [stateLock]. */
    private fun isStaleLocked(generation: Int): Boolean =
        destroyed || generation != runGeneration

    // -----------------------------------------------------------------------
    // Wakelock
    // -----------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
        }
        val lock = wakeLock ?: return
        if (lock.isHeld) return
        runCatching { lock.acquire() }
            .onFailure { Log.w(TAG, "Could not acquire the partial wake lock", it) }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) return
        runCatching { lock.release() }
            .onFailure { Log.w(TAG, "Could not release the partial wake lock", it) }
    }

    // -----------------------------------------------------------------------
    // Foreground notification
    // -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    private fun showState(state: RunState, error: String? = null) {
        synchronized(notificationLock) {
            val notification = buildNotification(state, error)
            if (!foregroundActive) {
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        foregroundServiceType(),
                    )
                    foregroundActive = true
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not start the foreground server notification", t)
                }
            } else {
                runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
                    .onFailure { Log.w(TAG, "Could not update the server notification", it) }
            }
        }
    }

    private fun buildNotification(state: RunState, error: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            mainActivityIntent(),
            PENDING_INTENT_FLAGS,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP_SERVER,
            Intent(this, TanhoshiService::class.java).setAction(ACTION_STOP),
            PENDING_INTENT_FLAGS,
        )
        val port = ServerStateRepository.state.value.port
        val text = when (state) {
            RunState.STARTING -> getString(R.string.notification_starting)
            RunState.RUNNING -> getString(R.string.notification_running, port)
            RunState.STOPPING -> getString(R.string.notification_stopping)
            RunState.STOPPED -> getString(R.string.notification_stopped)
            RunState.ERROR -> error?.takeIf { it.isNotBlank() }
                ?.let { "Server error: $it" }
                ?: getString(R.string.notification_stopped)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    /**
     * Launcher intent for [dev.spainclaro.tanoshi.MainActivity] without a
     * compile-time dependency on the UI class (it is written in parallel).
     */
    private fun mainActivityIntent(): Intent {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            return launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(packageName, MAIN_ACTIVITY_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private fun stopForegroundAndSelf() {
        synchronized(notificationLock) {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            foregroundActive = false
        }
        stopSelf()
    }

    // -----------------------------------------------------------------------
    // Data holders
    // -----------------------------------------------------------------------

    private data class LaunchSpec(
        val binary: File,
        val home: File,
        val filesDir: File,
        val nativeLibraryDir: File,
        val cacheDir: File,
        val port: Int,
        val bindAddress: String,
    )

    private class StartupFailure(message: String) : IllegalStateException(message)

    private enum class Readiness { READY, PROCESS_EXITED, ABORTED, TIMEOUT }

    private sealed interface ProbeOutcome {
        data class Answered(val statusCode: Int) : ProbeOutcome

        data class NoAnswer(val reason: String) : ProbeOutcome
    }

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    // -----------------------------------------------------------------------
    // Public entry points (stable contract for the UI layer)
    // -----------------------------------------------------------------------

    companion object {
        /** Starts (or keeps) the server running. */
        const val ACTION_START = "dev.spainclaro.tanoshi.action.START"

        /** Stops the server and the foreground service. */
        const val ACTION_STOP = "dev.spainclaro.tanoshi.action.STOP"

        /** Restarts the server (used after config.yml changes). */
        const val ACTION_RESTART = "dev.spainclaro.tanoshi.action.RESTART"

        /** Notification id of the ongoing foreground notification. */
        const val NOTIFICATION_ID = 1001

        /** Notification channel used by the foreground service. */
        const val CHANNEL_ID = "tanoshi_server"

        private const val TAG = "TanhoshiService"
        private const val MAIN_ACTIVITY_CLASS = "dev.spainclaro.tanoshi.MainActivity"
        private const val WAKE_LOCK_TAG = "Tanoshi:Server"
        private const val BIND_LOOPBACK = "127.0.0.1"
        private const val BIND_ANY = "0.0.0.0"

        private const val READY_TIMEOUT_MS = 90_000L
        private const val READY_POLL_INTERVAL_MS = 500L
        private const val PROBE_CONNECT_TIMEOUT_MS = 1_500
        private const val PROBE_READ_TIMEOUT_MS = 2_000

        private const val STOP_TIMEOUT_MS = 10_000L
        private const val FORCE_KILL_TIMEOUT_MS = 3_000L
        private const val STOP_POLL_INTERVAL_MS = 100L
        private const val STOP_JOIN_TIMEOUT_MS = 20_000L

        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_STOP_SERVER = 2
        private val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private val WHITESPACE = Regex("\\s+")

        /** Starts the server, showing the foreground notification immediately. */
        @JvmStatic
        fun start(context: Context) = dispatch(context, ACTION_START)

        /** Stops the server gracefully; safe to call when it is not running. */
        @JvmStatic
        fun stop(context: Context) = dispatch(context, ACTION_STOP)

        /** Restarts the server, stopping it first if needed. */
        @JvmStatic
        fun restart(context: Context) = dispatch(context, ACTION_RESTART)

        private fun dispatch(context: Context, action: String) {
            val app = context.applicationContext
            val intent = Intent(app, TanhoshiService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (first: RuntimeException) {
                Log.w(TAG, "startForegroundService($action) failed; trying startService", first)
                try {
                    app.startService(intent)
                } catch (second: RuntimeException) {
                    Log.e(TAG, "Could not deliver $action to TanhoshiService", second)
                    handleDispatchFailure(app, action, second)
                }
            }
        }

        private fun handleDispatchFailure(app: Context, action: String, error: Throwable) {
            val verb = if (action == ACTION_STOP) "stop" else "start"
            val message = "Could not $verb the Tanoshi service: " +
                (error.message ?: error.javaClass.simpleName)
            Log.e(TAG, message)
            if (action == ACTION_STOP) {
                runCatching { app.stopService(Intent(app, TanhoshiService::class.java)) }
            } else {
                AppSettings.serverShouldRun = false
                ServerStateRepository.update {
                    it.copy(state = RunState.ERROR, lastError = message)
                }
            }
        }
    }
}
