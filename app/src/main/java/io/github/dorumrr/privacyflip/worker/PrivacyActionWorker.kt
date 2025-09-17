package io.github.dorumrr.privacyflip.worker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.privacy.PrivacyManager
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
            
            val privacyManager = PrivacyManager.getInstance(applicationContext)
            val preferenceManager = PreferenceManager.getInstance(applicationContext)
            val configManager = FeatureConfigurationManager(preferenceManager)
            
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
                    val successCount = results.count { it.success }

                    results.forEach { result ->
                        val status = if (result.success) "✅ SUCCESS" else "❌ FAILED"
                        Log.i(TAG, "🔒 ${result.feature.displayName}: $status")
                    }

                    Log.i(TAG, "Lock action completed: $successCount/${featuresToDisable.size} features disabled")

                    if (successCount > 0) {
                        val successfulFeatures = results.filter { it.success }.map { result ->
                            featuresToDisable.find { it.displayName == result.feature.displayName }?.displayName ?: result.feature.displayName
                        }
                        val toastMessage = "Disabled: ${successfulFeatures.joinToString(", ")}"
                        showToast(toastMessage)
                    }


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
                    val successCount = results.count { it.success }

                    results.forEach { result ->
                        val status = if (result.success) "✅ SUCCESS" else "❌ FAILED"
                        Log.i(TAG, "🔓 ${result.feature.displayName}: $status")
                    }

                    Log.i(TAG, "Unlock action completed: $successCount/${featuresToEnable.size} features enabled")

                    if (successCount > 0) {
                        val successfulFeatures = results.filter { it.success }.map { result ->
                            featuresToEnable.find { it.displayName == result.feature.displayName }?.displayName ?: result.feature.displayName
                        }
                        val toastMessage = "Re-enabled: ${successfulFeatures.joinToString(", ")}"
                        showToast(toastMessage)
                    }


                }
            }
            
            Log.d(TAG, "Privacy action worker completed successfully")
            return Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Privacy action worker failed", e)
            return Result.failure()
        }
    }
}
