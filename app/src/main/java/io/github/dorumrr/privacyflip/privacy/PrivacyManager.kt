package io.github.dorumrr.privacyflip.privacy

import android.content.Context
import android.util.Log
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.DualLogger
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

    private val dualLog = DualLogger(context, TAG)

    private fun logDebug(message: String) = dualLog.i(message)

    private fun logWarning(message: String) = dualLog.w(message)

    private fun logError(message: String, e: Exception? = null) = dualLog.e(message, e)
    
    // Built from the enum itself, through an exhaustive `when`: adding a PrivacyFeature without
    // giving it a toggle here is a compile error, not a feature that silently does nothing at
    // runtime.
    private val toggles: Map<PrivacyFeature, PrivacyToggle> =
        PrivacyFeature.values().associateWith { createToggle(it) }

    private fun createToggle(feature: PrivacyFeature): PrivacyToggle = when (feature) {
        PrivacyFeature.WIFI -> WiFiToggle(rootManager)
        PrivacyFeature.BLUETOOTH -> BluetoothToggle(rootManager)
        PrivacyFeature.MOBILE_DATA -> MobileDataToggle(rootManager)
        PrivacyFeature.LOCATION -> LocationToggle(rootManager)
        PrivacyFeature.NFC -> NFCToggle(rootManager, context)
        PrivacyFeature.CAMERA -> CameraToggle(rootManager)
        PrivacyFeature.MICROPHONE -> MicrophoneToggle(rootManager)
        PrivacyFeature.AIRPLANE_MODE -> AirplaneModeToggle(rootManager)
        PrivacyFeature.BATTERY_SAVER -> BatterySaverToggle(rootManager)
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
                        logWarning("❌ Commands attempted: ${result.commandUsed}")
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


