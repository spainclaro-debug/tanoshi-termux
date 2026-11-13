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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.launch

/**
 * Wrapper/behaviour settings: server port, LAN exposure, config.yml editor,
 * notification/battery permissions and the on-device storage paths.
 */
@Composable
fun SettingsScreen(
    viewModel: ServerViewModel,
    serverRunning: Boolean,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
