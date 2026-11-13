package dev.spainclaro.tanoshi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spainclaro.tanoshi.R
import dev.spainclaro.tanoshi.server.LogLine
import dev.spainclaro.tanoshi.server.LogStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val clipboardTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Streaming server log. Output is whatever the native Tanoshi process prints on
 * stdout/stderr plus a few SYSTEM markers added by the service; the UI colours
 * stderr and never adds data of its own.
 */
@Composable
fun LogsScreen(
    logs: List<LogLine>,
    onClearLogs: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var followOutput by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(logs.size, followOutput) {
        if (followOutput && logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.logs_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.logs_line_count,
                        logs.size,
                        logs.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    val clipboardText = logs.joinToString(separator = "\n") { line ->
                        formatForClipboard(line)
                    }
                    copyToClipboard(context, "Tanoshi logs", clipboardText)
                    val count = logs.size
                    onShowMessage(
                        context.resources.getQuantityString(R.plurals.logs_copied, count, count),
                    )
                },
                enabled = logs.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.logs_copy_all),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onClearLogs, enabled = logs.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.logs_clear),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.logs_follow),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = followOutput, onCheckedChange = { followOutput = it })
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Article,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(logs) { line -> LogRow(line) }
            }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val colors = MaterialTheme.colorScheme
    val label = when (line.stream) {
        LogStream.STDOUT -> "OUT"
        LogStream.STDERR -> "ERR"
        LogStream.SYSTEM -> "SYS"
    }
    val labelColor = when (line.stream) {
        LogStream.STDOUT -> colors.onSurfaceVariant
        LogStream.STDERR -> colors.error
        LogStream.SYSTEM -> colors.secondary
    }
    val textColor = when (line.stream) {
        LogStream.STDERR -> colors.error
        LogStream.SYSTEM -> colors.onSurfaceVariant
        LogStream.STDOUT -> colors.onSurface
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatLogTime(line.timestampMillis),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = labelColor,
            modifier = Modifier.width(30.dp),
        )
        Text(
            text = line.text.trimEnd(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = textColor,
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 2.dp),
        )
    }
}

private fun formatLogTime(timestampMillis: Long): String =
    Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(logTimeFormatter)

private fun formatForClipboard(line: LogLine): String {
    val stamp = Instant.ofEpochMilli(line.timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(clipboardTimeFormatter)
    val stream = when (line.stream) {
        LogStream.STDOUT -> "OUT"
        LogStream.STDERR -> "ERR"
        LogStream.SYSTEM -> "SYS"
    }
    return "$stamp [$stream] ${line.text}"
}
