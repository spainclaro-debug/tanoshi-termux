package dev.spainclaro.tanoshi.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.spainclaro.tanoshi.R
import dev.spainclaro.tanoshi.server.AdminBootstrap
import dev.spainclaro.tanoshi.server.AppPaths
import dev.spainclaro.tanoshi.server.ConfigManager
import dev.spainclaro.tanoshi.server.RunState
import dev.spainclaro.tanoshi.server.ServerStateRepository
import dev.spainclaro.tanoshi.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the first-run admin onboarding. [checking] is true while the
 * GraphQL status probe runs; [submitting] while the register mutation is in
 * flight; [error] carries a human readable GraphQL/HTTP failure.
 */
data class AdminDialogState(
    val checking: Boolean = true,
    val submitting: Boolean = false,
    val error: String? = null,
)

/**
 * UI-facing logic: first-run admin onboarding (through [AdminBootstrap]) and
 * the few settings operations that touch config.yml (through [ConfigManager]).
 *
 * The live server snapshot and log lines intentionally stay in
 * [ServerStateRepository] so the foreground service can update them without
 * involving the UI; screens collect those flows directly.
 */
class ServerViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context
        get() = getApplication<Application>().applicationContext

    private val _adminDialog = MutableStateFlow<AdminDialogState?>(null)
    val adminDialog: StateFlow<AdminDialogState?> = _adminDialog.asStateFlow()

    /** One-shot snackbar messages (channels are buffered so none are lost). */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val dataDirectory: String = AppPaths.home(application).absolutePath
    val serverLogPath: String = AppPaths.serverLog(application).absolutePath

    /**
     * The admin probe runs once per RUNNING transition: without this, every
     * snapshot update would re-open the dialog after the user skipped it.
     */
    private var adminCheckArmed = true

    init {
        viewModelScope.launch {
            ServerStateRepository.state.collect { snapshot ->
                if (snapshot.state == RunState.RUNNING) {
                    if (adminCheckArmed && !AppSettings.adminCreated) {
                        adminCheckArmed = false
                        // Separate coroutine: the HTTP probe must not stall the
                        // state collector (the service updates it constantly).
                        viewModelScope.launch { checkForFirstRunAdmin(snapshot.port) }
                    }
                } else {
                    adminCheckArmed = true
                    val dialog = _adminDialog.value
                    if (dialog != null && !dialog.submitting) {
                        _adminDialog.value = null
                    }
                }
            }
        }
    }

    private suspend fun checkForFirstRunAdmin(port: Int) {
        _adminDialog.value = AdminDialogState(checking = true)
        // AdminBootstrap reports probe failures as `false`, so an unreachable
        // server simply looks "not activated"; only a real cancellation aborts.
        val activated = withContext(Dispatchers.IO) {
            try {
                AdminBootstrap.isActivated(port)
            } catch (e: CancellationException) {
                throw e
            }
        }
        val stillRunning = ServerStateRepository.state.value.state == RunState.RUNNING
        _adminDialog.value = if (activated || !stillRunning || AppSettings.adminCreated) {
            null
        } else {
            AdminDialogState(checking = false)
        }
    }

    fun submitAdminAccount(username: String, password: String) {
        val dialog = _adminDialog.value ?: return
        if (dialog.checking || dialog.submitting) return

        val cleanUsername = username.trim()
        val port = ServerStateRepository.state.value.port
        _adminDialog.value = dialog.copy(submitting = true, error = null)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    AdminBootstrap.createAdmin(port, cleanUsername, password)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }
            result.fold(
                onSuccess = {
                    AppSettings.adminCreated = true
                    _adminDialog.value = null
                    _messages.trySend(string(R.string.admin_created))
                },
                onFailure = { error ->
                    val message = error.message?.takeIf { it.isNotBlank() }
                        ?: string(R.string.admin_error_generic)
                    _adminDialog.value = _adminDialog.value?.copy(submitting = false, error = message)
                },
            )
        }
    }

    /** Dismisses the dialog without marking the admin as created. */
    fun skipAdminSetup() {
        val dialog = _adminDialog.value ?: return
        if (dialog.submitting) return
        _adminDialog.value = null
        _messages.trySend(string(R.string.admin_skipped))
    }

    /** Tap-outside / back dismissal; ignored while a request is in flight. */
    fun dismissAdminDialog() {
        val dialog = _adminDialog.value ?: return
        if (dialog.submitting) return
        _adminDialog.value = null
    }

    // --- Settings ----------------------------------------------------------

    fun currentPort(): Int = ConfigManager.currentPort(appContext)

    fun isValidPort(port: Int): Boolean = ConfigManager.isValidPort(port)

    suspend fun savePort(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        ConfigManager.setPort(appContext, port)
    }

    suspend fun loadConfig(): String? = withContext(Dispatchers.IO) {
        runCatching { ConfigManager.ensureSeeded(appContext) }
        ConfigManager.readConfig(appContext)
    }

    suspend fun saveConfig(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        ConfigManager.writeConfig(appContext, text)
    }

    fun setExposeOnLan(enabled: Boolean) {
        AppSettings.exposeOnLan = enabled
    }

    private fun string(resId: Int): String = getApplication<Application>().getString(resId)
}
