package dev.spainclaro.tanoshi.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Lifecycle of the local Tanoshi process. */
enum class RunState { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/** Immutable snapshot shown by the UI and the notification. */
data class ServerSnapshot(
    val state: RunState = RunState.STOPPED,
    val pid: Int? = null,
    val port: Int = 8080,
    val bindAddress: String = "127.0.0.1",
    val startedAtMillis: Long? = null,
    val lastError: String? = null,
)

enum class LogStream { STDOUT, STDERR, SYSTEM }

data class LogLine(
    val timestampMillis: Long = System.currentTimeMillis(),
    val stream: LogStream = LogStream.SYSTEM,
    val text: String,
)

/**
 * In-process source of truth shared by [TanhoshiService] and the UI. The app
 * runs the server in its own process, so a singleton is enough - no binder
 * plumbing required.
 */
object ServerStateRepository {

    private const val MAX_LOG_LINES = 1_500

    private val _state = MutableStateFlow(ServerSnapshot())
    val state: StateFlow<ServerSnapshot> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    fun update(transform: (ServerSnapshot) -> ServerSnapshot) {
        _state.update(transform)
    }

    fun setState(snapshot: ServerSnapshot) {
        _state.value = snapshot
    }

    fun appendLog(line: LogLine) {
        _logs.update { current ->
            val next = if (current.size >= MAX_LOG_LINES) {
                current.subList(current.size - MAX_LOG_LINES + 1, current.size) + line
            } else {
                current + line
            }
            next
        }
    }

    fun appendLog(stream: LogStream, text: String) =
        appendLog(LogLine(stream = stream, text = text))

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun reset() {
        _state.value = ServerSnapshot()
    }
}
