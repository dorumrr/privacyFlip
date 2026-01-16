package io.github.dorumrr.privacyflip.privacy

import android.content.Context
import android.util.Log
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.DebugLogHelper
import io.github.dorumrr.privacyflip.util.SingletonHolder
import kotlinx.coroutines.*

class PrivacyManager private constructor(
    private val context: Context,
    private val rootManager: RootManager
) {

    companion object : SingletonHolder<PrivacyManager, Context>({ context ->
        PrivacyManager(
            context.applicationContext,
            RootManager.getInstance(Unit)
        )
    }) {
        private const val TAG = "privacyFlip-PrivacyManager"
    }

    private val debugLogger: DebugLogHelper by lazy {
        DebugLogHelper.getInstance(context)
    }

    private fun logDebug(message: String) {
        Log.i(TAG, message)
        debugLogger.i(TAG, message)
    }

    private fun logWarning(message: String) {
        Log.w(TAG, message)
        debugLogger.w(TAG, message)
    }

    private fun logError(message: String, e: Exception? = null) {
        Log.e(TAG, message, e)
        debugLogger.e(TAG, message, e)
    }
    
    private val toggles = mutableMapOf<PrivacyFeature, PrivacyToggle>()
    
    init {
        initializeToggles()
    }
    
    private fun initializeToggles() {
        toggles[PrivacyFeature.WIFI] = WiFiToggle(rootManager)
        toggles[PrivacyFeature.BLUETOOTH] = BluetoothToggle(rootManager)
        toggles[PrivacyFeature.MOBILE_DATA] = MobileDataToggle(rootManager)
        toggles[PrivacyFeature.LOCATION] = LocationToggle(rootManager)
        toggles[PrivacyFeature.NFC] = NFCToggle(rootManager, context)
        toggles[PrivacyFeature.CAMERA] = CameraToggle(rootManager)
        toggles[PrivacyFeature.MICROPHONE] = MicrophoneToggle(rootManager)
        toggles[PrivacyFeature.AIRPLANE_MODE] = AirplaneModeToggle(rootManager)
        toggles[PrivacyFeature.BATTERY_SAVER] = BatterySaverToggle(rootManager)
    }
    
    fun getAvailableToggles(): Map<PrivacyFeature, PrivacyToggle> {
        return toggles.toMap()
    }
    
    fun getToggle(feature: PrivacyFeature): PrivacyToggle? {
        return toggles[feature]
    }
    
    suspend fun checkFeatureSupport(): Map<PrivacyFeature, FeatureSupport> = withContext(Dispatchers.IO) {
        val supportMap = mutableMapOf<PrivacyFeature, FeatureSupport>()
        
        toggles.forEach { (feature, toggle) ->
            try {
                supportMap[feature] = toggle.isSupported()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking support for $feature", e)
                supportMap[feature] = FeatureSupport.UNSUPPORTED
            }
        }
        
        return@withContext supportMap
    }
    
    suspend fun getCurrentStatus(): Map<PrivacyFeature, FeatureState> = withContext(Dispatchers.IO) {
        val statusMap = mutableMapOf<PrivacyFeature, FeatureState>()
        
        toggles.forEach { (feature, toggle) ->
            try {
                statusMap[feature] = toggle.getCurrentState()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting status for $feature", e)
                statusMap[feature] = FeatureState.ERROR
            }
        }
        
        return@withContext statusMap
    }
    
    suspend fun enableFeatures(features: Set<PrivacyFeature>): List<PrivacyResult> =
        executeFeatureAction(features, true)

    suspend fun disableFeatures(features: Set<PrivacyFeature>): List<PrivacyResult> =
        executeFeatureAction(features, false)

    private suspend fun executeFeatureAction(features: Set<PrivacyFeature>, enable: Boolean): List<PrivacyResult> = withContext(Dispatchers.IO) {
        val action = if (enable) "enable" else "disable"
        val results = mutableListOf<PrivacyResult>()

        logDebug("📍 executeFeatureAction START: action=$action, features=${features.map { it.displayName }}")

        features.forEach { feature ->
            logDebug("🔄 Attempting to $action ${feature.displayName}...")

            val toggle = toggles[feature]
            if (toggle != null) {
                try {
                    val result = if (enable) toggle.enable() else toggle.disable()
                    results.add(result)

                    val status = if (result.success) "✅ SUCCESS" else "❌ FAILED"
                    logDebug("$status $action ${feature.displayName}: ${result.message}")
                    if (!result.success) {
                        logWarning("❌ Command used: ${result.commandUsed}")
                        logWarning("❌ Error details: ${result.message}")
                    }
                } catch (e: Exception) {
                    logError("❌ EXCEPTION ${action}ing $feature", e)
                    results.add(
                        PrivacyResult(
                            feature = feature,
                            success = false,
                            message = "Exception: ${e.message}"
                        )
                    )
                }
            } else {
                logError("❌ Toggle not found for $feature")
                results.add(
                    PrivacyResult(
                        feature = feature,
                        success = false,
                        message = "Feature not supported"
                    )
                )
            }
        }

        val successCount = results.count { it.success }
        val failCount = results.count { !it.success }
        logDebug("📍 executeFeatureAction END: $successCount succeeded, $failCount failed")

        return@withContext results
    }
    

    
    suspend fun getPrivacyStatus(): PrivacyStatus = withContext(Dispatchers.IO) {
        val currentStatus = getCurrentStatus()
        val activeFeatures = currentStatus.filterValues { it == FeatureState.DISABLED }.keys
        
        PrivacyStatus(
            isActive = activeFeatures.isNotEmpty(),
            activeFeatures = activeFeatures
        )
    }
}


