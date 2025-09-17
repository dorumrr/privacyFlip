package io.github.dorumrr.privacyflip.ui.screen



import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.util.Log
import io.github.dorumrr.privacyflip.ui.component.BatteryOptimizationCard
import io.github.dorumrr.privacyflip.util.BatteryOptimizationManager
import io.github.dorumrr.privacyflip.ui.component.CreditsFooter
import io.github.dorumrr.privacyflip.ui.component.GlobalPrivacyStatusCard
import io.github.dorumrr.privacyflip.ui.component.PermissionStatusCard
import io.github.dorumrr.privacyflip.ui.component.RootStatusCard
import io.github.dorumrr.privacyflip.ui.component.ScreenLockConfigCard
import io.github.dorumrr.privacyflip.ui.component.ServiceSettingsCard
import io.github.dorumrr.privacyflip.ui.component.TimerSettingsCard
import io.github.dorumrr.privacyflip.ui.screen.LogViewerScreen
import io.github.dorumrr.privacyflip.ui.viewmodel.MainViewModel

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermissions: (Array<String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var shouldShowBatteryOptimizationCard by remember { mutableStateOf(false) }

    val batteryManager = remember { BatteryOptimizationManager(context) }

    val updateCardVisibility = remember {
        {
            shouldShowBatteryOptimizationCard = batteryManager.isBatteryOptimizationSupported() &&
                !batteryManager.isIgnoringBatteryOptimizations()

            Log.d("MainScreen", "Card visibility updated - Battery: $shouldShowBatteryOptimizationCard")
        }
    }

    LaunchedEffect(Unit) {
        updateCardVisibility()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updateCardVisibility()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (uiState.showLogViewer) {
        LogViewerScreen(
            logs = uiState.logs,
            logFileSizeKB = uiState.logFileSizeKB,
            onBack = { viewModel.hideLogViewer() },
            onClearLogs = { viewModel.clearLogs() }
        )
        return
    }
    val timerSettings by viewModel.timerSettings.collectAsState()

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        
        uiState.errorMessage?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }
        }
        
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            RootStatusCard(
                isRootAvailable = uiState.isRootAvailable,
                isRootGranted = uiState.isRootGranted,
                onRetryRoot = { viewModel.requestRootPermission() }
            )
        }

        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        if (uiState.isRootGranted) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
            ) {
                item {
                    GlobalPrivacyStatusCard(
                        isGloballyEnabled = uiState.isGlobalPrivacyEnabled,
                        onToggleGlobalPrivacy = { enabled -> viewModel.toggleGlobalPrivacy(enabled) }
                    )
                }

                Log.d("MainScreen", "Checking permission card: ungrantedPermissions.size = ${uiState.ungrantedPermissions.size}")
                if (uiState.ungrantedPermissions.isNotEmpty()) {
                    Log.d("MainScreen", "Showing permission card for ${uiState.ungrantedPermissions.size} permissions")
                    item {
                        PermissionStatusCard(
                            ungrantedPermissions = uiState.ungrantedPermissions,
                            onRequestPermissions = onRequestPermissions,
                            isExpandable = true
                        )
                    }
                } else {
                    Log.d("MainScreen", "No ungranted permissions - not showing permission card")
                }

                item {
                    ScreenLockConfigCard(
                        wifiDisableOnLock = uiState.screenLockConfig.wifiDisableOnLock,
                        wifiEnableOnUnlock = uiState.screenLockConfig.wifiEnableOnUnlock,
                        bluetoothDisableOnLock = uiState.screenLockConfig.bluetoothDisableOnLock,
                        bluetoothEnableOnUnlock = uiState.screenLockConfig.bluetoothEnableOnUnlock,
                        mobileDataDisableOnLock = uiState.screenLockConfig.mobileDataDisableOnLock,
                        mobileDataEnableOnUnlock = uiState.screenLockConfig.mobileDataEnableOnUnlock,
                        locationDisableOnLock = uiState.screenLockConfig.locationDisableOnLock,
                        locationEnableOnUnlock = uiState.screenLockConfig.locationEnableOnUnlock,
                        onConfigChanged = { feature, disableOnLock, enableOnUnlock ->
                            viewModel.updateScreenLockConfig(feature, disableOnLock, enableOnUnlock)
                        }
                    )
                }

                item {
                    TimerSettingsCard(
                        timerSettings = timerSettings,
                        onTimerSettingsChanged = { viewModel.updateTimerSettings(it) }
                    )
                }

                if (shouldShowBatteryOptimizationCard) {
                    item {
                        BatteryOptimizationCard()
                    }
                }

                item {
                    ServiceSettingsCard(
                        backgroundServiceEnabled = uiState.backgroundServiceEnabled,
                        onBackgroundServiceToggle = { viewModel.toggleBackgroundService(it) }
                    )
                }
                
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { viewModel.executePanicMode() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("🚨 Panic Mode - Disable All")
                    }
                }


                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    CreditsFooter(
                        onViewLogs = { viewModel.showLogViewer() },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        }

        if (!uiState.isRootGranted) {
            CreditsFooter(
                onViewLogs = { viewModel.showLogViewer() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            )
        }
    }
}
