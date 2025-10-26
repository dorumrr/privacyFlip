package io.github.dorumrr.privacyflip.ui.viewmodel

import android.app.KeyguardManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.privacyflip.data.*

import io.github.dorumrr.privacyflip.permission.PermissionChecker
import io.github.dorumrr.privacyflip.privacy.PrivacyManager
import io.github.dorumrr.privacyflip.privilege.PrivilegeMethod
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private inline fun handleError(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            logManager.e(TAG, "Error $operation: ${e.message}")
        }
    }

    private val rootManager = RootManager.getInstance(Unit)
    private lateinit var privacyManager: PrivacyManager
    private lateinit var permissionChecker: PermissionChecker
    private lateinit var logManager: LogManager
    private lateinit var preferenceManager: PreferenceManager
    private var context: Context? = null
    private var isInitialized = false
    private var globalPrivacyToggleJob: Job? = null

    private val _uiState = MutableLiveData(UiState())
    val uiState: LiveData<UiState> = _uiState
    
    private val _privacyConfig = MutableStateFlow(PrivacyConfig())
    val privacyConfig: StateFlow<PrivacyConfig> = _privacyConfig.asStateFlow()
    
    fun initialize(context: Context) {
        if (isInitialized) {
            return
        }

        this.context = context
        privacyManager = PrivacyManager.getInstance(context)
        permissionChecker = PermissionChecker(context)
        logManager = LogManager.getInstance(context)
        preferenceManager = PreferenceManager.getInstance(context)

        logManager.i(TAG, "=== MainViewModel INITIALIZATION START ===")
        logManager.i(TAG, "App version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")

        viewModelScope.launch {
            rootManager.initialize(context)
            checkRootStatus()
        }

        loadTimerSettings()
        loadGlobalPrivacyStatus()
        loadScreenLockConfig(context)
        loadServiceSettings()
        startBackgroundServiceIfEnabled()
        loadPermissionStatus()

        logManager.i(TAG, "Performing initial lock delay configuration check...")
        checkLockDelayConfiguration(context)

        isInitialized = true
        logManager.i(TAG, "=== MainViewModel INITIALIZATION COMPLETE ===")
    }

    override fun onCleared() {
        super.onCleared()
        globalPrivacyToggleJob?.cancel()
        globalPrivacyToggleJob = null
    }

    fun updateUiState(update: (UiState) -> UiState) {
        val currentState = _uiState.value ?: UiState()
        _uiState.value = update(currentState)
    }

    private fun checkRootStatus() {
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }

            try {
                val privilegeMethod = rootManager.getPrivilegeMethod()
                val isPrivilegeAvailable = rootManager.isRootAvailable()
                val currentState = _uiState.value ?: UiState()

                var didAttemptAutoRequest = false

                val isPrivilegeGranted = if (isPrivilegeAvailable) {
                    val alreadyGranted = rootManager.isRootGranted()
                    if (!alreadyGranted && !currentState.hasTriedAutoRootRequest) {
                        didAttemptAutoRequest = true
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
                        isRootAvailable = isPrivilegeAvailable,
                        isRootGranted = isPrivilegeGranted,
                        privilegeMethod = privilegeMethod,
                        privilegeMethodName = privilegeMethod.getDisplayName(),
                        privilegeMethodDescription = privilegeMethod.getDescription(),
                        isLoading = false,
                        errorMessage = if (!isPrivilegeAvailable) {
                            "Root or Shizuku required - Install Shizuku from Play Store (for non-rooted devices) or root your device with Magisk"
                        } else if (isPrivilegeAvailable && !isPrivilegeGranted) {
                            if (didAttemptAutoRequest) {
                                when (privilegeMethod) {
                                    PrivilegeMethod.SHIZUKU -> "Shizuku permission denied. Click 'Grant Shizuku Permission' button to try again."
                                    PrivilegeMethod.ROOT -> "Root permission denied. Uninstall and reinstall the app, then grant root access when prompted by Magisk."
                                    PrivilegeMethod.SUI -> "Sui permission denied. Click 'Grant Sui Permission' button to try again."
                                    PrivilegeMethod.NONE -> "No privilege method available. Please install Shizuku or root your device."
                                }
                            } else {
                                when (privilegeMethod) {
                                    PrivilegeMethod.SHIZUKU -> "Shizuku permission required. Click 'Grant Shizuku Permission' button below to request permission."
                                    PrivilegeMethod.ROOT -> "Root permission required. Click 'Grant Root Permission' button below to request permission."
                                    PrivilegeMethod.SUI -> "Sui permission required. Click 'Grant Sui Permission' button below to request permission."
                                    PrivilegeMethod.NONE -> "No privilege method available. Please install Shizuku or root your device."
                                }
                            }
                        } else null
                    )
                }

                if (isPrivilegeGranted) {
                    loadPrivacyStatus()
                    loadPermissionStatus()
                }
            } catch (e: Exception) {
                updateUiState {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error checking privilege status: ${e.message}"
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
            updateUiState { it.copy(timerSettings = settings) }
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
                val ungrantedPermissions = permissionChecker.getUngrantedPermissions()

                if (ungrantedPermissions.isNotEmpty()) {
                    val currentState = _uiState.value ?: UiState()

                    if (!currentState.hasTriedAutoRequest) {
                        val permissionsToRequest = ungrantedPermissions.map { it.permission }.toTypedArray()
                        _uiState.value = currentState.copy(
                            ungrantedPermissions = emptyList(),
                            pendingPermissionRequest = permissionsToRequest,
                            hasTriedAutoRequest = true
                        )
                    } else {
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
                logManager.i(TAG, "Updating screen lock config: feature=$feature, disableOnLock=$disableOnLock, enableOnUnlock=$enableOnUnlock")

                preferenceManager.updateScreenLockConfig(feature, disableOnLock, enableOnUnlock)

                val currentState = _uiState.value ?: UiState()
                val currentConfig = currentState.screenLockConfig
                val newConfig = currentConfig.updateFeature(feature, disableOnLock, enableOnUnlock)

                updateUiState { it.copy(screenLockConfig = newConfig) }

                logManager.i(TAG, "✅ Screen lock config updated successfully for $feature")

                // Re-check lock delay warning if camera or microphone settings changed
                // This ensures the warning appears/disappears dynamically when user toggles switches
                if (feature == PrivacyFeature.CAMERA || feature == PrivacyFeature.MICROPHONE) {
                    logManager.i(TAG, "Sensor feature ($feature) changed - triggering lock delay configuration check")
                    context?.let { checkLockDelayConfiguration(it) }
                } else {
                    logManager.d(TAG, "Non-sensor feature changed - no lock delay check needed")
                }

            } catch (e: Exception) {
                logManager.e(TAG, "Failed to update screen lock config for $feature: ${e.message}")
            }
        }
    }

    fun requestRootPermission() {
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }

            try {
                val privilegeMethod = rootManager.getPrivilegeMethod()
                val isRootGranted = rootManager.forceRootPermissionRequest()

                val errorMsg = if (!isRootGranted) {
                    when (privilegeMethod) {
                        PrivilegeMethod.SHIZUKU ->
                            "Shizuku permission denied. Please ensure Shizuku app is running and wireless debugging is enabled. Try again or restart Shizuku."
                        PrivilegeMethod.ROOT ->
                            "Root permission denied. Uninstall and reinstall the app, then grant root access when prompted by Magisk/SuperSU."
                        PrivilegeMethod.SUI ->
                            "Sui permission denied. Please grant permission when prompted. If this persists, check Sui module settings in Magisk."
                        else ->
                            "Permission denied or failed. Please try again."
                    }
                } else null

                updateUiState {
                    it.copy(
                        isRootGranted = isRootGranted,
                        isLoading = false,
                        errorMessage = errorMsg
                    )
                }

                if (isRootGranted) {
                    loadPrivacyStatus()
                    loadPermissionStatus()
                }
            } catch (e: Exception) {
                logManager.e(TAG, "Error requesting permission: ${e.message}")
                updateUiState {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error requesting permission: ${e.message}"
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
    
    /**
     * Reloads screen lock configuration from SharedPreferences.
     * This should be called when the app comes back from background to ensure
     * the UI reflects any changes made by the worker (e.g., after lock/unlock).
     */
    fun reloadScreenLockConfig() {
        context?.let { ctx ->
            logManager.d(TAG, "Reloading screen lock config from preferences (triggered by onResume)")
            loadScreenLockConfig(ctx)
            logManager.d(TAG, "Screen lock config reloaded successfully")

            // Re-check lock delay warning in case camera/mic settings changed
            val currentConfig = _uiState.value?.screenLockConfig
            val cameraOrMicEnabled = currentConfig?.cameraDisableOnLock == true ||
                                    currentConfig?.microphoneDisableOnLock == true
            if (cameraOrMicEnabled) {
                logManager.d(TAG, "Camera or mic is enabled - re-checking lock delay configuration")
                checkLockDelayConfiguration(ctx)
            }
        }
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
                locationEnableOnUnlock = prefs.getBoolean(Constants.Preferences.getFeatureUnlockKey("LOCATION"), Constants.Defaults.LOCATION_ENABLE_ON_UNLOCK),
                nfcDisableOnLock = prefs.getBoolean(Constants.Preferences.getFeatureLockKey("NFC"), Constants.Defaults.NFC_DISABLE_ON_LOCK),
                nfcEnableOnUnlock = prefs.getBoolean(Constants.Preferences.getFeatureUnlockKey("NFC"), Constants.Defaults.NFC_ENABLE_ON_UNLOCK),
                cameraDisableOnLock = prefs.getBoolean(Constants.Preferences.getFeatureLockKey("CAMERA"), Constants.Defaults.CAMERA_DISABLE_ON_LOCK),
                cameraEnableOnUnlock = prefs.getBoolean(Constants.Preferences.getFeatureUnlockKey("CAMERA"), Constants.Defaults.CAMERA_ENABLE_ON_UNLOCK),
                microphoneDisableOnLock = prefs.getBoolean(Constants.Preferences.getFeatureLockKey("MICROPHONE"), Constants.Defaults.MICROPHONE_DISABLE_ON_LOCK),
                microphoneEnableOnUnlock = prefs.getBoolean(Constants.Preferences.getFeatureUnlockKey("MICROPHONE"), Constants.Defaults.MICROPHONE_ENABLE_ON_UNLOCK)
            )

            updateUiState { it.copy(screenLockConfig = config) }
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to load screen lock config: ${e.message}")
        }
    }

    private fun loadServiceSettings() {
        handleError("loading service settings") {
            preferenceManager.backgroundServiceEnabled = true
            val isServiceRunning = isBackgroundServiceRunning()
            updateUiState {
                it.copy(
                    backgroundServiceEnabled = true,
                    backgroundServicePermissionGranted = isServiceRunning
                )
            }
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

    /**
     * Check if the device's lock delay configuration allows camera/mic to be disabled on lock.
     * Returns true if sensors can be disabled, false if user needs to configure lock delay.
     *
     * This method implements comprehensive detection across all Android versions and manufacturers.
     */
    private fun canDisableSensorsOnLock(context: Context): Boolean {
        return try {
            // Log device information for debugging
            logManager.i(TAG, "=== LOCK DELAY DETECTION START ===")
            logManager.i(TAG, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            logManager.i(TAG, "Android Version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            logManager.i(TAG, "Build ID: ${android.os.Build.ID}")

            // Check if camera/mic sensor privacy is supported (Android 12+)
            val isSensorPrivacySupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            logManager.i(TAG, "Sensor privacy API supported: $isSensorPrivacySupported")

            if (!isSensorPrivacySupported) {
                logManager.w(TAG, "Sensor privacy not supported on API ${android.os.Build.VERSION.SDK_INT} - warning not applicable")
                return true // No warning needed on older Android versions
            }

            // Check if "Power button instantly locks" is enabled
            val powerButtonInstantlyLocks = checkPowerButtonInstantlyLocks(context)
            logManager.i(TAG, "Power button instantly locks: $powerButtonInstantlyLocks")

            if (powerButtonInstantlyLocks) {
                logManager.w(TAG, "⚠️ Power button instantly locks is ENABLED - sensors CANNOT be disabled on lock")
                logManager.w(TAG, "Reason: Keyguard engages immediately when power button is pressed")
                return false
            }

            // Check lock timeout setting with multiple fallback methods
            val lockTimeout = detectLockTimeout(context)
            logManager.i(TAG, "Lock timeout detected: ${lockTimeout}ms (${lockTimeout / 1000.0}s)")

            // Analyze the timeout value
            val canDisable = analyzeLockTimeout(lockTimeout)

            logManager.i(TAG, "=== LOCK DELAY DETECTION RESULT ===")
            logManager.i(TAG, "Can disable sensors on lock: $canDisable")
            if (canDisable) {
                logManager.i(TAG, "✅ Sufficient timing window exists to disable sensors before keyguard locks")
            } else {
                logManager.w(TAG, "❌ Insufficient timing window - sensors cannot be disabled reliably")
                logManager.w(TAG, "User needs to configure lock delay to 5+ seconds")
            }
            logManager.i(TAG, "=== LOCK DELAY DETECTION END ===")

            canDisable

        } catch (e: Exception) {
            logManager.e(TAG, "❌ CRITICAL ERROR in lock delay detection: ${e.message}")
            logManager.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            // Default to false (show warning) if we can't determine - safer approach
            logManager.w(TAG, "Defaulting to showing warning due to detection error")
            false
        }
    }

    /**
     * Check if "Power button instantly locks" setting is enabled.
     * Tries multiple setting keys for different manufacturers.
     */
    private fun checkPowerButtonInstantlyLocks(context: Context): Boolean {
        val settingKeys = listOf(
            "lockscreen.power_button_instantly_locks",  // Standard Android
            "power_button_instantly_locks",              // Some manufacturers
            "lockscreen_power_button_instantly_locks"    // Alternative format
        )

        for (key in settingKeys) {
            try {
                // Try Settings.System first (most common)
                val value = Settings.System.getInt(context.contentResolver, key, -1)
                if (value != -1) {
                    logManager.d(TAG, "Found power button setting in Settings.System: $key = $value")
                    return value == 1
                }
            } catch (e: Exception) {
                logManager.d(TAG, "Could not read Settings.System.$key: ${e.message}")
            }

            try {
                // Try Settings.Secure as fallback
                val value = Settings.Secure.getInt(context.contentResolver, key, -1)
                if (value != -1) {
                    logManager.d(TAG, "Found power button setting in Settings.Secure: $key = $value")
                    return value == 1
                }
            } catch (e: Exception) {
                logManager.d(TAG, "Could not read Settings.Secure.$key: ${e.message}")
            }
        }

        logManager.d(TAG, "Power button instantly locks setting not found - assuming disabled (safe default)")
        return false // Safe default: assume it's disabled if we can't find it
    }

    /**
     * Detect lock timeout with multiple fallback methods for different manufacturers.
     * Returns timeout in milliseconds.
     */
    private fun detectLockTimeout(context: Context): Long {
        logManager.d(TAG, "=== ATTEMPTING TO DETECT LOCK TIMEOUT ===")

        val settingKeys = listOf(
            "lock_screen_lock_after_timeout",      // Standard Android (Settings.Secure)
            "lockscreen.lock_after_timeout",       // Alternative format
            "lock_after_timeout",                  // Short format
            "screen_lock_timeout"                  // Some manufacturers
        )

        // Try Settings.Secure first (standard location)
        logManager.d(TAG, "Trying Settings.Secure...")
        for (key in settingKeys) {
            try {
                val value = Settings.Secure.getLong(context.contentResolver, key, -1)
                logManager.d(TAG, "  Settings.Secure.$key = $value")
                if (value != -1L) {
                    logManager.i(TAG, "✅ Found lock timeout in Settings.Secure: $key = ${value}ms")
                    return value
                }
            } catch (e: Exception) {
                logManager.d(TAG, "  Settings.Secure.$key threw exception: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Try Settings.System as fallback (some manufacturers)
        logManager.d(TAG, "Trying Settings.System...")
        for (key in settingKeys) {
            try {
                val value = Settings.System.getLong(context.contentResolver, key, -1)
                logManager.d(TAG, "  Settings.System.$key = $value")
                if (value != -1L) {
                    logManager.i(TAG, "✅ Found lock timeout in Settings.System: $key = ${value}ms")
                    return value
                }
            } catch (e: Exception) {
                logManager.d(TAG, "  Settings.System.$key threw exception: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Try to read screen off timeout as a hint
        try {
            val screenOffTimeout = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                -1
            )
            logManager.d(TAG, "Screen off timeout: ${screenOffTimeout}ms (for reference)")
        } catch (e: Exception) {
            logManager.d(TAG, "Could not read screen off timeout: ${e.message}")
        }

        // If we can't find the setting, we CANNOT determine the actual timeout
        // This is common on emulators and some devices
        logManager.w(TAG, "❌ Could not detect lock timeout setting from any known location")
        logManager.w(TAG, "This may indicate:")
        logManager.w(TAG, "  - Android emulator (settings not exposed)")
        logManager.w(TAG, "  - Custom ROM with different setting keys")
        logManager.w(TAG, "  - Android version with changed settings location")
        logManager.w(TAG, "  - Manufacturer-specific implementation")

        // IMPORTANT: We cannot assume a safe default here
        // Return -1 to indicate "unknown" so caller can handle appropriately
        logManager.w(TAG, "Returning -1 to indicate UNKNOWN lock timeout")
        return -1L
    }

    /**
     * Analyze lock timeout value and determine if sensors can be disabled.
     *
     * @param timeoutMs Lock timeout in milliseconds (-1 = unknown)
     * @return true if there's sufficient time to disable sensors, false otherwise
     */
    private fun analyzeLockTimeout(timeoutMs: Long): Boolean {
        when {
            timeoutMs == -1L -> {
                logManager.w(TAG, "⚠️ Lock timeout is UNKNOWN - cannot determine if sensors can be disabled")
                logManager.w(TAG, "This typically happens on:")
                logManager.w(TAG, "  - Android emulators")
                logManager.w(TAG, "  - Devices where settings are not exposed via standard API")
                logManager.w(TAG, "  - Custom ROMs with non-standard settings")
                logManager.w(TAG, "DECISION: Showing warning to be safe (better safe than sorry)")
                logManager.w(TAG, "User can test if camera/mic actually work on lock and ignore warning if they do")
                return false // Show warning when we can't determine
            }
            timeoutMs == 0L -> {
                logManager.w(TAG, "Lock timeout is 0ms (Immediately) - NO timing window")
                return false
            }
            timeoutMs < 5000L -> {
                logManager.w(TAG, "Lock timeout is ${timeoutMs}ms (< 5s) - timing window TOO SHORT")
                logManager.w(TAG, "Sensor disable commands may not complete before keyguard locks")
                return false
            }
            timeoutMs == 5000L -> {
                logManager.i(TAG, "Lock timeout is 5000ms (5s) - MINIMUM acceptable timing window")
                return true
            }
            timeoutMs < 30000L -> {
                logManager.i(TAG, "Lock timeout is ${timeoutMs}ms (${timeoutMs / 1000}s) - GOOD timing window")
                return true
            }
            else -> {
                logManager.i(TAG, "Lock timeout is ${timeoutMs}ms (${timeoutMs / 1000}s) - EXCELLENT timing window")
                return true
            }
        }
    }

    /**
     * Check lock delay configuration and update UI state to show/hide warning.
     * This is the main entry point for lock delay warning logic.
     */
    fun checkLockDelayConfiguration(context: Context) {
        handleError("checking lock delay configuration") {
            logManager.i(TAG, "=== LOCK DELAY WARNING CHECK START ===")

            // Check if device configuration allows sensor disabling
            val canDisableSensors = canDisableSensorsOnLock(context)
            val shouldShowWarning = !canDisableSensors

            logManager.i(TAG, "Device configuration check: canDisableSensors=$canDisableSensors")

            // Only show warning if camera or microphone disable-on-lock is enabled
            val currentConfig = _uiState.value?.screenLockConfig
            val cameraEnabled = currentConfig?.cameraDisableOnLock == true
            val micEnabled = currentConfig?.microphoneDisableOnLock == true
            val isCameraOrMicEnabled = cameraEnabled || micEnabled

            logManager.i(TAG, "User settings: camera_disable_on_lock=$cameraEnabled, mic_disable_on_lock=$micEnabled")
            logManager.i(TAG, "At least one sensor feature enabled: $isCameraOrMicEnabled")

            // Final decision: show warning only if BOTH conditions are true
            val finalShowWarning = shouldShowWarning && isCameraOrMicEnabled

            logManager.i(TAG, "=== LOCK DELAY WARNING DECISION ===")
            logManager.i(TAG, "Show warning: $finalShowWarning")
            if (finalShowWarning) {
                logManager.w(TAG, "⚠️ WARNING WILL BE DISPLAYED TO USER")
                logManager.w(TAG, "Reason: Device configuration prevents reliable sensor disabling on lock")
                logManager.w(TAG, "Action required: User must configure lock delay settings")
            } else {
                logManager.i(TAG, "✅ No warning needed")
                if (!isCameraOrMicEnabled) {
                    logManager.i(TAG, "Reason: Camera/Mic disable-on-lock not enabled by user")
                } else {
                    logManager.i(TAG, "Reason: Device configuration allows sensor disabling")
                }
            }
            logManager.i(TAG, "=== LOCK DELAY WARNING CHECK END ===")

            updateUiState { it.copy(showLockDelayWarning = finalShowWarning) }
        }
    }

    private fun scheduleServiceHealthCheck(context: Context) {
        handleError("scheduling service health check") {
            WorkManager.getInstance(context).cancelAllWorkByTag("ServiceHealthWorker")

            val healthCheckRequest = PeriodicWorkRequestBuilder<ServiceHealthWorker>(15, TimeUnit.MINUTES)
                .addTag("ServiceHealthWorker")
                .build()

            WorkManager.getInstance(context).enqueue(healthCheckRequest)
        }
    }

    fun toggleGlobalPrivacy(enabled: Boolean) {
        globalPrivacyToggleJob?.cancel()
        globalPrivacyToggleJob = null

        preferenceManager.isGlobalPrivacyEnabled = enabled
        updateUiState { it.copy(isGlobalPrivacyEnabled = enabled) }

        if (!enabled) {
            globalPrivacyToggleJob = viewModelScope.launch {
                try {
                    // Recheck preference in case user toggled back while coroutine was starting
                    if (!preferenceManager.isGlobalPrivacyEnabled) {
                        val allFeatures = PrivacyFeature.getConnectivityFeatures().toSet()
                        privacyManager.enableFeatures(allFeatures)
                        loadPrivacyStatus()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateUiState {
                        it.copy(errorMessage = "Error disabling global privacy: ${e.message}")
                    }
                } finally {
                    globalPrivacyToggleJob = null
                }
            }
        }
    }

    private fun ensureBackgroundServiceRunning() {
        val context = this.context ?: return

        preferenceManager.backgroundServiceEnabled = true
        PrivacyMonitorService.start(context)
        scheduleServiceHealthCheck(context)

        viewModelScope.launch {
            delay(1000)
            val isRunning = isBackgroundServiceRunning()
            updateUiState {
                it.copy(
                    backgroundServiceEnabled = true,
                    backgroundServicePermissionGranted = isRunning
                )
            }
        }
    }





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

    fun updateCameraSettings(disableOnLock: Boolean? = null, enableOnUnlock: Boolean? = null) {
        updateFeatureSettings(PrivacyFeature.CAMERA, disableOnLock, enableOnUnlock,
            { it.cameraDisableOnLock }, { it.cameraEnableOnUnlock })
    }

    fun updateMicrophoneSettings(disableOnLock: Boolean? = null, enableOnUnlock: Boolean? = null) {
        updateFeatureSettings(PrivacyFeature.MICROPHONE, disableOnLock, enableOnUnlock,
            { it.microphoneDisableOnLock }, { it.microphoneEnableOnUnlock })
    }

    fun updateFeatureSetting(feature: PrivacyFeature, disableOnLock: Boolean? = null, enableOnUnlock: Boolean? = null) {
        when (feature) {
            PrivacyFeature.WIFI -> updateWifiSettings(disableOnLock, enableOnUnlock)
            PrivacyFeature.BLUETOOTH -> updateBluetoothSettings(disableOnLock, enableOnUnlock)
            PrivacyFeature.MOBILE_DATA -> updateMobileDataSettings(disableOnLock, enableOnUnlock)
            PrivacyFeature.LOCATION -> updateLocationSettings(disableOnLock, enableOnUnlock)
            PrivacyFeature.NFC -> updateNFCSettings(disableOnLock, enableOnUnlock)
            PrivacyFeature.CAMERA -> updateCameraSettings(disableOnLock, enableOnUnlock)
            PrivacyFeature.MICROPHONE -> updateMicrophoneSettings(disableOnLock, enableOnUnlock)
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
    val nfcEnableOnUnlock: Boolean = Constants.Defaults.NFC_ENABLE_ON_UNLOCK,
    val cameraDisableOnLock: Boolean = Constants.Defaults.CAMERA_DISABLE_ON_LOCK,
    val cameraEnableOnUnlock: Boolean = Constants.Defaults.CAMERA_ENABLE_ON_UNLOCK,
    val microphoneDisableOnLock: Boolean = Constants.Defaults.MICROPHONE_DISABLE_ON_LOCK,
    val microphoneEnableOnUnlock: Boolean = Constants.Defaults.MICROPHONE_ENABLE_ON_UNLOCK
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
            PrivacyFeature.CAMERA -> copy(
                cameraDisableOnLock = disableOnLock,
                cameraEnableOnUnlock = enableOnUnlock
            )
            PrivacyFeature.MICROPHONE -> copy(
                microphoneDisableOnLock = disableOnLock,
                microphoneEnableOnUnlock = enableOnUnlock
            )
        }
    }
}

data class UiState(
    val isLoading: Boolean = false,
    val isRootAvailable: Boolean = false,
    val isRootGranted: Boolean = false,
    // New: Privilege method information
    val privilegeMethod: PrivilegeMethod = PrivilegeMethod.NONE,
    val privilegeMethodName: String = "None",
    val privilegeMethodDescription: String = "Root or Shizuku required",
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
    ),
    // Lock delay warning for camera/mic
    val showLockDelayWarning: Boolean = false
)