package io.github.dorumrr.privacyflip.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.dorumrr.privacyflip.data.PrivacyFeature

@Composable
fun ScreenLockConfigCard(
    wifiDisableOnLock: Boolean,
    wifiEnableOnUnlock: Boolean,
    bluetoothDisableOnLock: Boolean,
    bluetoothEnableOnUnlock: Boolean,
    mobileDataDisableOnLock: Boolean,
    mobileDataEnableOnUnlock: Boolean,
    locationDisableOnLock: Boolean,
    locationEnableOnUnlock: Boolean,
    onConfigChanged: (PrivacyFeature, Boolean, Boolean) -> Unit,
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
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Screen Lock",
                        modifier = Modifier.size(20.dp)
                    )
                },
                title = "Screen Lock Actions",
                subtitle = "Configure privacy features for lock/unlock"
            )

            Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "When screen locks:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Switch(
                        checked = wifiDisableOnLock,
                        onCheckedChange = {
                            onConfigChanged(PrivacyFeature.WIFI, it, wifiEnableOnUnlock)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disable WiFi", modifier = Modifier.weight(1f))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Switch(
                        checked = bluetoothDisableOnLock,
                        onCheckedChange = {
                            onConfigChanged(PrivacyFeature.BLUETOOTH, it, bluetoothEnableOnUnlock)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disable Bluetooth", modifier = Modifier.weight(1f))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Switch(
                        checked = mobileDataDisableOnLock,
                        onCheckedChange = {
                            onConfigChanged(PrivacyFeature.MOBILE_DATA, it, mobileDataEnableOnUnlock)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disable Mobile Data", modifier = Modifier.weight(1f))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Switch(
                        checked = locationDisableOnLock,
                        onCheckedChange = {
                            onConfigChanged(PrivacyFeature.LOCATION, it, locationEnableOnUnlock)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disable Location", modifier = Modifier.weight(1f))
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "When screen unlocks:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Switch(
                        checked = wifiEnableOnUnlock,
                        onCheckedChange = {
                            onConfigChanged(PrivacyFeature.WIFI, wifiDisableOnLock, it)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable WiFi", modifier = Modifier.weight(1f))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Switch(
                        checked = bluetoothEnableOnUnlock,
                        onCheckedChange = {
                            onConfigChanged(PrivacyFeature.BLUETOOTH, bluetoothDisableOnLock, it)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Bluetooth", modifier = Modifier.weight(1f))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Switch(
                        checked = mobileDataEnableOnUnlock,
                        onCheckedChange = {
                            onConfigChanged(PrivacyFeature.MOBILE_DATA, mobileDataDisableOnLock, it)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Mobile Data", modifier = Modifier.weight(1f))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Switch(
                        checked = locationEnableOnUnlock,
                        onCheckedChange = {
                            onConfigChanged(PrivacyFeature.LOCATION, locationDisableOnLock, it)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Location", modifier = Modifier.weight(1f))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "💡 Tip: WiFi and Bluetooth are commonly disabled on lock for privacy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
        }
    }
}
