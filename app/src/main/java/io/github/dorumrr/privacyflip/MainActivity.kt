package io.github.dorumrr.privacyflip

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.dorumrr.privacyflip.service.PrivacyMonitorService
import io.github.dorumrr.privacyflip.ui.screen.MainScreen
import io.github.dorumrr.privacyflip.ui.theme.PrivacyFlipTheme
import io.github.dorumrr.privacyflip.ui.viewmodel.MainViewModel
import io.github.dorumrr.privacyflip.util.BatteryOptimizationManager

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("MainActivity", "Permission results: $permissions")
        viewModel.requestPermissions(permissions.keys.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initialize(this)
        
        setContent {
            PrivacyFlipTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsState()

                    LaunchedEffect(uiState.pendingPermissionRequest) {
                        uiState.pendingPermissionRequest?.let { permissions ->
                            Log.d("MainActivity", "Auto-requesting permissions: ${permissions.contentToString()}")
                            permissionLauncher.launch(permissions)
                            viewModel.clearPendingPermissionRequest()
                        }
                    }

                    LaunchedEffect(uiState.isRootGranted) {
                        if (!uiState.isRootGranted) {
                            Log.d("MainActivity", "Waiting for root access before battery optimization prompt")
                            return@LaunchedEffect
                        }

                        val batteryManager = BatteryOptimizationManager(this@MainActivity)

                        if (batteryManager.isBatteryOptimizationSupported() &&
                            !batteryManager.isIgnoringBatteryOptimizations()) {

                            kotlinx.coroutines.delay(1000)

                            Log.i("MainActivity", "Auto-prompting for battery optimization exemption (after root granted)")

                            try {
                                val intent = batteryManager.createBatteryOptimizationIntent()
                                if (intent != null) {
                                    startActivity(intent)
                                } else {
                                    val settingsIntent = batteryManager.createBatteryOptimizationSettingsIntent()
                                    if (settingsIntent != null) {
                                        startActivity(settingsIntent)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("MainActivity", "Failed to auto-prompt for battery optimization", e)
                            }
                        } else {
                            Log.d("MainActivity", "Battery optimization already granted or not supported")
                        }
                    }

                    MainScreen(
                        viewModel = viewModel,
                        onRequestPermissions = { permissions ->
                            permissionLauncher.launch(permissions)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.cleanup()
    }
}
