package dev.spainclaro.tanoshi.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.QrCode
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.spainclaro.tanoshi.R
import dev.spainclaro.tanoshi.server.RunState
import dev.spainclaro.tanoshi.server.ServerSnapshot
import dev.spainclaro.tanoshi.ui.components.ServerStatusCard
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main dashboard: lifecycle card, URL, start/stop action, the shortcuts into the
 * external or embedded browser and a "share on your network" QR code when the
 * server is exposed on the LAN. All intents are guarded so a device without a
 * browser cannot crash the app.
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
    val lanActive = snapshot.bindAddress == "0.0.0.0"
    val lanModeEnabled = exposeOnLan || lanActive

    // Wi-Fi/Ethernet can appear or disappear while this screen is visible, so the
    // address is re-resolved off the main thread every time the screen resumes.
    var lanIpAddress by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    lanIpAddress = withContext(Dispatchers.IO) {
                        findLanIpv4Address(context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Honest gating: a scan target is only offered when the server really is
    // reachable over the LAN on a discovered IPv4 address.
    val shareUrl = if (lanModeEnabled && lanActive && running) {
        lanIpAddress?.let { address -> "http://$address:${snapshot.port}" }
    } else {
        null
    }

    // QR encoding is CPU work; produceState caches the bitmap per URL.
    val qrCodeState by produceState<QrCodeState>(initialValue = QrCodeState.Idle, shareUrl) {
        value = if (shareUrl == null) {
            QrCodeState.Idle
        } else {
            value = QrCodeState.Generating
            val image = withContext(Dispatchers.Default) { generateQrCode(shareUrl) }
            if (image != null) QrCodeState.Ready(image) else QrCodeState.Failed
        }
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

        ShareOnNetworkCard(
            lanModeEnabled = lanModeEnabled,
            lanActive = lanActive,
            running = running,
            port = snapshot.port,
            lanIpAddress = lanIpAddress,
            shareUrl = shareUrl,
            qrCodeState = qrCodeState,
            onShowMessage = onShowMessage,
        )

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

/**
 * LAN sharing card. Shows a scannable QR code plus the host address only when
 * the server is RUNNING on 0.0.0.0 and a LAN IPv4 address was found; otherwise
 * it explains what is missing instead of showing a code that would not work.
 */
@Composable
private fun ShareOnNetworkCard(
    lanModeEnabled: Boolean,
    lanActive: Boolean,
    running: Boolean,
    port: Int,
    lanIpAddress: String?,
    shareUrl: String?,
    qrCodeState: QrCodeState,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val shareable = shareUrl != null && lanIpAddress != null
    val guidance = when {
        !lanModeEnabled -> stringResource(R.string.server_share_enable_lan)
        !running -> stringResource(R.string.server_share_start_server)
        !lanActive -> stringResource(R.string.server_share_restart_server)
        else -> stringResource(R.string.server_share_no_address)
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.QrCode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.server_share_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (!shareable) {
                Text(
                    text = guidance,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.server_share_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when (qrCodeState) {
                    is QrCodeState.Ready -> Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(232.dp)
                                .background(Color.White, RoundedCornerShape(16.dp))
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = qrCodeState.image,
                                contentDescription = stringResource(
                                    R.string.server_share_qr_description,
                                ),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }

                    QrCodeState.Failed -> Text(
                        text = stringResource(R.string.server_share_qr_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    QrCodeState.Idle, QrCodeState.Generating -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.server_share_generating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ShareAddressBlock(
                    lanIpAddress = lanIpAddress,
                    port = port,
                    shareUrl = shareUrl,
                    onCopy = {
                        copyToClipboard(context, "Tanoshi network URL", shareUrl)
                        onShowMessage(context.getString(R.string.server_share_copied))
                    },
                )

                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.server_share_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareAddressBlock(
    lanIpAddress: String?,
    port: Int,
    shareUrl: String?,
    onCopy: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.server_share_address_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lanIpAddress != null) {
                Text(
                    text = "$lanIpAddress:$port",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (shareUrl != null) {
                Text(
                    text = shareUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.server_share_copy),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Loading state of the QR code for the current LAN URL. */
private sealed interface QrCodeState {
    data object Idle : QrCodeState
    data object Generating : QrCodeState
    data class Ready(val image: ImageBitmap) : QrCodeState
    data object Failed : QrCodeState
}

private const val QR_CODE_SIZE_PX = 512

/**
 * Encodes [content] as a black-on-white QR bitmap (never theme tinted, so it
 * scans in every app theme). Must be called off the main thread.
 */
private fun generateQrCode(content: String): ImageBitmap? = try {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        QR_CODE_SIZE_PX,
        QR_CODE_SIZE_PX,
        hints,
    )
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            pixels[rowOffset + x] = if (matrix.get(x, y)) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    bitmap.asImageBitmap()
} catch (throwable: Throwable) {
    // QRCodeWriter throws WriterException for unencodable content and Bitmap can
    // throw for extreme sizes; both simply hide the QR and keep the address text.
    null
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
 * Best-effort LAN IPv4 address for sharing: first prefer the active network when
 * [ConnectivityManager] reports it as Wi-Fi or Ethernet (via [LinkProperties]),
 * then fall back to enumerating network interfaces. Loopback and link-local
 * (169.254.x.x) addresses are never returned.
 */
private fun findLanIpv4Address(context: Context): String? {
    val fromConnectivity = runCatching { findActiveWifiOrEthernetIpv4(context) }.getOrNull()
    if (fromConnectivity != null) return fromConnectivity
    return runCatching {
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
}

private fun findActiveWifiOrEthernetIpv4(context: Context): String? {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager ?: return null
    val activeNetwork = connectivityManager.activeNetwork ?: return null
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
    val isWifiOrEthernet =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    if (!isWifiOrEthernet) return null
    val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null
    return linkProperties.linkAddresses.asSequence()
        .map { linkAddress -> linkAddress.address }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { candidate ->
            !candidate.isLoopbackAddress && !candidate.isLinkLocalAddress
        }
        ?.hostAddress
}
