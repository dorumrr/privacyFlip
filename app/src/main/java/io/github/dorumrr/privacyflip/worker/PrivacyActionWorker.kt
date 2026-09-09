package io.github.dorumrr.privacyflip.worker

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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

        // #C2 Part 1: when an unlock is confirmed while sensorDisableInProgress is true, it is
        // deliberately NOT cancelled above (the same #G1 reason) - but nothing recorded that the
        // unlock happened, so this same lock cycle's own later regular-features/protection-modes
        // stage had no way to know and could still act on a phone the user had already unlocked.
        // Every unlock-detector (util/PendingLockWork.kt's recordUnlock(), called from all 4 call
        // sites) writes this UNCONDITIONALLY, regardless of sensorDisableInProgress - a plain
        // timestamp write can never interrupt anything mid-flight the way an external
        // WorkManager cancel can, so it needs none of that guard. doWork() compares it against
        // its OWN lock cycle's start time, so a stale write from an earlier, unrelated cycle can
        // never wrongly cancel a new one - no explicit reset needed anywhere. SystemClock.
        // elapsedRealtime(), not System.currentTimeMillis(): immune to the wall clock itself
        // moving (NTP sync, the user changing the clock, DST), which a plain timestamp
        // comparison would otherwise be exposed to.
        @Volatile
        var lastUnlockAtMillis: Long = 0L
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

    /**
     * Applies "only if unused" to a group of features, with an optional hotspot exception for
     * WIFI/MOBILE_DATA (#C1/#34 - the only two that participate in it; CAMERA/MICROPHONE never
     * are). Shared by the sensor group's immediate filter and the regular group's single filter
     * (#C2 Part 2) so the two can never drift into checking this differently - each group used
     * to run its own copy of this same logic at two different pre/post-delay checkpoints.
     */
    private suspend fun filterByOnlyIfUnused(
        features: List<PrivacyFeature>,
        connectionChecker: ConnectionStateChecker,
        hotspotActiveNow: Boolean
    ): List<PrivacyFeature> {
        return features.filter { feature ->
            if ((feature == PrivacyFeature.WIFI || feature == PrivacyFeature.MOBILE_DATA) && hotspotActiveNow) {
                false // caller already logged/notified this once, in its aggregate hotspot message
            } else if (!preferenceManager.getFeatureOnlyIfUnused(feature)) {
                true // Always disable if "only if unused" is not enabled
            } else {
                val inUse = connectionChecker.isFeatureInUse(feature)
                if (inUse) {
                    logDebug("⏸️ ${feature.displayName} is in use - skipping disable (onlyIfUnused=true)")
                    debugNotifier.notifyFeatureSkipped(feature.displayName, "in use/connected")
                }
                !inUse // Only include if NOT in use
            }
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

                    // #C2 Part 1: this lock cycle's own start time, compared against
                    // lastUnlockAtMillis at the checkpoint below. Recorded before anything else
                    // in this cycle runs, so any unlock recorded from this point on - including
                    // one that lands during the sensor block just below, which deliberately does
                    // NOT cancel (#G1) - is caught there instead.
                    val thisLockCycleStartedAt = SystemClock.elapsedRealtime()

                    // Categorise by TYPE ONLY, unfiltered (#C2 Part 2). Filtering used to happen
                    // here too, in a shared pass, before the lock delay - narrowing this list
                    // once, then (for regular features) narrowing it again after the delay, only
                    // ever able to subtract. A feature excluded at THIS point (in use, or
                    // hotspot active) could then never be re-added even if the reason stopped
                    // applying before the delay ended. Each group below is now filtered exactly
                    // once, at the latest moment safe for that group: sensors immediately, since
                    // they act with no delay anyway and always have; regular features and
                    // protection modes together after the delay (or immediately if lockDelay==0).
                    val sensorFeatures = featuresToDisable.filter { it in PrivacyFeature.getSensorFeatures() }
                    val protectionModes = featuresToDisable.filter { it in PrivacyFeature.getSystemModeFeatures() }
                    val regularFeatures = featuresToDisable.filter {
                        it !in PrivacyFeature.getSensorFeatures() && it !in PrivacyFeature.getSystemModeFeatures()
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
                      val filteredSensorFeatures = filterByOnlyIfUnused(sensorFeatures, connectionChecker, hotspotActiveNow = false)
                      if (filteredSensorFeatures.isNotEmpty()) {
                        // sensorDisableInProgress stays true for this whole block, not just the
                        // actual disable call - the other two triggers check it before they'd
                        // REPLACE this same unique work, and the danger window is "could this
                        // enqueue cancel something already underway", which starts as soon as this
                        // block starts (#G1). Cleared in every exit path via finally, so a crash or
                        // an external cancellation can never leave it stuck true.
                        sensorDisableInProgress = true
                        try {
                          if (!isDeviceLocked) {
                              logDebug("🔒 Attempting to disable sensors immediately (no delay): ${filteredSensorFeatures.map { it.displayName }}")
                              val sensorResults = privacyManager.disableFeatures(filteredSensorFeatures.toSet())
                              processResults(sensorResults, filteredSensorFeatures, "🔒", "disabled", "Disabled", isLockAction = true)
                              val stillFailed = sensorResults.filter { !it.success }
                              if (stillFailed.isNotEmpty()) {
                                  logWarning("⚠️ Sensor disable failed, keyguard likely won the race: ${stillFailed.map { it.feature.displayName }}")
                                  debugNotifier.notifyFeatureSkipped(
                                      stillFailed.map { it.feature.displayName }.joinToString(", "),
                                      "device locked before sensors could be disabled"
                                  )
                              }
                          } else {
                              logWarning("⚠️ Device already locked at ACTION_SCREEN_OFF - cannot disable sensors: ${filteredSensorFeatures.map { it.displayName }}")
                              debugNotifier.notifyFeatureSkipped(
                                  filteredSensorFeatures.map { it.displayName }.joinToString(", "),
                                  "device already locked"
                              )
                          }
                        } finally {
                          sensorDisableInProgress = false
                        }
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

                        // #C2 Part 1: catches an unlock that this cycle's own sensor block above
                        // deliberately did not cancel for (#G1), plus - since this is the ONLY
                        // check on the lockDelay==0 path - is that path's sole re-validation of
                        // any kind. On the lockDelay>0 path this is a second, independent signal
                        // alongside isStillLocked just above: that already catches a LASTING
                        // unlock by the time the delay ends, this also catches one that happened
                        // and was recorded during the sensor block, closing the window before it
                        // rather than only after the wait.
                        if (lastUnlockAtMillis > thisLockCycleStartedAt) {
                            logWarning("⚠️ Unlocked during this lock cycle - cancelling remaining disable actions")
                            debugNotifier.notifyActionCancelled("Unlocked during lock cycle - disable cancelled")
                            return Result.success()
                        }

                        logDebug("📍 CHECKPOINT: Passed all validations, proceeding to disable features")

                        // Re-check hotspot state once, here - the latest safe moment (#B1, #C1),
                        // and now the ONLY moment (#C2 Part 2) rather than a pre-delay sample
                        // plus a post-delay re-sample. WiFi shares its radio with the hotspot's
                        // access point (#34); Mobile Data is the hotspot's own upstream
                        // connection, so cutting it leaves the hotspot broadcasting with nothing
                        // to share; Airplane Mode is a full radio kill switch with no per-feature
                        // "only if unused" setting of its own, so this check is the only thing
                        // standing between it and taking a live hotspot down outright (#C1).
                        val needsHotspotCheck = PrivacyFeature.WIFI in regularFeatures ||
                            PrivacyFeature.MOBILE_DATA in regularFeatures ||
                            PrivacyFeature.AIRPLANE_MODE in protectionModes
                        val hotspotActiveNow = needsHotspotCheck && connectionChecker.isHotspotActive()
                        if (hotspotActiveNow) {
                            val keptOn = buildList {
                                if (PrivacyFeature.WIFI in regularFeatures) add("WiFi")
                                if (PrivacyFeature.MOBILE_DATA in regularFeatures) add("Mobile Data")
                                if (PrivacyFeature.AIRPLANE_MODE in protectionModes) add("Airplane Mode")
                            }
                            logDebug("📡 Hotspot is active - keeping ${keptOn.joinToString(", ")} on despite lock")
                            debugNotifier.notifyFeatureSkipped(keptOn.joinToString(", "), "hotspot is active")
                        }

                        // Disable regular features (WiFi, Bluetooth, NFC, etc.) - filtered
                        // exactly once, here (#C2 Part 2): "only if unused" needs re-testing at
                        // whatever point actually precedes the disable call, since a feature
                        // reported free earlier could be in genuine active use by now - e.g.
                        // navigation started during the wait. An unconditionally included
                        // feature's presence here never depended on any snapshot to begin with.
                        if (regularFeatures.isNotEmpty()) {
                            val filteredRegularFeatures = filterByOnlyIfUnused(regularFeatures, connectionChecker, hotspotActiveNow)

                            if (filteredRegularFeatures.isNotEmpty()) {
                                logDebug("🔒 Disabling regular features (count=${filteredRegularFeatures.size}): ${filteredRegularFeatures.map { it.displayName }}")
                                logDebug("🔒 About to call privacyManager.disableFeatures()...")

                                val regularResults = privacyManager.disableFeatures(filteredRegularFeatures.toSet())

                                logDebug("🔒 privacyManager.disableFeatures() returned ${regularResults.size} results")

                                processResults(regularResults, filteredRegularFeatures, "🔒", "disabled", "Disabled", isLockAction = true)
                            } else {
                                logDebug("ℹ️ No regular features left to disable after the in-use/hotspot check")
                            }
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
                                // #C1: Airplane Mode is a full radio kill switch - unlike WiFi/
                                // Mobile Data it has no per-feature "only if unused" setting to
                                // check, so the hotspot re-check above is the only thing standing
                                // between it and taking a live hotspot down outright. Battery
                                // Saver does NOT get the same treatment: Android documents it as
                                // throttling background activity, not disabling radios - unlike
                                // the Airplane Mode case, this was not verified live against a
                                // real hotspot (this device's shell lacks the permission to start
                                // one), so it rests on documented platform behaviour, not a test.
                                if (mode == PrivacyFeature.AIRPLANE_MODE && hotspotActiveNow) {
                                    logDebug("🛡️ Skipping Airplane Mode - hotspot is active")
                                    continue
                                }

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
