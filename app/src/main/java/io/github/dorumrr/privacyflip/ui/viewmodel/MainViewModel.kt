package io.github.dorumrr.privacyflip.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
import kotlinx.coroutines.delay
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

    // Helper methods to consolidate duplicate error handling patterns
    private inline fun handleError(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            logManager.e(TAG, "Error $operation: ${e.message}")
        }
    }

    private inline fun handleErrorWithUiUpdate(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            logManager.e(TAG, "Error $operation: ${e.message}")
            updateUiState { it.copy(errorMessage = "Error $operation: ${e.message}") }
        }
    }

    private val rootManager = RootManager.getInstance(Unit)
    private lateinit var privacyManager: PrivacyManager

    private lateinit var permissionChecker: PermissionChecker
    private lateinit var logManager: LogManager
    private lateinit var preferenceManager: PreferenceManager
    private var context: Context? = null
    private var isInitialized = false
    
    private val _uiState = MutableLiveData(UiState())
    val uiState: LiveData<UiState> = _uiState
    
    private val _privacyConfig = MutableStateFlow(PrivacyConfig())
    val privacyConfig: StateFlow<PrivacyConfig> = _privacyConfig.asStateFlow()
    
    fun initialize(context: Context) {
        if (isInitialized) {
            logManager.w(TAG, "ViewModel already initialized, skipping duplicate initialization")
            return
        }

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

        startBackgroundServiceIfEnabled()

        checkRootStatus()

        loadPermissionStatus()

        isInitialized = true
        logManager.i(TAG, "ViewModel initialized successfully")
    }
    
    // Helper function to safely update UI state
    private fun updateUiState(update: (UiState) -> UiState) {
        val currentState = _uiState.value ?: UiState()
        _uiState.value = update(currentState)
    }

    private fun checkRootStatus() {
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }

            try {
                val isRootAvailable = rootManager.isRootAvailable()
                val currentState = _uiState.value ?: UiState()

                val isRootGranted = if (isRootAvailable) {
                    val alreadyGranted = rootManager.isRootGranted()
                    if (!alreadyGranted && !currentState.hasTriedAutoRootRequest) {
                        val granted = rootManager.requestRootPermission()
                        updateUiState { it.copy(hasTriedAutoRootRequest = true) }
                        granted
                    } else {
                        alreadyGranted
                    }
                } else {
                    false
                }

                updateUiState {
                    it.copy(
                        isRootAvailable = isRootAvailable,
                        isRootGranted = isRootGranted,
                        isLoading = false,
                        errorMessage = if (!isRootAvailable) "Root access not available" else null
                    )
                }

                if (isRootGranted) {
                    loadPrivacyStatus()
                    loadPermissionStatus()
                }
            } catch (e: Exception) {
                updateUiState {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error checking root status: ${e.message}"
                    )
                }
            }
        }
    }
    

    
    private fun loadPrivacyStatus() {
        viewModelScope.launch {
            try {
                val status = privacyManager.getPrivacyStatus()
                val currentStates = privacyManager.getCurrentStatus()

                updateUiState {
                    it.copy(
                        privacyStatus = status,
                        featureStates = currentStates
                    )
                }
            } catch (e: Exception) {
                updateUiState {
                    it.copy(errorMessage = "Error loading privacy status: ${e.message}")
                }
            }
        }
    }
    
    fun updatePrivacyConfig(config: PrivacyConfig) {
        _privacyConfig.value = config
    }
    
    fun updateTimerSettings(settings: TimerSettings) {
        if (settings.isValid()) {
            // Update UiState for UI binding
            updateUiState { it.copy(timerSettings = settings) }

            viewModelScope.launch {
                try {
                    preferenceManager.updateTimerSettings(settings)
                    logManager.d(TAG, "Timer settings updated: lock=${settings.lockDelaySeconds}s, unlock=${settings.unlockDelaySeconds}s")
                } catch (e: Exception) {
                    logManager.e(TAG, "Failed to save timer settings: ${e.message}")
                }
            }
        } else {
            logManager.w(TAG, "Invalid timer settings: lock=${settings.lockDelaySeconds}s, unlock=${settings.unlockDelaySeconds}s")
        }
    }

    private fun loadTimerSettings() {
        handleError("loading timer settings") {
            val settings = TimerSettings(
                lockDelaySeconds = preferenceManager.lockDelaySeconds,
                unlockDelaySeconds = preferenceManager.unlockDelaySeconds,
                showCountdown = preferenceManager.showCountdown
            )

            // Update UiState for UI binding
            updateUiState { it.copy(timerSettings = settings) }

            logConfigLoaded("Timer settings", "lock=${preferenceManager.lockDelaySeconds}s, unlock=${preferenceManager.unlockDelaySeconds}s")
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
    


    fun clearError() {
        updateUiState { it.copy(errorMessage = null) }
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
                    val currentState = _uiState.value ?: UiState()

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
                    updateUiState {
                        it.copy(
                            ungrantedPermissions = emptyList(),
                            pendingPermissionRequest = null,
                            hasTriedAutoRequest = false
                        )
                    }
                }
                val currentState = _uiState.value ?: UiState()
                logManager.d(TAG, "UI State updated - ungrantedPermissions.size = ${currentState.ungrantedPermissions.size}")
            } catch (e: Exception) {
                logManager.e(TAG, "Error loading permission status: ${e.message}")
            }
        }
    }

    fun requestPermissions(@Suppress("UNUSED_PARAMETER") permissions: Array<String>) {
        updateUiState { it.copy(hasTriedAutoRequest = true) }
        loadPermissionStatus()
    }

    fun clearPendingPermissionRequest() {
        updateUiState { it.copy(pendingPermissionRequest = null) }
    }

    fun updateScreenLockConfig(feature: PrivacyFeature, disableOnLock: Boolean, enableOnUnlock: Boolean) {
        viewModelScope.launch {
            try {
                preferenceManager.updateScreenLockConfig(feature, disableOnLock, enableOnUnlock)

                val currentState = _uiState.value ?: UiState()
                val currentConfig = currentState.screenLockConfig
                val newConfig = currentConfig.updateFeature(feature, disableOnLock, enableOnUnlock)

                updateUiState { it.copy(screenLockConfig = newConfig) }

                logManager.d(TAG, "Screen lock config updated for $feature: disable=$disableOnLock, enable=$enableOnUnlock")

            } catch (e: Exception) {
                logManager.e(TAG, "Failed to update screen lock config for $feature: ${e.message}")
            }
        }
    }

    fun requestRootPermission() {
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }

            try {
                val isRootGranted = rootManager.forceRootPermissionRequest()

                updateUiState {
                    it.copy(
                        isRootGranted = isRootGranted,
                        isLoading = false,
                        errorMessage = if (!isRootGranted) "Root permission denied or failed. If this persists, uninstall the app and install it again ensuring you grant root access when prompted." else null
                    )
                }

                if (isRootGranted) {
                    loadPrivacyStatus()
                    loadPermissionStatus()
                }
            } catch (e: Exception) {
                logManager.e(TAG, "Error requesting root permission: ${e.message}")
                updateUiState {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error requesting root permission: ${e.message}"
                    )
                }
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
        updateUiState { it.copy(isGlobalPrivacyEnabled = isEnabled) }
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

            updateUiState { it.copy(screenLockConfig = config) }

            logConfigLoaded("Screen lock config", config.toString())
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to load screen lock config: ${e.message}")
        }
    }

    private fun loadServiceSettings() {
        handleError("loading service settings") {
            // Always ensure background service is enabled
            preferenceManager.backgroundServiceEnabled = true
            val isServiceRunning = isBackgroundServiceRunning()
            updateUiState {
                it.copy(
                    backgroundServiceEnabled = true,
                    backgroundServicePermissionGranted = isServiceRunning
                )
            }

            logConfigLoaded("Service settings", "backgroundService=true (always enabled), running=$isServiceRunning")
        }
    }

    private fun isBackgroundServiceRunning(): Boolean {
        return try {
            // Use the service's own running state tracker
            PrivacyMonitorService.isRunning()
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to check if background service is running: ${e.message}")
            false
        }
    }

    private fun startBackgroundServiceIfEnabled() {
        try {
            ensureBackgroundServiceRunning()
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to ensure background service running: ${e.message}")
        }
    }

    private fun scheduleServiceHealthCheck(context: Context) {
        handleError("scheduling service health check") {
            // Cancel any existing health check workers first to prevent duplicates
            WorkManager.getInstance(context).cancelAllWorkByTag("ServiceHealthWorker")

            val healthCheckRequest = PeriodicWorkRequestBuilder<ServiceHealthWorker>(15, TimeUnit.MINUTES)
                .addTag("ServiceHealthWorker")
                .build()

            WorkManager.getInstance(context).enqueue(healthCheckRequest)
            logManager.d(TAG, "Service health check scheduled (previous workers cancelled)")
        }
    }

    fun toggleGlobalPrivacy(enabled: Boolean) {
        preferenceManager.isGlobalPrivacyEnabled = enabled

        updateUiState { it.copy(isGlobalPrivacyEnabled = enabled) }

        if (!enabled) {
            viewModelScope.launch {
                try {
                    val allFeatures = PrivacyFeature.getConnectivityFeatures().toSet()
                    privacyManager.enableFeatures(allFeatures)

                    loadPrivacyStatus()

                } catch (e: Exception) {
                    updateUiState {
                        it.copy(errorMessage = "Error disabling global privacy: ${e.message}")
                    }
                }
            }
        }
    }

    private fun ensureBackgroundServiceRunning() {
        val context = this.context ?: return

        // Always ensure background service is enabled and running
        preferenceManager.backgroundServiceEnabled = true

        PrivacyMonitorService.start(context)
        scheduleServiceHealthCheck(context)
        logManager.i(TAG, "Background service ensured running with health checks")

        // Check if service is actually running after start attempt
        viewModelScope.launch {
            delay(1000) // Give service time to start
            val isRunning = isBackgroundServiceRunning()
            updateUiState {
                it.copy(
                    backgroundServiceEnabled = true,
                    backgroundServicePermissionGranted = isRunning
                )
            }
        }

        logManager.d(TAG, "Background service ensured running")
    }





    // Helper methods for traditional views - DRY implementation
    private fun updateFeatureSettings(
        feature: PrivacyFeature,
        disableOnLock: Boolean? = null,
        enableOnUnlock: Boolean? = null,
        getCurrentDisableOnLock: (ScreenLockConfig) -> Boolean,
        getCurrentEnableOnUnlock: (ScreenLockConfig) -> Boolean
    ) {
        val currentState = _uiState.value ?: UiState()
        val currentConfig = currentState.screenLockConfig
        val newDisableOnLock = disableOnLock ?: getCurrentDisableOnLock(currentConfig)
        val newEnableOnUnlock = enableOnUnlock ?: getCurrentEnableOnUnlock(currentConfig)
        updateScreenLockConfig(feature, newDisableOnLock, newEnableOnUnlock)
    }

    fun updateWifiSettings(disableOnLock: Boolean? = null, enableOnUnlock: Boolean? = null) {
        updateFeatureSettings(PrivacyFeature.WIFI, disableOnLock, enableOnUnlock,
            { it.wifiDisableOnLock }, { it.wifiEnableOnUnlock })
    }

    fun updateBluetoothSettings(disableOnLock: Boolean? = null, enableOnUnlock: Boolean? = null) {
        updateFeatureSettings(PrivacyFeature.BLUETOOTH, disableOnLock, enableOnUnlock,
            { it.bluetoothDisableOnLock }, { it.bluetoothEnableOnUnlock })
    }

    fun updateMobileDataSettings(disableOnLock: Boolean? = null, enableOnUnlock: Boolean? = null) {
        updateFeatureSettings(PrivacyFeature.MOBILE_DATA, disableOnLock, enableOnUnlock,
            { it.mobileDataDisableOnLock }, { it.mobileDataEnableOnUnlock })
    }

    fun updateLocationSettings(disableOnLock: Boolean? = null, enableOnUnlock: Boolean? = null) {
        updateFeatureSettings(PrivacyFeature.LOCATION, disableOnLock, enableOnUnlock,
            { it.locationDisableOnLock }, { it.locationEnableOnUnlock })
    }

    fun updateNFCSettings(disableOnLock: Boolean? = null, enableOnUnlock: Boolean? = null) {
        updateFeatureSettings(PrivacyFeature.NFC, disableOnLock, enableOnUnlock,
            { it.nfcDisableOnLock }, { it.nfcEnableOnUnlock })
    }

    // Generic method for updating any feature setting
    fun updateFeatureSetting(feature: PrivacyFeature, disableOnLock: Boolean? = null, enableOnUnlock: Boolean? = null) {
        when (feature) {
            PrivacyFeature.WIFI -> updateWifiSettings(disableOnLock, enableOnUnlock)
            PrivacyFeature.BLUETOOTH -> updateBluetoothSettings(disableOnLock, enableOnUnlock)
            PrivacyFeature.MOBILE_DATA -> updateMobileDataSettings(disableOnLock, enableOnUnlock)
            PrivacyFeature.LOCATION -> updateLocationSettings(disableOnLock, enableOnUnlock)
            PrivacyFeature.NFC -> updateNFCSettings(disableOnLock, enableOnUnlock)
        }
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
    val locationEnableOnUnlock: Boolean = Constants.Defaults.LOCATION_ENABLE_ON_UNLOCK,
    val nfcDisableOnLock: Boolean = Constants.Defaults.NFC_DISABLE_ON_LOCK,
    val nfcEnableOnUnlock: Boolean = Constants.Defaults.NFC_ENABLE_ON_UNLOCK
) {
    fun updateFeature(feature: PrivacyFeature, disableOnLock: Boolean, enableOnUnlock: Boolean): ScreenLockConfig {
        return when (feature) {
            PrivacyFeature.WIFI -> copy(
                wifiDisableOnLock = disableOnLock,
                wifiEnableOnUnlock = enableOnUnlock
            )
            PrivacyFeature.BLUETOOTH -> copy(
                bluetoothDisableOnLock = disableOnLock,
                bluetoothEnableOnUnlock = enableOnUnlock
            )
            PrivacyFeature.MOBILE_DATA -> copy(
                mobileDataDisableOnLock = disableOnLock,
                mobileDataEnableOnUnlock = enableOnUnlock
            )
            PrivacyFeature.LOCATION -> copy(
                locationDisableOnLock = disableOnLock,
                locationEnableOnUnlock = enableOnUnlock
            )
            PrivacyFeature.NFC -> copy(
                nfcDisableOnLock = disableOnLock,
                nfcEnableOnUnlock = enableOnUnlock
            )
        }
    }
}

