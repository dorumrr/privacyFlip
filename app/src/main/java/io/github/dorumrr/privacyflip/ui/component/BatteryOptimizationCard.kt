package io.github.dorumrr.privacyflip.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.dorumrr.privacyflip.util.BatteryOptimizationManager

@Composable
fun BatteryOptimizationCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val batteryManager = remember { BatteryOptimizationManager(context) }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            CardHeader(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Battery Optimization Warning",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                },
                title = "Battery Optimization",
                subtitle = "Action required for reliable background operation"
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "⚠️ PrivacyFlip may be killed by battery optimization. Please whitelist the app for reliable background operation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    try {
                        val intent = batteryManager.createBatteryOptimizationIntent()
                        if (intent != null) {
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        try {
                            val settingsIntent = batteryManager.createBatteryOptimizationSettingsIntent()
                            if (settingsIntent != null) {
                                context.startActivity(settingsIntent)
                            }
                        } catch (e2: Exception) {
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Whitelist App")
            }
        }
    }
}
