package dev.spainclaro.tanoshi.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.spainclaro.tanoshi.BuildConfig
import dev.spainclaro.tanoshi.R
import dev.spainclaro.tanoshi.settings.AppSettings
import dev.spainclaro.tanoshi.ui.ServerViewModel
import dev.spainclaro.tanoshi.ui.theme.TanoshiMonoTextStyle
import dev.spainclaro.tanoshi.ui.theme.ThemeMode
import dev.spainclaro.tanoshi.ui.theme.colorSchemeFor
import dev.spainclaro.tanoshi.updates.UpdateCheckResult
import dev.spainclaro.tanoshi.updates.UpdateInfo
import dev.spainclaro.tanoshi.updates.UpdateManager
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wrapper/behaviour settings: appearance/theme, server port, LAN exposure,
 * config.yml editor, notification/battery permissions, in-app updates and the
 * on-device storage paths.
 */
@Composable
fun SettingsScreen(
    viewModel: ServerViewModel,
    serverRunning: Boolean,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember(context) { UpdateManager(context) }

    val initialPort = remember { viewModel.currentPort().toString() }
    var portText by rememberSaveable { mutableStateOf(initialPort) }
    var exposeOnLan by remember { mutableStateOf(AppSettings.exposeOnLan) }
    var showConfigEditor by rememberSaveable { mutableStateOf(false) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var batteryOptimisationIgnored by remember {
        mutableStateOf(isIgnoringBatteryOptimisations(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AppSettings.notificationsRequested = true
        notificationsGranted = hasNotificationPermission(context)
        onShowMessage(
            context.getString(
                if (granted) R.string.settings_notification_granted_toast
                else R.string.settings_notification_denied_toast,
            ),
        )
    }

    // The user may grant/revoke permissions in the system UI; refresh on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = hasNotificationPermission(context)
                batteryOptimisationIgnored = isIgnoringBatteryOptimisations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val portValue = portText.toIntOrNull()
    val portValid = portValue != null && viewModel.isValidPort(portValue)
    val portError = portText.isNotEmpty() && !portValid

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
            AppearanceContent(themeMode = themeMode, onThemeChange = onThemeChange)
        }

        SettingsSection(title = stringResource(R.string.settings_section_server)) {
            OutlinedTextField(
                value = portText,
                onValueChange = { input ->
                    if (input.length <= 5 && input.all { it.isDigit() }) {
                        portText = input
                    }
                },
                label = { Text(stringResource(R.string.settings_port_label)) },
                singleLine = true,
                isError = portError,
                supportingText = {
                    Text(
                        if (portError) stringResource(R.string.settings_port_invalid)
                        else stringResource(R.string.settings_port_support),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val value = portValue ?: return@Button
                    scope.launch {
                        viewModel.savePort(value).fold(
                            onSuccess = {
                                portText = value.toString()
                                onShowMessage(context.getString(R.string.settings_port_saved))
                            },
                            onFailure = { error ->
                                onShowMessage(
                                    context.getString(
                                        R.string.settings_port_save_failed,
                                        error.message
                                            ?: context.getString(R.string.settings_unknown_error),
                                    ),
                                )
                            },
                        )
                    }
                },
                enabled = portValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_port_save))
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_network)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_expose_lan),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_expose_lan_support),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = exposeOnLan,
                    onCheckedChange = { checked ->
                        exposeOnLan = checked
                        viewModel.setExposeOnLan(checked)
                        onShowMessage(
                            context.getString(
                                if (serverRunning) R.string.settings_lan_saved
                                else R.string.settings_lan_saved_idle,
                            ),
                        )
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_expose_lan_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_config)) {
            Text(
                text = stringResource(R.string.settings_config_support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showConfigEditor = true },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_config_edit))
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_permissions)) {
            Text(
                text = stringResource(R.string.settings_notifications),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    stringResource(R.string.settings_notifications_not_needed)
                } else if (notificationsGranted) {
                    stringResource(R.string.settings_notifications_granted)
                } else {
                    stringResource(R.string.settings_notifications_denied)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (notificationsGranted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = stringResource(R.string.settings_notifications_support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
                    OutlinedButton(
                        onClick = {
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_notifications_request))
                    }
                }
                OutlinedButton(
                    onClick = { openNotificationSettings(context, onShowMessage) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_notifications_open))
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.settings_battery),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (batteryOptimisationIgnored) {
                    stringResource(R.string.settings_battery_ignored)
                } else {
                    stringResource(R.string.settings_battery_optimised)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (batteryOptimisationIgnored) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { requestIgnoreBatteryOptimisations(context, onShowMessage) },
                enabled = !batteryOptimisationIgnored,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (batteryOptimisationIgnored) R.string.settings_battery_already
                        else R.string.settings_battery_action,
                    ),
                )
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_updates)) {
            UpdatesContent(updateManager = updateManager, onShowMessage = onShowMessage)
        }

        SettingsSection(title = stringResource(R.string.settings_section_storage)) {
            Text(
                text = stringResource(R.string.settings_storage_data_dir),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = viewModel.dataDirectory,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        copyToClipboard(context, "Tanoshi data directory", viewModel.dataDirectory)
                        onShowMessage(context.getString(R.string.settings_path_copied))
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.settings_copy_path),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_storage_log_file),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = viewModel.serverLogPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }

    if (showConfigEditor) {
        ConfigEditorDialog(
            viewModel = viewModel,
            onDismiss = { showConfigEditor = false },
            onSaved = {
                // The editor may have changed the port line.
                portText = viewModel.currentPort().toString()
            },
            onShowMessage = onShowMessage,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ConfigEditorDialog(
    viewModel: ServerViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val loaded = viewModel.loadConfig()
        text = loaded
        loadFailed = loaded == null
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.settings_config_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val content = text
                when {
                    loadFailed -> Text(
                        text = stringResource(R.string.settings_config_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    content == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.settings_config_loading))
                    }

                    else -> OutlinedTextField(
                        value = content,
                        onValueChange = { updated ->
                            text = updated
                            dirty = true
                            error = null
                        },
                        textStyle = TanoshiMonoTextStyle,
                        enabled = !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 320.dp),
                    )
                }
                val message = error
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_config_support),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val content = text ?: return@TextButton
                    saving = true
                    error = null
                    scope.launch {
                        viewModel.saveConfig(content).fold(
                            onSuccess = {
                                saving = false
                                onShowMessage(context.getString(R.string.settings_config_saved))
                                onSaved()
                                onDismiss()
                            },
                            onFailure = { failure ->
                                saving = false
                                error = context.getString(
                                    R.string.settings_config_invalid,
                                    failure.message
                                        ?: context.getString(R.string.settings_unknown_error),
                                )
                            },
                        )
                    }
                },
                enabled = text != null && dirty && !saving,
            ) {
                Text(stringResource(R.string.settings_config_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.settings_config_cancel))
            }
        },
    )
}

