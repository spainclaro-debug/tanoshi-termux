package dev.spainclaro.tanoshi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spainclaro.tanoshi.R
import dev.spainclaro.tanoshi.server.RunState
import dev.spainclaro.tanoshi.server.ServerSnapshot
import dev.spainclaro.tanoshi.ui.theme.TanoshiStatusColors
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Compact dashboard card: lifecycle badge, PID, port, bind address and a live
 * uptime counter. The ticker only runs while the server is RUNNING.
 */
@Composable
fun ServerStatusCard(
    snapshot: ServerSnapshot,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(snapshot.startedAtMillis) {
        if (snapshot.state != RunState.RUNNING || snapshot.startedAtMillis == null) {
            return@LaunchedEffect
        }
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val uptimeText = if (snapshot.state == RunState.RUNNING && snapshot.startedAtMillis != null) {
        formatUptime((nowMillis - snapshot.startedAtMillis).coerceAtLeast(0L))
    } else {
        stringResource(R.string.status_value_none)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(12.dp)
                        .background(snapshot.state.statusColor(), CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = snapshot.state.displayLabel(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.width(12.dp))
                if (snapshot.pid != null) {
                    Text(
                        text = "${stringResource(R.string.status_pid)} ${snapshot.pid}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = colors.outlineVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusMetric(
                    label = stringResource(R.string.status_port),
                    value = snapshot.port.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatusMetric(
                    label = stringResource(R.string.status_bind),
                    value = snapshot.bindAddress.ifBlank { stringResource(R.string.status_value_none) },
                    modifier = Modifier.weight(1f),
                )
                StatusMetric(
                    label = stringResource(R.string.status_uptime),
                    value = uptimeText,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatusMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun RunState.displayLabel(): String = stringResource(
    when (this) {
        RunState.STOPPED -> R.string.status_stopped
        RunState.STARTING -> R.string.status_starting
        RunState.RUNNING -> R.string.status_running
        RunState.STOPPING -> R.string.status_stopping
        RunState.ERROR -> R.string.status_error
    },
)

internal fun RunState.statusColor(): Color = when (this) {
    RunState.STOPPED -> TanoshiStatusColors.Stopped
    RunState.STARTING -> TanoshiStatusColors.Starting
    RunState.RUNNING -> TanoshiStatusColors.Running
    RunState.STOPPING -> TanoshiStatusColors.Stopping
    RunState.ERROR -> TanoshiStatusColors.Error
}

private fun formatUptime(millis: Long): String {
    val totalSeconds = millis / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds)
        minutes > 0L -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
        else -> String.format(Locale.US, "%ds", seconds)
    }
}
