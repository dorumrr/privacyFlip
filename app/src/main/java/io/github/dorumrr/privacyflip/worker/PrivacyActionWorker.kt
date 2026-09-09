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
import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.data.PrivacyResult
import io.github.dorumrr.privacyflip.privacy.PrivacyManager
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.ConnectionStateChecker
import io.github.dorumrr.privacyflip.util.DebugLogHelper
import io.github.dorumrr.privacyflip.util.DebugNotificationHelper
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.util.FeatureConfigurationManager
import io.github.dorumrr.privacyflip.util.ForegroundAppDetector
import kotlinx.coroutines.delay

class PrivacyActionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "privacyFlip-PrivacyActionWorker"

        // True for exactly the window where a sensor (camera/mic) disable is actually running.
        // ScreenStateReceiver, PrivacyAccessibilityService and PrivacyMonitorService all check
        // this before enqueuing the same unique lock-work under ExistingWorkPolicy.REPLACE, so
        // none of them cancels a disable that's already mid-flight (#G1) - REPLACE would
        // otherwise silently cancel the coroutine (see the CancellationException catch below)
        // partway through, leaving sensors on with no error shown anywhere.
        @Volatile
        var sensorDisableInProgress: Boolean = false
            private set
    }

    private val debugNotifier: DebugNotificationHelper by lazy {
        DebugNotificationHelper.getInstance(applicationContext)
    }

    private val debugLogger: DebugLogHelper by lazy {
        DebugLogHelper.getInstance(applicationContext)
    }

    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager.getInstance(applicationContext)
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

    private fun showToast(message: String) {
        // Only show toast if debug notifications are enabled
        if (!preferenceManager.debugNotificationsEnabled) {
            return
        }
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

            logDebug("🔒 Executing privacy actions: locking=$isLocking, deviceLocked=$isDeviceLocked, trigger=$trigger, reason=$reason")

            val rootManager = RootManager.getInstance(Unit)
            rootManager.initialize(applicationContext)

            // Check if privilege is granted (works for Root, Dhizuku, Shizuku, and Sui)
            val hasPrivilege = rootManager.isRootGranted()

            if (!hasPrivilege) {
                logWarning("Privilege permission not granted - cannot execute privacy actions")
                logWarning("User must grant permission from the UI before privacy actions can be executed")
                debugNotifier.notifyNoPrivilege()
                return Result.failure()
            }

            val privacyManager = PrivacyManager.getInstance(applicationContext)
            val configManager = FeatureConfigurationManager(preferenceManager)
            val connectionChecker = ConnectionStateChecker(applicationContext, rootManager)
            val foregroundAppDetector = ForegroundAppDetector(applicationContext)

            val isGlobalPrivacyEnabled = preferenceManager.isGlobalPrivacyEnabled
            if (!isGlobalPrivacyEnabled) {
                logDebug("🚫 Global privacy is disabled - skipping all privacy actions")
                debugNotifier.notifyGlobalPrivacyDisabled()
                return Result.success()
            }

            // Check if any exempt app is in foreground
            val exemptApps = preferenceManager.getExemptApps()
            val foregroundExemptApp = if (exemptApps.isNotEmpty()) {
                foregroundAppDetector.getFirstForegroundApp(exemptApps)
            } else {
                null
            }

            if (foregroundExemptApp != null) {
                logDebug("🛡️ Exempt app '$foregroundExemptApp' is in foreground - skipping ALL privacy actions")
                debugNotifier.notifyFeatureSkipped("All features", "exempt app in foreground: $foregroundExemptApp")
                return Result.success()
            }

            if (isLocking) {
                val featuresToDisable = configManager.getFeaturesToDisableOnLock()

                if (featuresToDisable.isNotEmpty()) {
                    logDebug("Disabling features on lock: ${featuresToDisable.map { it.displayName }}")

                    // Never disable WiFi or Mobile Data while an active hotspot is running,
                    // regardless of the per-feature "only if unused" setting. WiFi shares its
                    // radio with the hotspot's access point, so turning WiFi off silently kills
                    // the hotspot for anyone tethered to it (#34). Mobile Data is the hotspot's
                    // own upstream internet connection on a phone - a WiFi hotspot can't also be
                    // fed by the phone's own WiFi client radio, so leaving WiFi on but cutting
                    // Mobile Data leaves the hotspot broadcasting with nothing to share (found in
                    // this project's own post-release audit, not part of the original #34 fix).
                    val hotspotActive = (PrivacyFeature.WIFI in featuresToDisable || PrivacyFeature.MOBILE_DATA in featuresToDisable) &&
                        connectionChecker.isHotspotActive()
                    if (hotspotActive) {
                        logDebug("📡 Hotspot is active - keeping WiFi and Mobile Data on despite lock")
                        debugNotifier.notifyFeatureSkipped("WiFi and Mobile Data", "hotspot is active")
                    }

                    // Filter features based on "only if unused/not connected" setting
                    val skippedFeatures = mutableListOf<String>()
                    val filteredFeatures = featuresToDisable.filter { feature ->
                        if ((feature == PrivacyFeature.WIFI || feature == PrivacyFeature.MOBILE_DATA) && hotspotActive) {
                            false // Hotspot check above already decided this one
                        } else if (!preferenceManager.getFeatureOnlyIfUnused(feature)) {
                            true // Always disable if "only if unused" is not enabled
                        } else {
                            // Check if feature is in use
                            val inUse = connectionChecker.isFeatureInUse(feature)
                            if (inUse) {
                                logDebug("⏸️ ${feature.displayName} is in use - skipping disable (onlyIfUnused=true)")
                                skippedFeatures.add(feature.displayName)
                                debugNotifier.notifyFeatureSkipped(feature.displayName, "in use/connected")
                            }
                            !inUse // Only include if NOT in use
                        }
                    }

                    logDebug("Features to disable after filtering: ${filteredFeatures.map { it.displayName }}")

                    // Split features into three groups:
                    // 1. Camera/Microphone - must be disabled IMMEDIATELY (before device locks)
                    //    because Android blocks changing sensor privacy while locked
                    // 2. Protection modes (Airplane Mode, Battery Saver) - must be ENABLED (not disabled)
                    // 3. Other regular features - disabled after the configured delay
                    val sensorFeatures = filteredFeatures.filter {
                        it == PrivacyFeature.CAMERA || it == PrivacyFeature.MICROPHONE
                    }
                    val protectionModes = filteredFeatures.filter {
                        it in PrivacyFeature.getSystemModeFeatures()
                    }
                    val regularFeatures = filteredFeatures.filter {
                        it != PrivacyFeature.CAMERA && it != PrivacyFeature.MICROPHONE &&
                        it !in PrivacyFeature.getSystemModeFeatures()
                    }

                    // Disable camera/microphone - attempted immediately, no artificial delay.
                    //
                    // #F1b, confirmed live this session: the old code waited 75ms then re-checked
                    // isScreenCurrentlyLocked() (isKeyguardLocked || !isInteractive) before
                    // attempting anything. That re-check can never say "still unlocked" for this
                    // branch specifically - it only runs because ACTION_SCREEN_OFF already fired,
                    // and the screen being off is exactly what makes !isInteractive true, on its
                    // own, regardless of whether the keyguard itself has engaged yet. A real lock
                    // was watched go through this exact path: 5/5 regular features disabled
                    // correctly, camera and microphone silently skipped every time, "by design",
                    // because the check could never pass. Waiting 75ms before even trying only
                    // made it worse - it handed the keyguard 75ms head start to win the race
                    // before the attempt was even made.
                    //
                    // Fixed by removing the predictive check entirely and trusting the real
                    // outcome of the command instead: attempt the disable right away, and let
                    // privacyManager.disableFeatures()'s own success/failure (already captured
                    // and logged below via processResults, same as every other feature) be the
                    // source of truth. If the keyguard genuinely wins the race, the command fails
                    // and that's reported honestly - it no longer gets silently pre-decided by a
                    // heuristic that was measuring the wrong thing.
                    if (sensorFeatures.isNotEmpty()) {
                      // sensorDisableInProgress stays true for this whole block, not just the
                      // actual disable call - the other two triggers check it before they'd
                      // REPLACE this same unique work, and the danger window is "could this
                      // enqueue cancel something already underway", which starts as soon as this
                      // block starts (#G1). Cleared in every exit path via finally, so a crash or
                      // an external cancellation can never leave it stuck true.
                      sensorDisableInProgress = true
                      try {
                        if (!isDeviceLocked) {
                            logDebug("🔒 Attempting to disable sensors immediately (no delay): ${sensorFeatures.map { it.displayName }}")
                            val sensorResults = privacyManager.disableFeatures(sensorFeatures.toSet())
                            processResults(sensorResults, sensorFeatures, "🔒", "disabled", "Disabled", isLockAction = true)
                            val stillFailed = sensorResults.filter { !it.success }
                            if (stillFailed.isNotEmpty()) {
                                logWarning("⚠️ Sensor disable failed, keyguard likely won the race: ${stillFailed.map { it.feature.displayName }}")
                                debugNotifier.notifyFeatureSkipped(
                                    stillFailed.map { it.feature.displayName }.joinToString(", "),
                                    "device locked before sensors could be disabled"
                                )
                            }
                        } else {
                            logWarning("⚠️ Device already locked at ACTION_SCREEN_OFF - cannot disable sensors: ${sensorFeatures.map { it.displayName }}")
                            debugNotifier.notifyFeatureSkipped(
                                sensorFeatures.map { it.displayName }.joinToString(", "),
                                "device already locked"
                            )
                        }
                      } finally {
                        sensorDisableInProgress = false
                      }
                    }

                    // Handle regular features and protection modes after delay
                    if (regularFeatures.isNotEmpty() || protectionModes.isNotEmpty()) {
                        logDebug("📍 CHECKPOINT: Entering regular features/protection modes block")
                        logDebug("📊 regularFeatures count: ${regularFeatures.size}, protectionModes count: ${protectionModes.size}")
                        logDebug("📊 regularFeatures: ${regularFeatures.map { it.displayName }}")

                        // Always honour the user's configured delay, even if the device is
                        // already locked by the time this job runs (#30). Most phones lock
                        // instantly on screen-off, so skipping the delay in that case used to
                        // mean the delay setting rarely applied at all. Safe to always wait:
                        // the isStillLocked check right below already cancels the whole action
                        // if the user unlocks again during the wait.
                        val lockDelay = preferenceManager.lockDelaySeconds

                        logDebug("⏱️ Lock delay: ${lockDelay}s (isDeviceLocked=$isDeviceLocked, always honoured)")

                        if (lockDelay > 0) {
                            logDebug("⏳ Waiting ${lockDelay}s before disabling other features")
                            delay(lockDelay * 1000L)

                            logDebug("⏱️ Delay completed, now validating screen state...")

                            // Validate screen is still locked after delay
                            val isStillLocked = isScreenCurrentlyLocked()
                            logDebug("🔍 Screen lock validation: isStillLocked=$isStillLocked")

                            if (!isStillLocked) {
                                logWarning("⚠️ Screen is no longer locked after delay - cancelling disable action")
                                try {
                                    val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                                    val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                                    logWarning("🔍 KeyguardManager.isKeyguardLocked: ${km.isKeyguardLocked}")
                                    logWarning("🔍 PowerManager.isInteractive: ${pm.isInteractive}")
                                } catch (e: Exception) {
                                    logError("Error logging lock state details", e)
                                }
                                debugNotifier.notifyActionCancelled("Screen unlocked during delay - disable cancelled")
                                return Result.success()
                            }

                            logDebug("🔍 Checking global privacy setting...")

                            // Re-check global privacy setting after delay
                            val isGlobalPrivacyStillEnabled = preferenceManager.isGlobalPrivacyEnabled
                            logDebug("🔍 Global privacy enabled: $isGlobalPrivacyStillEnabled")

                            if (!isGlobalPrivacyStillEnabled) {
                                logDebug("🚫 Global privacy disabled during delay - cancelling disable action")
                                debugNotifier.notifyActionCancelled("Global privacy disabled during delay")
                                return Result.success()
                            }
                        } else {
                            logDebug("⚡ Skipping delay (lockDelay=0), proceeding directly to disable features")
                        }

                        logDebug("📍 CHECKPOINT: Passed all validations, proceeding to disable features")

                        // Disable regular features (WiFi, Bluetooth, NFC, etc.)
                        if (regularFeatures.isNotEmpty()) {
                            logDebug("🔒 Disabling regular features (count=${regularFeatures.size}): ${regularFeatures.map { it.displayName }}")
                            logDebug("🔒 About to call privacyManager.disableFeatures()...")

                            val regularResults = privacyManager.disableFeatures(regularFeatures.toSet())

                            logDebug("🔒 privacyManager.disableFeatures() returned ${regularResults.size} results")

                            processResults(regularResults, regularFeatures, "🔒", "disabled", "Disabled", isLockAction = true)
                        } else {
                            logDebug("ℹ️ No regular features to disable (list is empty)")
                        }

                        // ENABLE protection modes (Airplane Mode, Battery Saver) - note: ENABLE, not disable!
                        // Also track whether we enabled them (for "only if not manually set" feature)
                        if (protectionModes.isNotEmpty()) {
                            logDebug("🛡️ Enabling protection modes on lock: ${protectionModes.map { it.displayName }}")
                            
                            // Get current status to check if already enabled
                            val currentStatus = privacyManager.getCurrentStatus()
                            
                            for (mode in protectionModes) {
                                val wasAlreadyEnabled = currentStatus[mode] == FeatureState.ENABLED
                                
                                if (wasAlreadyEnabled) {
                                    // Already enabled (manually by user) - don't enable, mark as not enabled by app
                                    logDebug("🛡️ ${mode.displayName} already enabled (manually set) - skipping")
                                    preferenceManager.setFeatureEnabledByApp(mode, false)
                                    debugNotifier.notifyFeatureSkipped(mode.displayName, "already enabled")
                                } else {
                                    // Not enabled - enable it and mark as enabled by app
                                    val results = privacyManager.enableFeatures(setOf(mode))
                                    val success = results.firstOrNull()?.success == true
                                    if (success) {
                                        preferenceManager.setFeatureEnabledByApp(mode, true)
                                        logDebug("🛡️ ${mode.displayName} enabled by app")
                                    }
                                    processResults(results, listOf(mode), "🛡️", "enabled", "Enabled", isLockAction = true)
                                }
                            }
                        }
                    }
                }

            } else {
                val featuresToEnable = configManager.getFeaturesToEnableOnUnlock()

                if (featuresToEnable.isNotEmpty()) {
                    logDebug("Enabling features on unlock: ${featuresToEnable.map { it.displayName }}")

                    // Split into sensor features, protection modes, and regular features
                    val sensorFeatures = featuresToEnable.filter {
                        it == PrivacyFeature.CAMERA || it == PrivacyFeature.MICROPHONE
                    }
                    val protectionModes = featuresToEnable.filter {
                        it in PrivacyFeature.getSystemModeFeatures()
                    }
                    val regularFeatures = featuresToEnable.filter {
                        it != PrivacyFeature.CAMERA && it != PrivacyFeature.MICROPHONE &&
                        it !in PrivacyFeature.getSystemModeFeatures()
                    }

                    // Enable camera/microphone IMMEDIATELY (no delay), skipping ones already on.
                    // CAMERA_ONLY_IF_NOT_ENABLED / MICROPHONE_ONLY_IF_NOT_ENABLED both default to
                    // true (Constants.kt) - the intent was always to skip a redundant re-enable
                    // here, same as regular features already do below, but this block never
                    // actually read that preference (#21's real, if minor, finding: every
                    // catch-up re-check - the app can only detect a real lock while its
                    // background service is alive, and gets restarted often on some phones -
                    // was unconditionally re-enabling the microphone even when it was already on).
                    if (sensorFeatures.isNotEmpty()) {
                        val currentSensorStatus = privacyManager.getCurrentStatus()
                        val filteredSensorFeatures = sensorFeatures.filter { feature ->
                            val onlyIfNotEnabled = preferenceManager.getFeatureOnlyIfNotEnabled(feature)
                            if (!onlyIfNotEnabled) {
                                true
                            } else {
                                val isAlreadyEnabled = currentSensorStatus[feature] == FeatureState.ENABLED
                                if (isAlreadyEnabled) {
                                    logDebug("⏸️ ${feature.displayName} already enabled - skipping enable (onlyIfNotEnabled=true)")
                                }
                                !isAlreadyEnabled
                            }
                        }
                        if (filteredSensorFeatures.isNotEmpty()) {
                            logDebug("⚡ Enabling sensors immediately (no delay): ${filteredSensorFeatures.map { it.displayName }}")
                            val sensorResults = privacyManager.enableFeatures(filteredSensorFeatures.toSet())
                            processResults(sensorResults, filteredSensorFeatures, "🔓", "enabled", "Re-enabled", isLockAction = false)
                        }
                    }

                    // Handle regular features and protection modes after delay
                    if (regularFeatures.isNotEmpty() || protectionModes.isNotEmpty()) {
                        val unlockDelay = preferenceManager.unlockDelaySeconds
                        if (unlockDelay > 0) {
                            logDebug("⏳ Waiting ${unlockDelay}s before enabling other features")
                            delay(unlockDelay * 1000L)

                            // Validate screen is still unlocked after delay
                            if (isScreenCurrentlyLocked()) {
                                logWarning("⚠️ Screen is locked again after delay - cancelling enable action")
                                debugNotifier.notifyActionCancelled("Screen locked during delay - enable cancelled")
                                return Result.success()
                            }

                            // Re-check global privacy setting after delay
                            if (!preferenceManager.isGlobalPrivacyEnabled) {
                                logDebug("🚫 Global privacy disabled during delay - skipping enable action")
                                debugNotifier.notifyActionCancelled("Global privacy disabled during delay")
                                return Result.success()
                            }
                        }

                        // Enable regular features (WiFi, Bluetooth, etc.)
                        if (regularFeatures.isNotEmpty()) {
                            // Filter features based on "only if not already enabled" setting
                            // This prevents connection resets (e.g., WiFi/VPN disconnections)
                            val currentStatus = privacyManager.getCurrentStatus()
                            val filteredRegularFeatures = regularFeatures.filter { feature ->
                                val onlyIfNotEnabled = preferenceManager.getFeatureOnlyIfNotEnabled(feature)
                                if (!onlyIfNotEnabled) {
                                    true // Always enable if "only if not enabled" is not set
                                } else {
                                    // Check current state
                                    val currentState = currentStatus[feature]
                                    val isAlreadyEnabled = currentState == FeatureState.ENABLED

                                    if (isAlreadyEnabled) {
                                        logDebug("⏸️ ${feature.displayName} already enabled - skipping enable (onlyIfNotEnabled=true)")
                                        debugNotifier.notifyFeatureSkipped(feature.displayName, "already enabled")
                                    }
                                    !isAlreadyEnabled // Only include if NOT already enabled
                                }
                            }

                            if (filteredRegularFeatures.isNotEmpty()) {
                                logDebug("🔓 Enabling regular features: ${filteredRegularFeatures.map { it.displayName }}")
                                val regularResults = privacyManager.enableFeatures(filteredRegularFeatures.toSet())
                                processResults(regularResults, filteredRegularFeatures, "🔓", "enabled", "Re-enabled", isLockAction = false)
                            }
                        }

                        // DISABLE protection modes (Airplane Mode, Battery Saver) - note: DISABLE, not enable!
                        // Check "only if not manually set" preference before disabling
                        if (protectionModes.isNotEmpty()) {
                            logDebug("🛡️ Disabling protection modes on unlock: ${protectionModes.map { it.displayName }}")
                            
                            for (mode in protectionModes) {
                                val onlyIfNotManual = preferenceManager.getFeatureOnlyIfNotManual(mode)
                                val wasEnabledByApp = preferenceManager.getFeatureEnabledByApp(mode)
                                
                                if (onlyIfNotManual && !wasEnabledByApp) {
                                    // "Only if not manually set" is enabled AND we didn't enable it
                                    // Skip disabling - user had it enabled manually
                                    logDebug("🛡️ ${mode.displayName} was manually set - skipping disable (onlyIfNotManual=true)")
                                    debugNotifier.notifyFeatureSkipped(mode.displayName, "manually set")
                                } else {
                                    // Either "only if not manually set" is disabled, or we enabled it
                                    // Disable it and clear the flag
                                    val results = privacyManager.disableFeatures(setOf(mode))
                                    preferenceManager.setFeatureEnabledByApp(mode, false)
                                    processResults(results, listOf(mode), "🛡️", "disabled", "Disabled", isLockAction = false)
                                }
                            }
                        }
                    }
                }
            }

            return Result.success()
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Work was cancelled (e.g., screen state changed during delay)
            // This is expected behavior, not an error
            logDebug("⚠️ Privacy action cancelled (screen state changed)")
            debugNotifier.notifyActionCancelled("Screen state changed during action")
            throw e // Re-throw to properly cancel the coroutine
        } catch (e: Exception) {
            logError("Privacy action worker failed", e)
            debugNotifier.notifyError("Worker failed: ${e.message}")
            return Result.failure()
        }
    }

    private fun processResults(
        results: List<PrivacyResult>,
        features: List<PrivacyFeature>,
        logIcon: String,
        actionPastTense: String,
        toastPrefix: String,
        isLockAction: Boolean
    ) {
        val successCount = results.count { it.success }
        val failedResults = results.filter { !it.success }

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

            // Send debug notification for successful actions
            if (isLockAction) {
                debugNotifier.notifyLockAction(successfulFeatures)
            } else {
                debugNotifier.notifyUnlockAction(successfulFeatures)
            }
        }

        // Notify about failures
        if (failedResults.isNotEmpty()) {
            val failedFeatureNames = failedResults.map { it.feature.displayName }
            debugNotifier.notifyError("Failed to ${if (isLockAction) "disable" else "enable"}: ${failedFeatureNames.joinToString(", ")}")
        }
    }
}
