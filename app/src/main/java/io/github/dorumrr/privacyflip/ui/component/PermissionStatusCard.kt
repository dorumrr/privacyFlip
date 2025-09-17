package io.github.dorumrr.privacyflip.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.dorumrr.privacyflip.permission.PermissionChecker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionStatusCard(
    ungrantedPermissions: List<PermissionChecker.PermissionStatus>,
    onRequestPermissions: (Array<String>) -> Unit,
    modifier: Modifier = Modifier,
    isExpandable: Boolean = false
) {
    if (ungrantedPermissions.isEmpty()) return

    var isExpanded by remember { mutableStateOf(!isExpandable) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { if (isExpandable) it.clickable { isExpanded = !isExpanded } else it },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Missing Permissions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )

                    Text(
                        text = "${ungrantedPermissions.size} permission${if (ungrantedPermissions.size > 1) "s" else ""} needed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }

                if (isExpandable) {
                    Text(
                        text = if (isExpanded) "▲" else "▼",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))

                ungrantedPermissions.forEach { permission ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "• ${permission.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )

                        if (permission.isRequired) {
                            Text(
                                text = "REQUIRED",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (permission.description.isNotEmpty()) {
                        Text(
                            text = "  ${permission.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val permissionsToRequest = ungrantedPermissions.map { it.permission }.toTypedArray()
                        onRequestPermissions(permissionsToRequest)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}



@Composable
fun ExpandablePermissionStatusCard(
    ungrantedPermissions: List<PermissionChecker.PermissionStatus>,
    onRequestPermissions: (Array<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    PermissionStatusCard(
        ungrantedPermissions = ungrantedPermissions,
        onRequestPermissions = onRequestPermissions,
        modifier = modifier,
        isExpandable = true
    )
}
