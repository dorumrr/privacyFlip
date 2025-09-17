package io.github.dorumrr.privacyflip.privacy

import android.content.Context
import android.util.Log
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.SingletonHolder
import kotlinx.coroutines.*

class PrivacyManager private constructor(
    private val context: Context,
    private val rootManager: RootManager
) {

    companion object : SingletonHolder<PrivacyManager, Context>({ context ->
        PrivacyManager(
            context.applicationContext,
            RootManager.getInstance()
        )
    }) {
        private const val TAG = "PrivacyManager"
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

        features.forEach { feature ->
            val toggle = toggles[feature]
            if (toggle != null) {
                try {
                    val result = if (enable) toggle.enable() else toggle.disable()
                    results.add(result)
                    Log.d(TAG, "${action.replaceFirstChar { it.uppercase() }} result for $feature: ${result.success}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error ${action}ing $feature", e)
                    results.add(
                        PrivacyResult(
                            feature = feature,
                            success = false,
                            message = "Exception: ${e.message}"
                        )
                    )
                }
            } else {
                results.add(
                    PrivacyResult(
                        feature = feature,
                        success = false,
                        message = "Feature not supported"
                    )
                )
            }
        }

        return@withContext results
    }
    
    suspend fun executePanicMode(): List<PrivacyResult> = withContext(Dispatchers.IO) {
        Log.w(TAG, "Executing panic mode - disabling all connectivity")

        return@withContext disableFeatures(PrivacyFeature.getConnectivityFeatures().toSet())
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


