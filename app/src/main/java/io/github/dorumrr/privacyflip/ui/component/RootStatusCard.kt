package io.github.dorumrr.privacyflip.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RootStatusCard(
    isRootAvailable: Boolean?,
    isRootGranted: Boolean,
    onRetryRoot: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isRootAvailable == null) {
        return
    }

    if (!isRootGranted || isRootAvailable == false) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            // Main row with icon, title, and button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        isRootGranted -> Icons.Default.CheckCircle
                        isRootAvailable == true -> Icons.Default.Warning
                        else -> Icons.Default.Settings
                    },
                    contentDescription = "Root Status",
                    tint = when {
                        isRootGranted -> MaterialTheme.colorScheme.primary
                        isRootAvailable == true -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = when {
                        isRootGranted -> "Root access granted"
                        isRootAvailable == true -> "Root permission needed"
                        else -> "Root access not available"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                if (isRootAvailable == true && !isRootGranted) {
                    TextButton(
                        onClick = onRetryRoot,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Grant")
                    }
                }
            }

            // Explanatory text below the main row
            if (isRootAvailable == true && !isRootGranted) {
                Column(
                    modifier = Modifier.padding(start = 36.dp, top = 4.dp)
                ) {
                    Text(
                        text = "You have 10 seconds to allow permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "If Grant button doesn't work, uninstall and reinstall the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isRootAvailable == false) {
                Text(
                    text = "This app requires a rooted device with Magisk or similar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp, top = 4.dp)
                )
            }
        }
    }
}
