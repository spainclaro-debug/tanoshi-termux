package dev.spainclaro.tanoshi.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.spainclaro.tanoshi.R
import dev.spainclaro.tanoshi.server.RunState
import dev.spainclaro.tanoshi.server.ServerSnapshot
import dev.spainclaro.tanoshi.ui.components.ServerStatusCard
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Main dashboard: lifecycle card, URL, start/stop action and the shortcuts into
 * the external or embedded browser. All intents are guarded so a device without
 * a browser cannot crash the app.
 */
@Composable
fun ServerScreen(
    snapshot: ServerSnapshot,
    exposeOnLan: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenInApp: () -> Unit,
    onViewLogs: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val url = remember(snapshot.port) { serverUrl(snapshot.port) }
    val running = snapshot.state == RunState.RUNNING
    val busy = snapshot.state == RunState.STARTING || snapshot.state == RunState.STOPPING
    val lanMode = snapshot.bindAddress == "0.0.0.0" || exposeOnLan
    val lanUrl = remember(lanMode, snapshot.port) {
        if (lanMode) findLanAddress()?.let { "http://$it:${snapshot.port}" } else null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.server_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.server_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ServerStatusCard(snapshot = snapshot)

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.server_url_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            copyToClipboard(context, "Tanoshi server URL", url)
                            onShowMessage(context.getString(R.string.server_url_copied))
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.server_copy_url),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        LifecycleButton(state = snapshot.state, onStart = onStart, onStop = onStop)

        OutlinedButton(
            onClick = { openExternalBrowser(context, url, onShowMessage) },
            enabled = running,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.OpenInBrowser,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.server_open_in_browser))
        }

        OutlinedButton(
            onClick = onOpenInApp,
            enabled = running,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Web,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.server_open_in_app))
        }

        if (snapshot.state == RunState.ERROR) {
            ErrorCard(lastError = snapshot.lastError, onViewLogs = onViewLogs)
        }

        if (lanMode) {
            LanCard(running = running, lanUrl = lanUrl, bindAddress = snapshot.bindAddress)
        }

        if (!running && !busy && snapshot.state != RunState.ERROR) {
            Text(
                text = stringResource(R.string.server_hint_stopped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun LifecycleButton(
    state: RunState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val buttonModifier = Modifier
        .fillMaxWidth()
        .height(58.dp)

    when (state) {
        RunState.STOPPED, RunState.ERROR -> Button(
            onClick = onStart,
            shape = shape,
            modifier = buttonModifier,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.server_start),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        RunState.STARTING -> Button(
            onClick = {},
            enabled = false,
            shape = shape,
            modifier = buttonModifier,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.server_starting),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        RunState.STOPPING -> Button(
            onClick = {},
            enabled = false,
            shape = shape,
            modifier = buttonModifier,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.server_stopping),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        RunState.RUNNING -> Button(
            onClick = onStop,
            shape = shape,
            modifier = buttonModifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.server_stop),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ErrorCard(
    lastError: String?,
    onViewLogs: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.server_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = lastError?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.server_error_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(
                onClick = onViewLogs,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(R.string.server_view_logs))
            }
        }
    }
}

@Composable
private fun LanCard(
    running: Boolean,
    lanUrl: String?,
    bindAddress: String,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.server_lan_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = stringResource(R.string.server_lan_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (running && lanUrl != null) {
                Text(
                    text = stringResource(R.string.server_lan_url, lanUrl),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                Text(
                    text = bindAddress,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** The loopback URL the bundled server listens on. */
internal fun serverUrl(port: Int): String = "http://127.0.0.1:$port"

internal fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
}

private fun openExternalBrowser(
    context: Context,
    url: String,
    onShowMessage: (String) -> Unit,
) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { onShowMessage(context.getString(R.string.server_no_browser)) }
}

/**
 * Best-effort LAN IPv4 address so the user can type it on another device. No
 * permission is needed for interface enumeration; failures simply hide the hint.
 */
private fun findLanAddress(): String? = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter { networkInterface -> networkInterface.isUp && !networkInterface.isLoopback }
        .flatMap { networkInterface ->
            Collections.list(networkInterface.inetAddresses).asSequence()
        }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
        ?.hostAddress
}.getOrNull()
