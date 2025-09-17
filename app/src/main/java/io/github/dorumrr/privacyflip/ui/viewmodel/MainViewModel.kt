package io.github.dorumrr.privacyflip.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.privacyflip.data.*

import io.github.dorumrr.privacyflip.permission.PermissionChecker
import io.github.dorumrr.privacyflip.privacy.PrivacyManager
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.service.PrivacyMonitorService
import io.github.dorumrr.privacyflip.util.Constants
import io.github.dorumrr.privacyflip.util.LogManager
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.worker.ServiceHealthWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private fun logConfigLoaded(configType: String, details: String) {
        logManager.d(TAG, "$configType loaded: $details")
    }

    private val rootManager = RootManager.getInstance()
    private lateinit var privacyManager: PrivacyManager

    private lateinit var permissionChecker: PermissionChecker
    private lateinit var logManager: LogManager
    private lateinit var preferenceManager: PreferenceManager
    private var context: Context? = null
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val _privacyConfig = MutableStateFlow(PrivacyConfig())
    val privacyConfig: StateFlow<PrivacyConfig> = _privacyConfig.asStateFlow()
    
    private val _timerSettings = MutableStateFlow(TimerSettings())
    val timerSettings: StateFlow<TimerSettings> = _timerSettings.asStateFlow()
    
    fun initialize(context: Context) {
        this.context = context
        rootManager.initialize(context)
        privacyManager = PrivacyManager.getInstance(context)

        permissionChecker = PermissionChecker(context)
        logManager = LogManager.getInstance(context)
        preferenceManager = PreferenceManager.getInstance(context)

        loadTimerSettings()

        loadGlobalPrivacyStatus()

        loadScreenLockConfig(context)

        loadServiceSettings()

        startBackgroundServiceIfEnabled(context)

        checkRootStatus()

        loadPermissionStatus()
    }
    
    private fun checkRootStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val isRootAvailable = rootManager.isRootAvailable()
                val currentState = _uiState.value

                val isRootGranted = if (isRootAvailable) {
                    val alreadyGranted = rootManager.isRootGranted()
                    if (!alreadyGranted && !currentState.hasTriedAutoRootRequest) {
                        val granted = rootManager.requestRootPermission()
                        _uiState.value = currentState.copy(hasTriedAutoRootRequest = true)
                        granted
                    } else {
                        alreadyGranted
                    }
                } else {
                    false
                }

                _uiState.value = _uiState.value.copy(
                    isRootAvailable = isRootAvailable,
                    isRootGranted = isRootGranted,
                    isLoading = false,
                    errorMessage = if (!isRootAvailable) "Root access not available" else null
                )

                if (isRootGranted) {
                    loadPrivacyStatus()
                    loadPermissionStatus()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Error checking root status: ${e.message}"
                )
            }
        }
    }
    

    
    private fun loadPrivacyStatus() {
        viewModelScope.launch {
            try {
                val status = privacyManager.getPrivacyStatus()
                val currentStates = privacyManager.getCurrentStatus()
                
                _uiState.value = _uiState.value.copy(
                    privacyStatus = status,
                    featureStates = currentStates
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Error loading privacy status: ${e.message}"
                )
            }
        }
    }
    
    fun updatePrivacyConfig(config: PrivacyConfig) {
        _privacyConfig.value = config
    }
    
    fun updateTimerSettings(settings: TimerSettings) {
        if (settings.isValid()) {
            _timerSettings.value = settings

            viewModelScope.launch {
                try {
                    preferenceManager.updateTimerSettings(settings)
                } catch (e: Exception) {
                    logManager.e(TAG, "Failed to save timer settings: ${e.message}")
                }
            }
        }
    }

    private fun loadTimerSettings() {
        try {
            _timerSettings.value = TimerSettings(
                lockDelaySeconds = preferenceManager.lockDelaySeconds,
                unlockDelaySeconds = preferenceManager.unlockDelaySeconds,
                showCountdown = preferenceManager.showCountdown
            )

            logConfigLoaded("Timer settings", "lock=${preferenceManager.lockDelaySeconds}s, unlock=${preferenceManager.unlockDelaySeconds}s")
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to load timer settings: ${e.message}")
        }
    }

    fun toggleLockFeature(feature: PrivacyFeature, enabled: Boolean) {
        val currentConfig = _privacyConfig.value
        val newLockFeatures = if (enabled) {
            currentConfig.lockFeatures + feature
        } else {
            currentConfig.lockFeatures - feature
        }
        
        _privacyConfig.value = currentConfig.copy(lockFeatures = newLockFeatures)
    }
    
    fun toggleUnlockFeature(feature: PrivacyFeature, enabled: Boolean) {
        val currentConfig = _privacyConfig.value
        val newUnlockFeatures = if (enabled) {
            currentConfig.unlockFeatures + feature
        } else {
            currentConfig.unlockFeatures - feature
        }
        
        _privacyConfig.value = currentConfig.copy(unlockFeatures = newUnlockFeatures)
    }
    
    fun executePanicMode() {
        if (!_uiState.value.isGlobalPrivacyEnabled) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Enable global privacy protection first to use panic mode"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                privacyManager.executePanicMode()

                _uiState.value = _uiState.value.copy(
                    isLoading = false
                )

                loadPrivacyStatus()

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Error executing panic mode: ${e.message}"
                )
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
    
    private fun loadPermissionStatus() {
        viewModelScope.launch {
            try {
                logManager.d(TAG, "Loading permission status...")
                val ungrantedPermissions = permissionChecker.getUngrantedPermissions()
                logManager.d(TAG, "Found ${ungrantedPermissions.size} ungranted permissions")
                ungrantedPermissions.forEach { perm ->
                    logManager.d(TAG, "Ungranted permission: ${perm.displayName} (${perm.permission})")
                }

                if (ungrantedPermissions.isNotEmpty()) {
                    val currentState = _uiState.value

                    if (!currentState.hasTriedAutoRequest) {
                        logManager.d(TAG, "Auto-requesting ${ungrantedPermissions.size} missing permissions...")
                        val permissionsToRequest = ungrantedPermissions.map { it.permission }.toTypedArray()
                        _uiState.value = currentState.copy(
                            ungrantedPermissions = emptyList(),
                            pendingPermissionRequest = permissionsToRequest,
                            hasTriedAutoRequest = true
                        )
                    } else {
                        logManager.d(TAG, "User declined permissions, showing red warning card")
                        _uiState.value = currentState.copy(
                            ungrantedPermissions = ungrantedPermissions,
                            pendingPermissionRequest = null
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        ungrantedPermissions = emptyList(),
                        pendingPermissionRequest = null,
                        hasTriedAutoRequest = false
                    )
                }
                logManager.d(TAG, "UI State updated - ungrantedPermissions.size = ${_uiState.value.ungrantedPermissions.size}")
            } catch (e: Exception) {
                logManager.e(TAG, "Error loading permission status: ${e.message}")
            }
        }
    }

    fun requestPermissions(@Suppress("UNUSED_PARAMETER") permissions: Array<String>) {
        _uiState.value = _uiState.value.copy(hasTriedAutoRequest = true)
        loadPermissionStatus()
    }

    fun clearPendingPermissionRequest() {
        _uiState.value = _uiState.value.copy(pendingPermissionRequest = null)
    }

    fun updateScreenLockConfig(feature: PrivacyFeature, disableOnLock: Boolean, enableOnUnlock: Boolean) {
        viewModelScope.launch {
            try {
                preferenceManager.updateScreenLockConfig(feature, disableOnLock, enableOnUnlock)

                val currentConfig = _uiState.value.screenLockConfig
                val newConfig = when (feature) {
                    PrivacyFeature.WIFI -> currentConfig.copy(
                        wifiDisableOnLock = disableOnLock,
                        wifiEnableOnUnlock = enableOnUnlock
                    )
                    PrivacyFeature.BLUETOOTH -> currentConfig.copy(
                        bluetoothDisableOnLock = disableOnLock,
                        bluetoothEnableOnUnlock = enableOnUnlock
                    )
                    PrivacyFeature.MOBILE_DATA -> currentConfig.copy(
                        mobileDataDisableOnLock = disableOnLock,
                        mobileDataEnableOnUnlock = enableOnUnlock
                    )
                    PrivacyFeature.LOCATION -> currentConfig.copy(
                        locationDisableOnLock = disableOnLock,
                        locationEnableOnUnlock = enableOnUnlock
                    )
                }

                _uiState.value = _uiState.value.copy(screenLockConfig = newConfig)

                logManager.d(TAG, "Screen lock config updated for $feature: disable=$disableOnLock, enable=$enableOnUnlock")

            } catch (e: Exception) {
                logManager.e(TAG, "Failed to update screen lock config for $feature: ${e.message}")
            }
        }
    }

    fun requestRootPermission() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val isRootGranted = rootManager.forceRootPermissionRequest()

                _uiState.value = _uiState.value.copy(
                    isRootGranted = isRootGranted,
                    isLoading = false,
                    errorMessage = if (!isRootGranted) "Root permission denied or failed" else null
                )

                if (isRootGranted) {
                    loadPrivacyStatus()

                    loadPermissionStatus()
                }
            } catch (e: Exception) {
                logManager.e(TAG, "Error requesting root permission: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Error requesting root permission: ${e.message}"
                )
            }
        }
    }

    fun refresh() {
        checkRootStatus()
        loadPrivacyStatus()
        loadPermissionStatus()
    }
    
    private fun loadGlobalPrivacyStatus() {
        val isEnabled = preferenceManager.isGlobalPrivacyEnabled
        _uiState.value = _uiState.value.copy(isGlobalPrivacyEnabled = isEnabled)
    }

    private fun loadScreenLockConfig(context: Context) {
        try {
            val prefs = context.getSharedPreferences(Constants.Preferences.PRIVACY_SWITCH_PREFS, Context.MODE_PRIVATE)

            val config = ScreenLockConfig(
                wifiDisableOnLock = prefs.getBoolean(Constants.Preferences.getFeatureLockKey("WIFI"), Constants.Defaults.WIFI_DISABLE_ON_LOCK),
                wifiEnableOnUnlock = prefs.getBoolean(Constants.Preferences.getFeatureUnlockKey("WIFI"), Constants.Defaults.WIFI_ENABLE_ON_UNLOCK),
                bluetoothDisableOnLock = prefs.getBoolean(Constants.Preferences.getFeatureLockKey("BLUETOOTH"), Constants.Defaults.BLUETOOTH_DISABLE_ON_LOCK),
                bluetoothEnableOnUnlock = prefs.getBoolean(Constants.Preferences.getFeatureUnlockKey("BLUETOOTH"), Constants.Defaults.BLUETOOTH_ENABLE_ON_UNLOCK),
                mobileDataDisableOnLock = prefs.getBoolean(Constants.Preferences.getFeatureLockKey("MOBILE_DATA"), Constants.Defaults.MOBILE_DATA_DISABLE_ON_LOCK),
                mobileDataEnableOnUnlock = prefs.getBoolean(Constants.Preferences.getFeatureUnlockKey("MOBILE_DATA"), Constants.Defaults.MOBILE_DATA_ENABLE_ON_UNLOCK),
                locationDisableOnLock = prefs.getBoolean(Constants.Preferences.getFeatureLockKey("LOCATION"), Constants.Defaults.LOCATION_DISABLE_ON_LOCK),
                locationEnableOnUnlock = prefs.getBoolean(Constants.Preferences.getFeatureUnlockKey("LOCATION"), Constants.Defaults.LOCATION_ENABLE_ON_UNLOCK)
            )

            _uiState.value = _uiState.value.copy(screenLockConfig = config)

            logConfigLoaded("Screen lock config", config.toString())
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to load screen lock config: ${e.message}")
        }
    }

    private fun loadServiceSettings() {
        try {
            _uiState.value = _uiState.value.copy(
                backgroundServiceEnabled = preferenceManager.backgroundServiceEnabled
            )

            logConfigLoaded("Service settings", "backgroundService=${preferenceManager.backgroundServiceEnabled}")
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to load service settings: ${e.message}")
        }
    }

    private fun startBackgroundServiceIfEnabled(context: Context) {
        if (_uiState.value.backgroundServiceEnabled) {
            try {
                PrivacyMonitorService.start(context)
                logManager.i(TAG, "Background service started")

                scheduleServiceHealthCheck(context)

            } catch (e: Exception) {
                logManager.e(TAG, "Failed to start background service: ${e.message}")
            }
        }
    }

    private fun scheduleServiceHealthCheck(context: Context) {
        try {
            val healthCheckRequest = PeriodicWorkRequestBuilder<ServiceHealthWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueue(healthCheckRequest)
            logManager.d(TAG, "Service health check scheduled")
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to schedule service health check: ${e.message}")
        }
    }

    fun toggleGlobalPrivacy(enabled: Boolean) {
        preferenceManager.isGlobalPrivacyEnabled = enabled

        _uiState.value = _uiState.value.copy(isGlobalPrivacyEnabled = enabled)

        if (!enabled) {
            viewModelScope.launch {
                try {
                    val allFeatures = PrivacyFeature.getConnectivityFeatures().toSet()
                    privacyManager.enableFeatures(allFeatures)

                    loadPrivacyStatus()

                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Error disabling global privacy: ${e.message}"
                    )
                }
            }
        }
    }

    fun toggleBackgroundService(enabled: Boolean) {
        val context = this.context ?: return

        preferenceManager.backgroundServiceEnabled = enabled

        _uiState.value = _uiState.value.copy(backgroundServiceEnabled = enabled)

        if (enabled) {
            PrivacyMonitorService.start(context)
            scheduleServiceHealthCheck(context)
            logManager.i(TAG, "Background service started with health checks")
        } else {
            PrivacyMonitorService.stop(context)
            WorkManager.getInstance(context).cancelAllWorkByTag("ServiceHealthWorker")
            logManager.i(TAG, "Background service stopped and health checks cancelled")
        }

        logManager.d(TAG, "Background service toggled: $enabled")
    }

    fun showLogViewer() {
        viewModelScope.launch {
            try {
                val logs = logManager.getLogs()
                val sizeKB = logManager.getLogFileSizeKB()
                _uiState.value = _uiState.value.copy(
                    showLogViewer = true,
                    logs = logs,
                    logFileSizeKB = sizeKB
                )
            } catch (e: Exception) {
                logManager.e(TAG, "Error loading logs: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    showLogViewer = true,
                    logs = "Error loading logs: ${e.message}",
                    logFileSizeKB = 0
                )
            }
        }
    }

    fun hideLogViewer() {
        _uiState.value = _uiState.value.copy(showLogViewer = false)
    }

    fun clearLogs() {
        viewModelScope.launch {
            try {
                logManager.clearLogs()
                val logs = logManager.getLogs()
                val sizeKB = logManager.getLogFileSizeKB()
                _uiState.value = _uiState.value.copy(
                    logs = logs,
                    logFileSizeKB = sizeKB
                )
            } catch (e: Exception) {
                logManager.e(TAG, "Error clearing logs: ${e.message}")
            }
        }
    }

    fun cleanup() {
        logManager.cleanup()
    }
}

