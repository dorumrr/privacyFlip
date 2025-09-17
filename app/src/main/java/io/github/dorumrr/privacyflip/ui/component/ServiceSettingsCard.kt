package io.github.dorumrr.privacyflip.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ServiceSettingsCard(
    backgroundServiceEnabled: Boolean,
    onBackgroundServiceToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            CardHeader(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Service Settings",
                        modifier = Modifier.size(20.dp)
                    )
                },
                title = "Service Settings",
                subtitle = "Background operation"
            )

            Spacer(modifier = Modifier.height(16.dp))

            SettingsRow(
                title = "Background Service",
                description = "Keep monitoring screen state when app is closed",
                checked = backgroundServiceEnabled,
                onCheckedChange = onBackgroundServiceToggle
            )
        }
    }
}
