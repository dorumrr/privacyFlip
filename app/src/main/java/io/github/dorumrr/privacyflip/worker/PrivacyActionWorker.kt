package io.github.dorumrr.privacyflip.worker

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.data.PrivacyResult
import io.github.dorumrr.privacyflip.privacy.PrivacyManager
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.ConnectionStateChecker
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.util.FeatureConfigurationManager
import kotlinx.coroutines.delay

class PrivacyActionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "privacyFlip-PrivacyActionWorker"
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Checks if the screen is currently locked.
     * Used to validate screen state after delays to prevent executing stale actions.
     *
     * @return true if screen is locked, false if unlocked
     */
    private fun isScreenCurrentlyLocked(): Boolean {
        return try {
            val keyguardManager = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

            val isKeyguardLocked = keyguardManager.isKeyguardLocked
            val isScreenOn = powerManager.isInteractive

            // Screen is considered locked if keyguard is active OR screen is off
            isKeyguardLocked || !isScreenOn
        } catch (e: Exception) {
            Log.e(TAG, "Error checking screen lock state", e)
            false // Default to unlocked if we can't determine state
        }
    }
    
    override suspend fun doWork(): Result {
        try {
            val isLocking = inputData.getBoolean("is_locking", false)
            val isDeviceLocked = inputData.getBoolean("is_device_locked", false)
            val trigger = inputData.getString("trigger") ?: "unknown"
            val reason = inputData.getString("reason") ?: "Unknown"

            Log.i(TAG, "🔒 Executing privacy actions: locking=$isLocking, deviceLocked=$isDeviceLocked, trigger=$trigger, reason=$reason")

            val rootManager = RootManager.getInstance(Unit)
            rootManager.initialize(applicationContext)

            // Check if privilege is granted (works for Root, Shizuku, and Sui)
            val hasPrivilege = rootManager.isRootGranted()

            if (!hasPrivilege) {
                Log.w(TAG, "Privilege permission not granted - cannot execute privacy actions")
                Log.w(TAG, "User must grant permission from the UI before privacy actions can be executed")
                return Result.failure()
            }

            val privacyManager = PrivacyManager.getInstance(applicationContext)
            val preferenceManager = PreferenceManager.getInstance(applicationContext)
            val configManager = FeatureConfigurationManager(preferenceManager)
            val connectionChecker = ConnectionStateChecker(applicationContext, rootManager)

            val isGlobalPrivacyEnabled = preferenceManager.isGlobalPrivacyEnabled
            if (!isGlobalPrivacyEnabled) {
                Log.i(TAG, "🚫 Global privacy is disabled - skipping all privacy actions")
                return Result.success()
            }

            if (isLocking) {
                val featuresToDisable = configManager.getFeaturesToDisableOnLock()

                if (featuresToDisable.isNotEmpty()) {
                    Log.i(TAG, "Disabling features on lock: ${featuresToDisable.map { it.displayName }}")

                    // Filter features based on "only if unused/not connected" setting
                    val filteredFeatures = featuresToDisable.filter { feature ->
                        val onlyIfUnused = preferenceManager.getFeatureOnlyIfUnused(feature)
                        if (!onlyIfUnused) {
                            true // Always disable if "only if unused" is not enabled
                        } else {
                            // Check if feature is in use
                            val inUse = connectionChecker.isFeatureInUse(feature)
                            if (inUse) {
                                Log.i(TAG, "⏸️ ${feature.displayName} is in use - skipping disable (onlyIfUnused=true)")
                            }
                            !inUse // Only include if NOT in use
                        }
                    }

                    Log.i(TAG, "Features to disable after filtering: ${filteredFeatures.map { it.displayName }}")

                    // Split features into two groups:
                    // 1. Camera/Microphone - must be disabled IMMEDIATELY (before device locks)
                    //    because Android blocks changing sensor privacy while locked
                    // 2. Other features - can be disabled after the configured delay
                    val sensorFeatures = filteredFeatures.filter {
                        it == PrivacyFeature.CAMERA || it == PrivacyFeature.MICROPHONE
                    }
                    val otherFeatures = filteredFeatures.filter {
                        it != PrivacyFeature.CAMERA && it != PrivacyFeature.MICROPHONE
                    }

                    // Disable camera/microphone IMMEDIATELY (no delay)
                    // BUT only if device is NOT locked yet (keyguard not engaged)
                    if (sensorFeatures.isNotEmpty()) {
                        if (!isDeviceLocked) {
                            Log.i(TAG, "⚡ Device unlocked - disabling sensors immediately: ${sensorFeatures.map { it.displayName }}")
                            val sensorResults = privacyManager.disableFeatures(sensorFeatures.toSet())
                            processResults(sensorResults, sensorFeatures, "🔒", "disabled", "Disabled")
                        } else {
                            Log.w(TAG, "⚠️ Device already locked - CANNOT disable sensors (Android restriction): ${sensorFeatures.map { it.displayName }}")
                        }
                    }

                    // Disable other features after the configured delay
                    if (otherFeatures.isNotEmpty()) {
                        val lockDelay = preferenceManager.lockDelaySeconds
                        if (lockDelay > 0) {
                            Log.i(TAG, "⏳ Waiting ${lockDelay}s before disabling other features")
                            delay(lockDelay * 1000L)

                            // Validate screen is still locked after delay
                            if (!isScreenCurrentlyLocked()) {
                                Log.w(TAG, "⚠️ Screen is no longer locked after delay - cancelling disable action")
                                return Result.success()
                            }

                            // Re-check global privacy setting after delay
                            if (!preferenceManager.isGlobalPrivacyEnabled) {
                                Log.i(TAG, "🚫 Global privacy disabled during delay - cancelling disable action")
                                return Result.success()
                            }
                        }

                        val otherResults = privacyManager.disableFeatures(otherFeatures.toSet())
                        processResults(otherResults, otherFeatures, "🔒", "disabled", "Disabled")
                    }
                }

            } else {
                val featuresToEnable = configManager.getFeaturesToEnableOnUnlock()

                if (featuresToEnable.isNotEmpty()) {
                    Log.i(TAG, "Enabling features on unlock: ${featuresToEnable.map { it.displayName }}")

                    // Split into sensor features (immediate) and other features (delayed)
                    // This mirrors the lock behavior for consistency
                    val sensorFeatures = featuresToEnable.filter {
                        it == PrivacyFeature.CAMERA || it == PrivacyFeature.MICROPHONE
                    }
                    val otherFeatures = featuresToEnable.filter {
                        it != PrivacyFeature.CAMERA && it != PrivacyFeature.MICROPHONE
                    }

                    // Enable camera/microphone IMMEDIATELY (no delay)
                    if (sensorFeatures.isNotEmpty()) {
                        Log.i(TAG, "⚡ Enabling sensors immediately (no delay): ${sensorFeatures.map { it.displayName }}")
                        val sensorResults = privacyManager.enableFeatures(sensorFeatures.toSet())
                        processResults(sensorResults, sensorFeatures, "🔓", "enabled", "Re-enabled")
                    }

                    // Enable other features after the configured delay
                    if (otherFeatures.isNotEmpty()) {
                        val unlockDelay = preferenceManager.unlockDelaySeconds
                        if (unlockDelay > 0) {
                            Log.i(TAG, "⏳ Waiting ${unlockDelay}s before enabling other features")
                            delay(unlockDelay * 1000L)

                            // Validate screen is still unlocked after delay
                            if (isScreenCurrentlyLocked()) {
                                Log.w(TAG, "⚠️ Screen is locked again after delay - cancelling enable action")
                                return Result.success()
                            }

                            // Re-check global privacy setting after delay
                            if (!preferenceManager.isGlobalPrivacyEnabled) {
                                Log.i(TAG, "🚫 Global privacy disabled during delay - skipping enable action")
                                return Result.success()
                            }
                        }
                        val otherResults = privacyManager.enableFeatures(otherFeatures.toSet())
                        processResults(otherResults, otherFeatures, "🔓", "enabled", "Re-enabled")
                    }
                }
            }

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
