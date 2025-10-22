package io.github.dorumrr.privacyflip

import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import io.github.dorumrr.privacyflip.databinding.ActivityMainBinding
import io.github.dorumrr.privacyflip.ui.viewmodel.MainViewModel
import io.github.dorumrr.privacyflip.util.BatteryOptimizationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("MainActivity", "Permission results: $permissions")
        viewModel.requestPermissions(permissions.keys.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable edge-to-edge display (draw behind status bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Handle window insets to respect safe areas (status bar, navigation bar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply padding to the root view to avoid content going behind system bars
            view.setPadding(
                insets.left,
                insets.top,
                insets.right,
                insets.bottom
            )

            WindowInsetsCompat.CONSUMED
        }

        // No ActionBar setup needed for NoActionBar theme

        viewModel.initialize(this)

        // Single observer for all UI state changes
        var hasPromptedBatteryOptimization = false

        viewModel.uiState.observe(this) { uiState ->
            // Handle permission requests
            uiState.pendingPermissionRequest?.let { permissions ->
                Log.d("MainActivity", "Auto-requesting permissions: ${permissions.contentToString()}")
                permissionLauncher.launch(permissions)
                viewModel.clearPendingPermissionRequest()
            }

            // Handle battery optimization after root is granted (only once)
            if (uiState.isRootGranted && !hasPromptedBatteryOptimization) {
                hasPromptedBatteryOptimization = true

                lifecycleScope.launch {
                    val batteryManager = BatteryOptimizationManager(this@MainActivity)

                    if (batteryManager.isBatteryOptimizationSupported() &&
                        !batteryManager.isIgnoringBatteryOptimizations()) {

                        delay(1000)

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
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