data class ScreenLockConfig(
    val wifiDisableOnLock: Boolean = Constants.Defaults.WIFI_DISABLE_ON_LOCK,
    val wifiEnableOnUnlock: Boolean = Constants.Defaults.WIFI_ENABLE_ON_UNLOCK,
    val bluetoothDisableOnLock: Boolean = Constants.Defaults.BLUETOOTH_DISABLE_ON_LOCK,
    val bluetoothEnableOnUnlock: Boolean = Constants.Defaults.BLUETOOTH_ENABLE_ON_UNLOCK,
    val mobileDataDisableOnLock: Boolean = Constants.Defaults.MOBILE_DATA_DISABLE_ON_LOCK,
    val mobileDataEnableOnUnlock: Boolean = Constants.Defaults.MOBILE_DATA_ENABLE_ON_UNLOCK,
    val locationDisableOnLock: Boolean = Constants.Defaults.LOCATION_DISABLE_ON_LOCK,
    val locationEnableOnUnlock: Boolean = Constants.Defaults.LOCATION_ENABLE_ON_UNLOCK
)

data class MainUiState(
    val isLoading: Boolean = false,
    val isRootAvailable: Boolean? = null,
    val isRootGranted: Boolean = false,
    val featureStates: Map<PrivacyFeature, FeatureState> = emptyMap(),
    val privacyStatus: PrivacyStatus = PrivacyStatus(),
    val isGlobalPrivacyEnabled: Boolean = true,
    val ungrantedPermissions: List<PermissionChecker.PermissionStatus> = emptyList(),
    val pendingPermissionRequest: Array<String>? = null,
    val hasTriedAutoRequest: Boolean = false,
    val hasTriedAutoRootRequest: Boolean = false,
    val showLogViewer: Boolean = false,
    val logs: String = "",
    val logFileSizeKB: Int = 0,
    val screenLockConfig: ScreenLockConfig = ScreenLockConfig(),
    val backgroundServiceEnabled: Boolean = Constants.Defaults.BACKGROUND_SERVICE_ENABLED,
    val errorMessage: String? = null
)
