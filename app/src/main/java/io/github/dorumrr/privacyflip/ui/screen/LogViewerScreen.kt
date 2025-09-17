package io.github.dorumrr.privacyflip.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dorumrr.privacyflip.util.Constants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    logs: String,
    logFileSizeKB: Int,
    onBack: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    var showClearDialog by remember { mutableStateOf(false) }
    var showCopySnackbar by remember { mutableStateOf(false) }
    
    LaunchedEffect(logs) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(showCopySnackbar) {
        if (showCopySnackbar) {
            kotlinx.coroutines.delay(Constants.UI.COPY_SNACKBAR_DURATION_MS)
            showCopySnackbar = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(Constants.UI.LOG_VIEWER_TITLE)
                        Text(
                            text = Constants.UI.LOG_SIZE_FORMAT.format(logFileSizeKB),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = Constants.UI.BACK_BUTTON
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = Constants.UI.CLEAR_LOGS_TITLE
                        )
                    }
                }
            )

            if (logs.isEmpty() || logs.isBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = Constants.UI.NO_LOGS_TITLE,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = Constants.UI.NO_LOGS_MESSAGE,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val logLines = logs.trim().lines().reversed()
                    
                    items(logLines.size) { index ->
                        val line = logLines[index]
                        if (line.isNotBlank()) {
                            LogEntry(
                                logLine = line,
                                isNewest = index == 0
                            )
                        }
                    }
                }
            }
        }

        if (logs.isNotEmpty() && logs.isNotBlank()) {
            FloatingActionButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(logs))
                    showCopySnackbar = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = Constants.UI.COPY_LOGS_BUTTON,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        if (showCopySnackbar) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface
                )
            ) {
                Text(
                    text = Constants.UI.LOGS_COPIED_MESSAGE,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(Constants.UI.CLEAR_LOGS_TITLE) },
                text = { Text(Constants.UI.CLEAR_LOGS_MESSAGE) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onClearLogs()
                            showClearDialog = false
                        }
                    ) {
                        Text(Constants.UI.CLEAR_BUTTON)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(Constants.UI.CANCEL_BUTTON)
                    }
                }
            )
        }
    }
}

@Composable
fun LogEntry(
    logLine: String,
    isNewest: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isNewest) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    
    val textColor = when {
        logLine.contains(" E/") -> MaterialTheme.colorScheme.error
        logLine.contains(" W/") -> MaterialTheme.colorScheme.tertiary
        logLine.contains(" I/") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Text(
            text = logLine,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = textColor,
            lineHeight = 14.sp
        )
    }
}