@Composable
private fun AppearanceContent(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_theme_support),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    ThemeMode.entries.forEach { mode ->
        ThemeOptionRow(
            mode = mode,
            selected = themeMode == mode,
            onClick = { onThemeChange(mode) },
        )
    }
}

@Composable
private fun ThemeOptionRow(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        ThemeSwatch(mode = mode)
        Spacer(Modifier.width(12.dp))
        Text(
            text = themeLabel(mode),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Small preview of a theme: its background plus primary/secondary accents. */
@Composable
private fun ThemeSwatch(mode: ThemeMode) {
    val scheme = colorSchemeFor(mode)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(scheme.primary, CircleShape),
        )
        Box(
            Modifier
                .size(8.dp)
                .background(scheme.secondary, CircleShape),
        )
        Box(
            Modifier
                .size(8.dp)
                .background(scheme.surfaceContainerHighest, CircleShape),
        )
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.NORD -> R.string.theme_nord
        ThemeMode.CATPPUCCIN -> R.string.theme_catppuccin
    },
)

/**
 * In-app updater UI: check GitHub releases, download the APK and hand it to the
 * system package installer. All blocking work runs on [Dispatchers.IO]; the
 * progress callback updates Compose state directly (the updater throttles it).
 */
@Composable
private fun UpdatesContent(
    updateManager: UpdateManager,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var downloadTotalBytes by remember { mutableStateOf(0L) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var canInstall by remember { mutableStateOf(updateManager.canInstallPackages()) }

    // "Install unknown apps" can be granted/revoked in the system UI.
    DisposableEffect(lifecycleOwner, updateManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = updateManager.canInstallPackages()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onCheckForUpdates: () -> Unit = {
        if (!checking && !downloading) {
            scope.launch {
                checking = true
                updateError = null
                checkResult = null
                downloadedApk = null
                downloadedBytes = 0L
                downloadTotalBytes = 0L
                val result = try {
                    withContext(Dispatchers.IO) { updateManager.checkForUpdate() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    UpdateCheckResult.Failure(
                        throwable.message ?: context.getString(R.string.settings_unknown_error),
                    )
                }
                checkResult = result
                checking = false
            }
        }
    }

    val onDownloadUpdate: (UpdateInfo) -> Unit = { info ->
        if (!downloading) {
            scope.launch {
                downloading = true
                updateError = null
                downloadedApk = null
                downloadedBytes = 0L
                downloadTotalBytes = info.apkSizeBytes
                val outcome = try {
                    withContext(Dispatchers.IO) {
                        updateManager.downloadUpdate(info) { downloaded, total ->
                            downloadedBytes = downloaded
                            if (total > 0L) {
                                downloadTotalBytes = total
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    Result.failure(throwable)
                }
                downloading = false
                outcome.fold(
                    onSuccess = { apk -> downloadedApk = apk },
                    onFailure = { failure ->
                        updateError = context.getString(
                            R.string.settings_updates_download_failed,
                            failure.message ?: context.getString(R.string.settings_unknown_error),
                        )
                    },
                )
            }
        }
    }

    val onInstallUpdate: () -> Unit = {
        val apk = downloadedApk
        if (apk != null) {
            if (!updateManager.canInstallPackages()) {
                canInstall = false
                updateError = context.getString(R.string.settings_updates_install_blocked)
            } else {
                updateError = null
                updateManager.installUpdate(apk) { outcome ->
                    outcome.fold(
                        onSuccess = {
                            onShowMessage(
                                context.getString(R.string.settings_updates_install_started),
                            )
                        },
                        onFailure = { failure ->
                            updateError = context.getString(
                                R.string.settings_updates_install_failed,
                                failure.message
                                    ?: context.getString(R.string.settings_unknown_error),
                            )
                        },
                    )
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(
                R.string.settings_updates_current,
                updateManager.currentVersionName,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(
            onClick = onCheckForUpdates,
            enabled = !checking && !downloading,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_updates_checking))
            } else {
                Text(stringResource(R.string.settings_updates_check))
            }
        }

        when (val result = checkResult) {
            null -> Unit

            is UpdateCheckResult.UpToDate -> Text(
                text = stringResource(R.string.settings_updates_up_to_date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is UpdateCheckResult.Failure -> Text(
                text = stringResource(R.string.settings_updates_check_failed, result.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            is UpdateCheckResult.UpdateAvailable -> {
                val info = result.info
                Text(
                    text = stringResource(
                        R.string.settings_updates_available,
                        info.versionName,
                        updateManager.currentVersionName,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = info.tag,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.settings_updates_notes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = info.notes.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (info.apkSizeBytes > 0L) {
                    Text(
                        text = stringResource(
                            R.string.settings_updates_size,
                            formatByteSize(info.apkSizeBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    downloading -> {
                        Text(
                            text = stringResource(R.string.settings_updates_downloading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (downloadTotalBytes > 0L) {
                            val fraction = (downloadedBytes.toFloat() /
                                downloadTotalBytes.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_updates_progress,
                                    (fraction * 100f).toInt(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            // The updater reports total = 0 when it cannot know
                            // the size (no Content-Length and no asset size).
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    downloadedApk != null -> {
                        Text(
                            text = stringResource(R.string.settings_updates_downloaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onInstallUpdate,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_updates_install))
                        }
                    }

                    else -> Button(
                        onClick = { onDownloadUpdate(info) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_updates_download))
                    }
                }
            }
        }

        val error = updateError
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (!canInstall) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_updates_install_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(
                            onClick = {
                                runCatching { updateManager.openUnknownSourcesSettings() }
                                    .onFailure {
                                        onShowMessage(
                                            context.getString(R.string.settings_could_not_open),
                                        )
                                    }
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.settings_updates_allow_installs),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "?"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb.toLong() -> String.format(Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb.toLong() -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb.toLong() -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun isIgnoringBatteryOptimisations(context: Context): Boolean = runCatching {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    powerManager.isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(false)

private fun requestIgnoreBatteryOptimisations(
    context: Context,
    onShowMessage: (String) -> Unit,
) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isSuccess) return

    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallbackOpened = runCatching { context.startActivity(fallback) }.isSuccess
    if (!fallbackOpened) {
        onShowMessage(context.getString(R.string.settings_could_not_open))
    }
}

private fun openNotificationSettings(
    context: Context,
    onShowMessage: (String) -> Unit,
) {
    val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isSuccess) return

    val fallback = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallbackOpened = runCatching { context.startActivity(fallback) }.isSuccess
    if (!fallbackOpened) {
        onShowMessage(context.getString(R.string.settings_could_not_open))
    }
}
