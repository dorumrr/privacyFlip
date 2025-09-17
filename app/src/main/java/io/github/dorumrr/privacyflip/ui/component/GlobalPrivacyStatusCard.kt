package io.github.dorumrr.privacyflip.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.dorumrr.privacyflip.ui.theme.PrivacyActive

@Composable
fun GlobalPrivacyStatusCard(
    isGloballyEnabled: Boolean,
    onToggleGlobalPrivacy: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGloballyEnabled) {
                PrivacyActive
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Icon(
                imageVector = if (isGloballyEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = "Privacy Status",
                tint = if (isGloballyEnabled) {
                    Color.White
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "PrivacyFlip",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isGloballyEnabled) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )

                Text(
                    text = if (isGloballyEnabled) {
                        "Protection Active"
                    } else {
                        "Protection Disabled"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isGloballyEnabled) {
                        Color.White.copy(alpha = 0.9f)
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    }
                )
            }

            Switch(
                checked = isGloballyEnabled,
                onCheckedChange = onToggleGlobalPrivacy,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2E7D32),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.error
                )
            )
        }
    }
}
