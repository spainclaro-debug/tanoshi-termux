package dev.spainclaro.tanoshi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.spainclaro.tanoshi.R
import dev.spainclaro.tanoshi.server.RunState
import dev.spainclaro.tanoshi.server.ServerStateRepository
import dev.spainclaro.tanoshi.server.TanhoshiService
import dev.spainclaro.tanoshi.settings.AppSettings
import dev.spainclaro.tanoshi.ui.screens.BrowserScreen
import dev.spainclaro.tanoshi.ui.screens.LogsScreen
import dev.spainclaro.tanoshi.ui.screens.ServerScreen
import dev.spainclaro.tanoshi.ui.screens.SettingsScreen
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private enum class RootTab(val labelRes: Int, val icon: ImageVector) {
    SERVER(R.string.nav_server, Icons.Filled.Dns),
    LOGS(R.string.nav_logs, Icons.AutoMirrored.Filled.Article),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}

/**
 * App shell: Material3 [Scaffold], bottom navigation, the one-shot notification
 * permission prompt and the first-run admin dialog. Screen selection is a plain
 * [RootTab] enum kept in [rememberSaveable] - the app deliberately avoids a
 * navigation dependency.
 */
@Composable
fun TanoshiRoot(
    viewModel: ServerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val snapshot by ServerStateRepository.state.collectAsStateWithLifecycle()
    val logs by ServerStateRepository.logs.collectAsStateWithLifecycle()
    val adminDialog by viewModel.adminDialog.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTabName by rememberSaveable { mutableStateOf(RootTab.SERVER.name) }
    val selectedTab = RootTab.entries.firstOrNull { it.name == selectedTabName } ?: RootTab.SERVER
    var browserVisible by rememberSaveable { mutableStateOf(false) }

    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    // One-shot POST_NOTIFICATIONS request (Android 13+). The flag is written
    // before launching so a process death mid-prompt never re-asks on launch.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AppSettings.notificationsRequested = true
        showMessage(
            context.getString(
                if (granted) R.string.notification_permission_granted
                else R.string.notification_permission_denied,
            ),
        )
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !AppSettings.notificationsRequested
        ) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            AppSettings.notificationsRequested = true
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> showMessage(message) }
    }

    val startServer: () -> Unit = {
        runCatching { TanhoshiService.start(context) }.onFailure { error ->
            showMessage(
                context.getString(
                    R.string.server_start_failed,
                    error.message ?: context.getString(R.string.settings_unknown_error),
                ),
            )
        }
    }
    val stopServer: () -> Unit = {
        runCatching { TanhoshiService.stop(context) }.onFailure { error ->
            showMessage(
                context.getString(
                    R.string.server_stop_failed,
                    error.message ?: context.getString(R.string.settings_unknown_error),
                ),
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!browserVisible) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    RootTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTabName = tab.name },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (browserVisible) {
                BrowserScreen(
                    port = snapshot.port,
                    isRunning = snapshot.state == RunState.RUNNING,
                    adminCreated = AppSettings.adminCreated,
                    onExit = { browserVisible = false },
                    onStartServer = startServer,
                    onShowMessage = showMessage,
                )
            } else {
                when (selectedTab) {
                    RootTab.SERVER -> ServerScreen(
                        snapshot = snapshot,
                        exposeOnLan = AppSettings.exposeOnLan,
                        onStart = startServer,
                        onStop = stopServer,
                        onOpenInApp = { browserVisible = true },
                        onViewLogs = { selectedTabName = RootTab.LOGS.name },
                        onShowMessage = showMessage,
                    )

                    RootTab.LOGS -> LogsScreen(
                        logs = logs,
                        onClearLogs = {
                            ServerStateRepository.clearLogs()
                            showMessage(context.getString(R.string.logs_cleared))
                        },
                        onShowMessage = showMessage,
                    )

                    RootTab.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        serverRunning = snapshot.state == RunState.RUNNING,
                        onShowMessage = showMessage,
                    )
                }
            }
        }
    }

    val dialogState = adminDialog
    if (dialogState != null) {
        FirstRunAdminDialog(
            state = dialogState,
            onSubmit = viewModel::submitAdminAccount,
            onSkip = viewModel::skipAdminSetup,
            onDismiss = viewModel::dismissAdminDialog,
        )
    }
}

/**
 * Asks for the first admin account. Tanoshi cannot create an admin once users
 * exist, so this is only offered while the server reports no activated user;
 * GraphQL failures from [AdminBootstrap] are surfaced in the dialog body.
 */
@Composable
private fun FirstRunAdminDialog(
    state: AdminDialogState,
    onSubmit: (String, String) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("admin") }
    var password by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val busy = state.checking || state.submitting
    val usernameError = stringResource(R.string.admin_error_username)
    val passwordError = stringResource(R.string.admin_error_password)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.admin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.admin_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.checking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.admin_checking),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { input ->
                            username = input
                            localError = null
                        },
                        label = { Text(stringResource(R.string.admin_username)) },
                        singleLine = true,
                        enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { input ->
                            password = input
                            localError = null
                        },
                        label = { Text(stringResource(R.string.admin_password)) },
                        singleLine = true,
                        enabled = !state.submitting,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val error = localError ?: state.error
                    if (error != null) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.submitting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.admin_creating),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleanUsername = username.trim()
                    localError = when {
                        cleanUsername.length < 3 -> usernameError
                        password.length < 8 -> passwordError
                        else -> null
                    }
                    if (localError == null) {
                        onSubmit(cleanUsername, password)
                    }
                },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.admin_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip, enabled = !busy) {
                Text(stringResource(R.string.admin_skip))
            }
        },
    )
}
