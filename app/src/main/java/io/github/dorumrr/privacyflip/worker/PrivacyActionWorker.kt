package io.github.dorumrr.privacyflip.worker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.data.PrivacyResult
import io.github.dorumrr.privacyflip.privacy.PrivacyManager
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.util.FeatureConfigurationManager
import kotlinx.coroutines.delay

class PrivacyActionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "PrivacyActionWorker"
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    override suspend fun doWork(): Result {
        Log.d(TAG, "Privacy action worker started")
        
        try {
            val isLocking = inputData.getBoolean("is_locking", false)
            val trigger = inputData.getString("trigger") ?: "unknown"
            val reason = inputData.getString("reason") ?: "Unknown"

            Log.i(TAG, "🔒 Executing privacy actions: locking=$isLocking, trigger=$trigger, reason=$reason")

            // Ensure RootManager is initialized before creating PrivacyManager
            // This is crucial for boot scenarios where RootManager may not be initialized yet
            val rootManager = RootManager.getInstance(Unit)
            rootManager.initialize(applicationContext)
            Log.d(TAG, "RootManager initialized for privacy actions")

            // For boot scenarios, add extra delay to ensure root access is fully established
            if (trigger == "service_init") {
                Log.d(TAG, "Boot scenario detected - adding extra delay for root initialization")
                delay(2000L) // 2 second additional delay for boot scenarios
            }

            // Verify root access is available before proceeding
            val hasRoot = rootManager.isRootPermissionGranted()
            Log.d(TAG, "Root permission status: $hasRoot")

            if (!hasRoot) {
                Log.w(TAG, "Root permission not granted - attempting to request")
                val rootGranted = rootManager.requestRootPermission()
                if (!rootGranted) {
                    Log.e(TAG, "Failed to obtain root permission - privacy actions will fail")
                    return Result.failure()
                }
                Log.i(TAG, "Root permission successfully obtained")
            }

            val privacyManager = PrivacyManager.getInstance(applicationContext)
            val preferenceManager = PreferenceManager.getInstance(applicationContext)
            val configManager = FeatureConfigurationManager(preferenceManager)

            // Check if global privacy is enabled - if not, skip all privacy actions
            val isGlobalPrivacyEnabled = preferenceManager.isGlobalPrivacyEnabled
            if (!isGlobalPrivacyEnabled) {
                Log.i(TAG, "🚫 Global privacy is disabled - skipping all privacy actions")
                return Result.success()
            }

            if (isLocking) {
                val featuresToDisable = configManager.getFeaturesToDisableOnLock()

                if (featuresToDisable.isNotEmpty()) {
                    Log.i(TAG, "Disabling features on lock: ${featuresToDisable.map { it.displayName }}")

                    val lockDelay = preferenceManager.lockDelaySeconds
                    if (lockDelay > 0) {
                        Log.d(TAG, "Applying lock delay: ${lockDelay}s")
                        delay(lockDelay * 1000L)
                    }

                    val results = privacyManager.disableFeatures(featuresToDisable.toSet())
                    processResults(results, featuresToDisable, "🔒", "disabled", "Disabled")


                }
                
            } else {
                val featuresToEnable = configManager.getFeaturesToEnableOnUnlock()

                if (featuresToEnable.isNotEmpty()) {
                    Log.i(TAG, "Enabling features on unlock: ${featuresToEnable.map { it.displayName }}")

                    val unlockDelay = preferenceManager.unlockDelaySeconds
                    if (unlockDelay > 0) {
                        Log.d(TAG, "Applying unlock delay: ${unlockDelay}s")
                        delay(unlockDelay * 1000L)
                    }

                    val results = privacyManager.enableFeatures(featuresToEnable.toSet())
                    processResults(results, featuresToEnable, "🔓", "enabled", "Re-enabled")
                }
            }
            
            Log.d(TAG, "Privacy action worker completed successfully")
            return Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Privacy action worker failed", e)
            return Result.failure()
        }
    }

    private fun processResults(
        results: List<PrivacyResult>,
        features: List<PrivacyFeature>,
        logIcon: String,
        actionPastTense: String,
        toastPrefix: String
    ) {
        val successCount = results.count { it.success }

        results.forEach { result ->
            val status = if (result.success) "✅ SUCCESS" else "❌ FAILED"
            Log.i(TAG, "$logIcon ${result.feature.displayName}: $status")
        }

        Log.i(TAG, "Lock action completed: $successCount/${features.size} features $actionPastTense")

        if (successCount > 0) {
            val successfulFeatures = results.filter { it.success }.map { result ->
                features.find { it.displayName == result.feature.displayName }?.displayName ?: result.feature.displayName
            }
            val toastMessage = "$toastPrefix: ${successfulFeatures.joinToString(", ")}"
            showToast(toastMessage)
        }
    }
}