data class UiState(
    val isLoading: Boolean = false,
    val isRootAvailable: Boolean = false,
    val isRootGranted: Boolean = false,
    val featureStates: Map<PrivacyFeature, FeatureState> = emptyMap(),
    val privacyStatus: PrivacyStatus = PrivacyStatus(),
    val isGlobalPrivacyEnabled: Boolean = true,
    val ungrantedPermissions: List<PermissionChecker.PermissionStatus> = emptyList(),
    val pendingPermissionRequest: Array<String>? = null,
    val hasTriedAutoRequest: Boolean = false,
    val hasTriedAutoRootRequest: Boolean = false,
    val screenLockConfig: ScreenLockConfig = ScreenLockConfig(),
    val backgroundServiceEnabled: Boolean = Constants.Defaults.BACKGROUND_SERVICE_ENABLED,
    val backgroundServicePermissionGranted: Boolean = false,
    val errorMessage: String? = null,
    // Timer settings for UI binding
    val timerSettings: TimerSettings = TimerSettings(
        lockDelaySeconds = Constants.Defaults.LOCK_DELAY_SECONDS,
        unlockDelaySeconds = Constants.Defaults.UNLOCK_DELAY_SECONDS,
        showCountdown = Constants.Defaults.SHOW_COUNTDOWN
    )
)