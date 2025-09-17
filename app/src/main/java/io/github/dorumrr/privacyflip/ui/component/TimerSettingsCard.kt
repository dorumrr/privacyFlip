package io.github.dorumrr.privacyflip.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.dorumrr.privacyflip.data.TimerSettings

@Composable
fun TimerSettingsCard(
    timerSettings: TimerSettings,
    onTimerSettingsChanged: (TimerSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var lockDelayText by remember(timerSettings.lockDelaySeconds) {
        mutableStateOf(timerSettings.lockDelaySeconds.toString())
    }
    var unlockDelayText by remember(timerSettings.unlockDelaySeconds) {
        mutableStateOf(timerSettings.unlockDelaySeconds.toString())
    }
    
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Timer Settings",
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Action Delays",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = {
                        if (isEditing) {
                            val lockDelay = lockDelayText.toIntOrNull()
                            val unlockDelay = unlockDelayText.toIntOrNull()

                            if (lockDelay != null && lockDelay in 0..300 &&
                                unlockDelay != null && unlockDelay in 0..60) {
                                onTimerSettingsChanged(
                                    timerSettings.copy(
                                        lockDelaySeconds = lockDelay,
                                        unlockDelaySeconds = unlockDelay
                                    )
                                )
                            }
                        }
                        isEditing = !isEditing
                    }
                ) {
                    Text(if (isEditing) "Done" else "Edit")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Configure delays before privacy actions are triggered",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isEditing) {
                OutlinedTextField(
                    value = lockDelayText,
                    onValueChange = { newValue ->
                        lockDelayText = newValue
                    },
                    label = { Text("Lock Delay (seconds)") },
                    supportingText = { Text("0-300 seconds (0 = immediate)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = lockDelayText.toIntOrNull()?.let { it !in 0..300 } ?: true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = unlockDelayText,
                    onValueChange = { newValue ->
                        unlockDelayText = newValue
                    },
                    label = { Text("Unlock Delay (seconds)") },
                    supportingText = { Text("0-60 seconds (0 = immediate)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = unlockDelayText.toIntOrNull()?.let { it !in 0..60 } ?: true
                )

            } else {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lock Delay",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${timerSettings.lockDelaySeconds} seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Unlock Delay",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${timerSettings.unlockDelaySeconds} seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
