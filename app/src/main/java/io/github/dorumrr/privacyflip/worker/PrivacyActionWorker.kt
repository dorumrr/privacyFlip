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
import io.github.dorumrr.privacyflip.util.DualLogger
import io.github.dorumrr.privacyflip.util.DebugNotificationHelper
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.util.FeatureConfigurationManager
import io.github.dorumrr.privacyflip.util.ForegroundAppDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.dorumrr.privacyflip.util.PrivacyActionWork

open class PrivacyActionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "privacyFlip-PrivacyActionWorker"

        // True from the start of a lock-direction doWork() invocation until that invocation's
        // own sensor stage concludes, or until the whole invocation exits early (no privilege,
        // global privacy off, exempt app) - not only during the moments a disable command is
        // actually running. ScreenStateReceiver, PrivacyAccessibilityService and
        // PrivacyMonitorService all check this before enqueuing the same unique lock-work under
        // ExistingWorkPolicy.REPLACE, so none of them cancels an invocation that's still mid-way
        // through, whether or not it has reached the sensor command itself yet - REPLACE would
        // otherwise silently cancel the coroutine (see the CancellationException catch below)
        // partway through, leaving sensors on with no error shown anywhere.
        //
        // Armed at the very start of doWork(), before any setup work (privilege check, object
        // construction) that can itself take real time talking to a real root/Shizuku shell:
        // arming it only once the sensor block itself began would let a second trigger for the
        // SAME real-world lock see this still false and REPLACE-cancel the worker mid-setup.
        // Cleared explicitly right after the sensor block concludes (see doWork()), with
        // doWork()'s own outer finally as a safety net for every early-return and exception path
        // that never reaches that explicit clear - guarded there against wiping a newer
        // invocation's still-in-flight flag (see that finally's own comment).
        @Volatile
        var sensorDisableInProgress: Boolean = false
            private set

        // The enable-side twin of sensorDisableInProgress above: without it, nothing stops a
        // THIRD unlock signal from REPLACE-enqueuing a new NAME_UNLOCK job while a SECOND one is
        // still mid-flight, cancelling that in-flight coroutine outright. Applied symmetrically:
        // every unlock-enqueue call site checks this before REPLACE. Armed and cleared the same
        // way as sensorDisableInProgress above.
        @Volatile
        var sensorEnableInProgress: Boolean = false
            private set

        // Makes a lock cycle's sensor disable and an unlock cycle's sensor enable mutually
        // exclusive: they are 2 separate WorkManager jobs, so nothing else serialises them.
        // Exclusion says nothing about ORDER, which isSupersededByFresherOppositeAction closes.
        internal val sensorMutex = Mutex()

        // Written by PendingLockWork.recordLock() at the trigger, before the job is enqueued:
        // doWork() starting at all waits on WorkManager dispatch latency (Doze, scheduler load),
        // which could make a genuinely LATER unlock look earlier than this cycle's own start.
        @Volatile
        var lastLockAtMillis: Long = 0L

        // An unlock during an in-flight sensor disable deliberately does not cancel it, so this
        // is how the rest of that cycle learns the unlock happened. Written unconditionally, from
        // SystemClock.elapsedRealtime(): a wall clock can move backwards (NTP sync, DST).
        @Volatile
        var lastUnlockAtMillis: Long = 0L

        // Shared by all 6 of doWork()'s supersede checks, 3 per direction, so a regression in the
        // comparison fails every one the same way. Callers must read the timestamp FRESH at each
        // checkpoint: one frozen at job start misses a re-trigger folded into this same job.
        internal fun isSupersededByFresherOppositeAction(
            oppositeActionAtMillis: Long,
            ownLastKnownAtMillis: Long
        ): Boolean = oppositeActionAtMillis > ownLastKnownAtMillis

        /**
         * Whether an action really took effect, for decisions that must not be made on a guess.
         *
         * An EMPTY result list is not a success: it means nothing was reported at all, and
         * treating that as "it worked" would clear state on the strength of no evidence.
         */
        internal fun allSucceeded(results: List<PrivacyResult>): Boolean =
            results.isNotEmpty() && results.all { it.success }

        /**
         * What observing a protection mode's state means for "this app turned it on".
         *
         * The app cannot tell, from a mode that is ON, whether the user set it or whether its own
         * disable failed at the last unlock and left it on. Assuming the user always did is what
         * made a failed disable permanent: the mode stayed on, the app disowned it, and every
         * later unlock skipped it as "manually set".
         *
         * A mode that is OFF settles it in the other direction: nothing this app turned on is
         * still on, so ownership cannot outlive it. An unreadable state settles nothing.
         */
        internal fun ownershipAfterObserving(
            state: FeatureState?,
            currentlyOwned: Boolean
        ): Boolean = when (state) {
            FeatureState.DISABLED -> false
            FeatureState.ENABLED -> currentlyOwned
            else -> currentlyOwned
        }

        /**
         * Whether an enable this app just ran makes the mode its own.
         *
         * Only a mode seen OFF and then switched on was turned on BY this app. A mode whose state
         * could not be read may already have been on by the user's hand, and an enable reports
         * success when its own read-back is unreadable, so claiming there would hand the app the
         * user's own setting and switch it off at the next unlock.
         */
        internal fun ownershipAfterEnabling(
            stateBefore: FeatureState?,
            enableSucceeded: Boolean
        ): Boolean = stateBefore == FeatureState.DISABLED && enableSucceeded

        private const val PRIVILEGE_CHECK_ATTEMPTS = 3
        private const val PRIVILEGE_CHECK_GAP_MS = 400L

        // One "no" can be a process WorkManager has only just started rather than a real refusal,
        // and believing it abandons the whole lock. internal so a test can drive it with a fake
        // probe instead of a real privileged shell.
        internal suspend fun privilegeIsGranted(
            attempts: Int = PRIVILEGE_CHECK_ATTEMPTS,
            gapMs: Long = PRIVILEGE_CHECK_GAP_MS,
            check: suspend () -> Boolean
        ): Boolean {
            repeat(attempts) { attempt ->
                if (check()) return true
                if (attempt < attempts - 1) delay(gapMs)
            }
            return false
        }
    }

    private val debugNotifier: DebugNotificationHelper by lazy {
        DebugNotificationHelper.getInstance(applicationContext)
    }

    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager.getInstance(applicationContext)
    }

    private val dualLog: DualLogger by lazy { DualLogger(applicationContext, TAG) }

    private val rootManager: RootManager by lazy { RootManager.getInstance(Unit) }

    private val privacyManager: PrivacyManager by lazy {
        PrivacyManager.getInstance(applicationContext)
    }

    private val connectionChecker: ConnectionStateChecker by lazy {
        ConnectionStateChecker(applicationContext, rootManager)
    }

    private val foregroundAppDetector: ForegroundAppDetector by lazy {
        ForegroundAppDetector(applicationContext)
    }

    // What doWork() cannot reach without a privileged shell arrives through an open member: the 7
    // here plus isScreenCurrentlyLocked below. Stored preferences stay direct, being themselves
    // in a test. Built lazily above, so a stand-in never constructs the real one.
    protected open suspend fun confirmPrivilege(): Boolean {
        rootManager.initialize(applicationContext)
        return privilegeIsGranted { rootManager.isRootGranted() }
    }

    protected open suspend fun getCurrentStatus(): Map<PrivacyFeature, FeatureState> =
        privacyManager.getCurrentStatus()

    protected open suspend fun enableFeatures(features: Set<PrivacyFeature>): List<PrivacyResult> =
        privacyManager.enableFeatures(features)

    protected open suspend fun disableFeatures(features: Set<PrivacyFeature>): List<PrivacyResult> =
        privacyManager.disableFeatures(features)

    protected open suspend fun isFeatureInUse(feature: PrivacyFeature): Boolean =
        connectionChecker.isFeatureInUse(feature)

    protected open suspend fun isHotspotActive(): Boolean = connectionChecker.isHotspotActive()

    protected open fun getFirstForegroundApp(exemptApps: Set<String>): String? =
        foregroundAppDetector.getFirstForegroundApp(exemptApps)

    private fun logDebug(message: String) = dualLog.i(message)

    private fun logWarning(message: String) = dualLog.w(message)

    private fun logError(message: String, e: Exception? = null) = dualLog.e(message, e)

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
     * Checks if the screen is currently locked. Used to validate screen state after delays to
     * prevent executing stale actions.
     *
     * Shared with PrivacyMonitorService via util/ScreenLockState.kt rather than each holding its
     * own copy, so a fail-closed fix to the lock-check itself (default to locked, not unlocked,
     * when the state can't be read) only has to exist in one place.
     *
     * @return true if screen is locked, false if unlocked
     */
    protected open fun isScreenCurrentlyLocked(): Boolean =
        io.github.dorumrr.privacyflip.util.isScreenCurrentlyLocked(applicationContext, TAG)

    /**
     * Applies "only if unused" to a group of features, with an optional hotspot exception for
     * WIFI/MOBILE_DATA (the only two that participate in it; CAMERA/MICROPHONE never are).
     * Shared by the sensor group's immediate filter and the regular group's single filter so the
     * two can never drift into checking this differently - each group used to run its own copy
     * of this same logic at two different pre/post-delay checkpoints.
     */
    private suspend fun filterByOnlyIfUnused(
        features: List<PrivacyFeature>,
        hotspotActiveNow: Boolean
    ): List<PrivacyFeature> {
        return features.filter { feature ->
            if ((feature == PrivacyFeature.WIFI || feature == PrivacyFeature.MOBILE_DATA) && hotspotActiveNow) {
                false // caller already logged/notified this once, in its aggregate hotspot message
            } else if (!preferenceManager.getFeatureOnlyIfUnused(feature)) {
                true // Always disable if "only if unused" is not enabled
            } else {
                val inUse = isFeatureInUse(feature)
                if (inUse) {
                    logDebug("⏸️ ${feature.displayName} is in use - skipping disable (onlyIfUnused=true)")
                    debugNotifier.notifyFeatureSkipped(feature.displayName, "in use/connected")
                }
                !inUse // Only include if NOT in use
            }
        }
    }

    override suspend fun doWork(): Result {
        val isLocking = inputData.getBoolean(PrivacyActionWork.KEY_IS_LOCKING, false)
        // Armed as the very first thing doWork() does, before any setup work below (privilege
        // check, object construction) that can itself take real time talking to a real
        // root/Shizuku shell - see sensorDisableInProgress's own comment above for why this
        // matters. Cleared explicitly right after each branch's own sensor block concludes (the
        // normal path, preserving the "still cancellable during the regular-features delay"
        // behaviour), and in the outer finally below for every early-return and exception path,
        // for THIS direction only (see that finally's own comment).
        if (isLocking) {
            sensorDisableInProgress = true
        } else {
            sensorEnableInProgress = true
        }
        // Tracks whether THIS invocation's own explicit clear (right after its own sensor block,
        // the normal path) has already run - see that finally's own comment for why this is
        // needed even when the clear happens on the normal path.
        var ownSensorGuardCleared = false
        try {
            val isDeviceLocked = inputData.getBoolean(PrivacyActionWork.KEY_IS_DEVICE_LOCKED, false)
            val trigger = inputData.getString(PrivacyActionWork.KEY_TRIGGER) ?: "unknown"
            val reason = inputData.getString(PrivacyActionWork.KEY_REASON) ?: "Unknown"

            logDebug("🔒 Executing privacy actions: locking=$isLocking, deviceLocked=$isDeviceLocked, trigger=$trigger, reason=$reason")

            // Works for Root, Dhizuku, Shizuku and Sui.
            val hasPrivilege = confirmPrivilege()

            if (!hasPrivilege) {
                logWarning("Could not confirm privileged access after $PRIVILEGE_CHECK_ATTEMPTS checks - skipping this action")
                logWarning("If access is already granted, this was a temporary failure rather than a refusal")
                debugNotifier.notifyNoPrivilege()
                return Result.failure()
            }

            val configManager = FeatureConfigurationManager(preferenceManager)

            val isGlobalPrivacyEnabled = preferenceManager.isGlobalPrivacyEnabled
            if (!isGlobalPrivacyEnabled) {
                logDebug("🚫 Global privacy is disabled - skipping all privacy actions")
                debugNotifier.notifyGlobalPrivacyDisabled()
                return Result.success()
            }

            // An exempt app stops this app DISABLING things, which is all the settings screen
            // promises. Blocking the unlock side too left the radios and sensors off, because the
            // app a user unlocks into is usually the exempt one that asked to be left alone.
            if (isLocking) {
                val exemptApps = preferenceManager.getExemptApps()
                val foregroundExemptApp = if (exemptApps.isNotEmpty()) {
                    getFirstForegroundApp(exemptApps)
                } else {
                    null
                }

                if (foregroundExemptApp != null) {
                    logDebug("🛡️ Exempt app '$foregroundExemptApp' is in foreground - not disabling anything")
                    debugNotifier.notifyFeatureSkipped("All features", "exempt app in foreground: $foregroundExemptApp")
                    return Result.success()
                }
            }

            if (isLocking) {
                val featuresToDisable = configManager.getFeaturesToDisableOnLock()

                if (featuresToDisable.isNotEmpty()) {
                    logDebug("Disabling features on lock: ${featuresToDisable.map { it.displayName }}")

                    // The 2 checkpoints below read lastLockAtMillis directly, fresh, each time -
                    // not a value frozen into a local once at the top of this branch. A frozen
                    // snapshot misses a re-lock that arrives while THIS job's own sensor step is
                    // still running (lock, then unlock, then re-lock, none of the later 2 getting
                    // their own job because the REPLACE-guard correctly folds them into this
                    // one): the frozen snapshot never learns about the re-lock, so the
                    // regularFeatures checkpoint would compare the unlock against the FIRST
                    // lock's stale timestamp and wrongly conclude it had been superseded. Reading
                    // lastLockAtMillis fresh is always safe: real lock timestamps only ever move
                    // forward (see recordLock()'s own comment), so a fresh read can only ever be
                    // a MORE complete picture than a frozen one, never a worse one.

                    // Categorise by TYPE ONLY, unfiltered. Filtering used to happen here too, in
                    // a shared pass, before the lock delay - narrowing this list once, then (for
                    // regular features) narrowing it again after the delay, only ever able to
                    // subtract. A feature excluded at THIS point (in use, or hotspot active)
                    // could then never be re-added even if the reason stopped applying before the
                    // delay ended. Each group below is now filtered exactly once, at the latest
                    // moment safe for that group: sensors immediately, since they act with no
                    // delay anyway and always have; regular features and protection modes
                    // together after the delay (or immediately if lockDelay==0). Hoisted once,
                    // not called again for every feature inside each filter predicate below
                    // (getSensorFeatures()/getSystemModeFeatures() each allocate their own Set on
                    // every call).
                    val sensorFeatureSet = PrivacyFeature.getSensorFeatures()
                    val systemModeFeatureSet = PrivacyFeature.getSystemModeFeatures()
                    val sensorFeatures = featuresToDisable.filter { it in sensorFeatureSet }
                    val protectionModes = featuresToDisable.filter { it in systemModeFeatureSet }
                    val regularFeatures = featuresToDisable.filter {
                        it !in sensorFeatureSet && it !in systemModeFeatureSet
                    }

                    // Disable camera/microphone - attempted immediately, no artificial delay.
                    //
                    // Whichever of the 3 producers enqueued this job (ScreenStateReceiver,
                    // PrivacyAccessibilityService, or PrivacyMonitorService's restart catch-up -
                    // see sensorDisableInProgress's own comment above; doWork() never branches on
                    // the "trigger" field itself, only logs it), a fresh re-check of
                    // isScreenCurrentlyLocked() here would only hand the keyguard more time to win
                    // the race before the attempt is even made, without changing what the command
                    // itself can prove. Instead, the real outcome of the command is trusted
                    // directly: attempt the disable right away, and let
                    // disableFeatures()'s own success/failure (already captured and
                    // logged below via processResults, same as every other feature) be the source
                    // of truth. If the keyguard genuinely wins the race, the command fails and
                    // that's reported honestly.
                    //
                    // One narrower gate does remain, and it is NOT that heuristic: the
                    // `else if (!isDeviceLocked)` below reads the flag the trigger supplied - each
                    // producer sets it from its own best knowledge of whether the device is
                    // already locked, rather than this code guessing from screen state. Skipping
                    // there is right, not pessimistic: on a real device, `cmd sensor_privacy
                    // enable 0 camera` leaves the camera allowed
                    // when the keyguard is up, and flips it the moment the same command runs
                    // unlocked. Attempting it would cost a shell round trip to change nothing.
                    if (sensorFeatures.isNotEmpty()) {
                      val filteredSensorFeatures = filterByOnlyIfUnused(sensorFeatures, hotspotActiveNow = false)
                      if (filteredSensorFeatures.isNotEmpty()) {
                        // Waits its turn if an unlock cycle's own sensor enable (below) is
                        // currently running - see sensorMutex's own comment above. Uncontended in
                        // the common case, so this changes nothing except in the exact race it
                        // exists to close.
                        sensorMutex.withLock {
                          // This cycle may have waited its turn at the mutex above - re-check
                          // right before acting, still inside the lock, whether a fresher unlock
                          // has already been recorded. If so, that unlock's own enable either
                          // already ran or is queued right behind this check - disabling now
                          // would stomp on it.
                          if (isSupersededByFresherOppositeAction(lastUnlockAtMillis, lastLockAtMillis)) {
                              logDebug("⚠️ A newer unlock was recorded since this lock - skipping sensor disable")
                              debugNotifier.notifyFeatureSkipped(
                                  filteredSensorFeatures.map { it.displayName }.joinToString(", "),
                                  "unlocked since this lock cycle started"
                              )
                          } else if (!isDeviceLocked) {
                              logDebug("🔒 Attempting to disable sensors immediately (no delay): ${filteredSensorFeatures.map { it.displayName }}")
                              val sensorResults = disableFeatures(filteredSensorFeatures.toSet())
                              processResults(sensorResults, filteredSensorFeatures, "🔒", "disabled", "Disabled", isLockCycle = true, didEnable = false)
                              val stillFailed = sensorResults.filter { !it.success }
                              if (stillFailed.isNotEmpty()) {
                                  // processResults above has already reported this failure. A second
                                  // notification calling it "skipped", with a cause nothing
                                  // determined, contradicted that: the keyguard winning the race is
                                  // the usual reason but not the only one, and it was attempted,
                                  // not skipped.
                                  logWarning("⚠️ Sensor disable did not take effect: ${stillFailed.map { it.feature.displayName }}")
                              }
                          } else {
                              logWarning("⚠️ Device already locked at ACTION_SCREEN_OFF - cannot disable sensors: ${filteredSensorFeatures.map { it.displayName }}")
                              debugNotifier.notifyFeatureSkipped(
                                  filteredSensorFeatures.map { it.displayName }.joinToString(", "),
                                  "device already locked"
                              )
                          }
                        }
                      }
                    }
                    // sensorDisableInProgress cleared HERE, right after the sensor block
                    // concludes (whether or not there was anything to disable) - not left true
                    // through the delay below, which would silently take away the "still
                    // cancellable while waiting" behaviour. The outer finally is a safety net for
                    // early returns/exceptions above this point only - marked done here so it
                    // knows not to touch the flag again (see that finally's own comment).
                    sensorDisableInProgress = false
                    ownSensorGuardCleared = true

                    // Handle regular features and protection modes after delay
                    if (regularFeatures.isNotEmpty() || protectionModes.isNotEmpty()) {
                        logDebug("📍 CHECKPOINT: Entering regular features/protection modes block")
                        logDebug("📊 regularFeatures count: ${regularFeatures.size}, protectionModes count: ${protectionModes.size}")
                        logDebug("📊 regularFeatures: ${regularFeatures.map { it.displayName }}")

                        // Always honour the user's configured delay, even if the device is
                        // already locked by the time this job runs. Most phones lock instantly on
                        // screen-off, so skipping the delay in that case would mean the delay
                        // setting rarely applied at all. Safe to always wait:
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

                        // Catches an unlock that this cycle's own sensor block above deliberately
                        // did not cancel for, plus - since this is the ONLY check on the
                        // lockDelay==0 path - is that path's sole re-validation of any kind. On
                        // the lockDelay>0 path this is a second, independent signal alongside
                        // isStillLocked just above: that already catches a LASTING unlock by the
                        // time the delay ends, this also catches one that happened and was
                        // recorded during the sensor block, closing the window before it rather
                        // than only after the wait. Compares against lastLockAtMillis read fresh
                        // here, not a value frozen at this job's own start - see
                        // isSupersededByFresherOppositeAction's comment above.
                        if (isSupersededByFresherOppositeAction(lastUnlockAtMillis, lastLockAtMillis)) {
                            logWarning("⚠️ Unlocked since this lock - cancelling remaining disable actions")
                            debugNotifier.notifyActionCancelled("Unlocked during lock cycle - disable cancelled")
                            return Result.success()
                        }

                        logDebug("📍 CHECKPOINT: Passed all validations, proceeding to disable features")

                        // Re-check hotspot state once, here - the latest safe moment for WiFi and
                        // Mobile Data, rather than a pre-delay sample plus a post-delay
                        // re-sample. WiFi shares its radio with the hotspot's access point;
                        // Mobile Data is the hotspot's own upstream connection, so cutting either
                        // leaves the hotspot broadcasting with nothing to share. Airplane Mode
                        // does NOT rely on this value - it gets its own later, independently
                        // fresh check below (hotspotActiveForAirplaneMode), since disableFeatures()
                        // for regularFeatures runs a real, possibly slow call in between.
                        val needsHotspotCheck = PrivacyFeature.WIFI in regularFeatures ||
                            PrivacyFeature.MOBILE_DATA in regularFeatures ||
                            PrivacyFeature.AIRPLANE_MODE in protectionModes
                        val hotspotActiveNow = needsHotspotCheck && isHotspotActive()
                        if (hotspotActiveNow) {
                            // Airplane Mode is deliberately NOT named here. This notification
                            // fires before the regularFeatures round-trip below, but Airplane
                            // Mode's own decision is made LATER, with its own fresh re-check
                            // (hotspotActiveForAirplaneMode, in the protection-modes loop) -
                            // naming it here could tell the user it was kept off while the fresh
                            // check then actually turns it on, or the reverse. Notified at the
                            // point the real decision is made instead, below.
                            val keptOn = buildList {
                                if (PrivacyFeature.WIFI in regularFeatures) add("WiFi")
                                if (PrivacyFeature.MOBILE_DATA in regularFeatures) add("Mobile Data")
                            }
                            if (keptOn.isNotEmpty()) {
                                logDebug("📡 Hotspot is active - keeping ${keptOn.joinToString(", ")} on despite lock")
                                debugNotifier.notifyFeatureSkipped(keptOn.joinToString(", "), "hotspot is active")
                            }
                        }

                        // Disable regular features (WiFi, Bluetooth, NFC, etc.) - filtered
                        // exactly once, here: "only if unused" needs re-testing at whatever
                        // point actually precedes the disable call, since a feature
                        // reported free earlier could be in genuine active use by now - e.g.
                        // navigation started during the wait. An unconditionally included
                        // feature's presence here never depended on any snapshot to begin with.
                        if (regularFeatures.isNotEmpty()) {
                            val filteredRegularFeatures = filterByOnlyIfUnused(regularFeatures, hotspotActiveNow)

                            if (filteredRegularFeatures.isNotEmpty()) {
                                logDebug("🔒 Disabling regular features (count=${filteredRegularFeatures.size}): ${filteredRegularFeatures.map { it.displayName }}")
                                logDebug("🔒 About to call disableFeatures()...")

                                val regularResults = disableFeatures(filteredRegularFeatures.toSet())

                                logDebug("🔒 disableFeatures() returned ${regularResults.size} results")

                                processResults(regularResults, filteredRegularFeatures, "🔒", "disabled", "Disabled", isLockCycle = true, didEnable = false)
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
                            val currentStatus = getCurrentStatus()

                            // hotspotActiveNow was computed once, above, before the
                            // regularFeatures block's own disableFeatures() root/Shizuku
                            // round-trip just ran - a real, possibly slow call. A hotspot
                            // starting or stopping in that gap would have been invisible to the
                            // Airplane Mode decision below, so it is re-read fresh, right here,
                            // rather than reusing the stale value - only when Airplane Mode is
                            // actually configured, so this never costs an extra shell call for
                            // the common case where it isn't.
                            val hotspotActiveForAirplaneMode = PrivacyFeature.AIRPLANE_MODE in protectionModes &&
                                isHotspotActive()

                            // Read fresh, immediately before switching a radio kill-switch ON. The
                            // regular-features step above can take real time, and an unlock during
                            // it would otherwise put the phone into Airplane Mode while it is back
                            // in the user's hands.
                            if (isSupersededByFresherOppositeAction(lastUnlockAtMillis, lastLockAtMillis)) {
                                logWarning("🛡️ Unlocked while this lock was still running - not enabling protection modes")
                                debugNotifier.notifyActionCancelled("Unlocked during the lock action - protection modes not enabled")
                                return Result.success()
                            }

                            for (mode in protectionModes) {
                                // Airplane Mode is a full radio kill switch - unlike WiFi/Mobile
                                // Data it has no per-feature "only if unused" setting to check, so
                                // this hotspot re-check is the only thing standing between it and
                                // taking a live hotspot down outright. Battery Saver does NOT get
                                // the same treatment: Android documents it as throttling
                                // background activity, not disabling radios - unlike the Airplane
                                // Mode case, this rests on documented platform behaviour, not a
                                // live-verified test (this device's shell lacks the permission to
                                // start a hotspot to test against).
                                if (mode == PrivacyFeature.AIRPLANE_MODE && hotspotActiveForAirplaneMode) {
                                    // The user-facing notification for this happens HERE, at the
                                    // fresh re-check, not at the earlier hotspotActiveNow point
                                    // above - so what the user is told always matches what the
                                    // code actually does, even if hotspot state changed between
                                    // the two checks.
                                    logDebug("🛡️ Skipping Airplane Mode - hotspot is active")
                                    debugNotifier.notifyFeatureSkipped("Airplane Mode", "hotspot is active")
                                    continue
                                }

                                val observedState = currentStatus[mode]
                                val ownedBefore = preferenceManager.getFeatureEnabledByApp(mode)
                                val ownedNow = ownershipAfterObserving(observedState, ownedBefore)
                                if (ownedNow != ownedBefore) {
                                    preferenceManager.setFeatureEnabledByApp(mode, ownedNow)
                                }

                                if (observedState == FeatureState.ENABLED) {
                                    // Already on. Who turned it on is NOT knowable from here, so
                                    // ownership is left exactly as it was: disowning it here is
                                    // what used to make a failed unlock-disable permanent.
                                    if (ownedNow) {
                                        logDebug("🛡️ ${mode.displayName} already enabled and still owned by this app - keeping it owned")
                                    } else {
                                        logDebug("🛡️ ${mode.displayName} already enabled, and not by this app - skipping")
                                        debugNotifier.notifyFeatureSkipped(mode.displayName, "already enabled")
                                    }
                                } else {
                                    val results = enableFeatures(setOf(mode))
                                    val success = results.firstOrNull()?.success == true
                                    if (ownershipAfterEnabling(observedState, success)) {
                                        preferenceManager.setFeatureEnabledByApp(mode, true)
                                        logDebug("🛡️ ${mode.displayName} enabled by app")
                                    } else if (success) {
                                        logWarning("🛡️ ${mode.displayName} was switched on, but its state could not be read first - leaving it unclaimed so the next unlock cannot undo a setting of yours")
                                    }
                                    processResults(results, listOf(mode), "🛡️", "enabled", "Enabled", isLockCycle = true, didEnable = true)
                                }
                            }
                        }
                    }
                }

            } else {
                val featuresToEnable = configManager.getFeaturesToEnableOnUnlock()

                if (featuresToEnable.isNotEmpty()) {
                    logDebug("Enabling features on unlock: ${featuresToEnable.map { it.displayName }}")

                    // The enable-side twin of the lock branch's own lastLockAtMillis reads. The 2
                    // checkpoints below read lastUnlockAtMillis directly, fresh, each time - not
                    // a value frozen into a local once here. A frozen version misses a
                    // re-trigger for the same direction that arrives while THIS job's own sensor
                    // step is still running - see isSupersededByFresherOppositeAction's own
                    // comment above for the full reasoning, symmetric here.

                    // Split into sensor features, protection modes, and regular features. Shares
                    // the same PrivacyFeature.getSensorFeatures()/getSystemModeFeatures() helper
                    // as the lock branch's own split above, rather than hardcoding
                    // CAMERA/MICROPHONE separately here - the same categorisation expressed 2
                    // different ways in the same doWork() would let a future change to what
                    // counts as a sensor feature be applied to only one. Hoisted once for the
                    // same reason as the lock branch's own equivalent hoist above.
                    val sensorFeatureSet = PrivacyFeature.getSensorFeatures()
                    val systemModeFeatureSet = PrivacyFeature.getSystemModeFeatures()
                    val sensorFeatures = featuresToEnable.filter { it in sensorFeatureSet }
                    val protectionModes = featuresToEnable.filter { it in systemModeFeatureSet }
                    val regularFeatures = featuresToEnable.filter {
                        it !in sensorFeatureSet && it !in systemModeFeatureSet
                    }

                    // Enable camera/microphone IMMEDIATELY (no delay), skipping ones already on.
                    // CAMERA_ONLY_IF_NOT_ENABLED / MICROPHONE_ONLY_IF_NOT_ENABLED both default to
                    // true (Constants.kt) - the intent was always to skip a redundant re-enable
                    // here, same as regular features already do below. Reading that preference
                    // matters because every catch-up re-check - the app can only detect a real
                    // lock while its background service is alive, and gets restarted often on
                    // some phones - would otherwise unconditionally re-enable the microphone even
                    // when it was already on.
                    if (sensorFeatures.isNotEmpty()) {
                        // Waits its turn if a lock cycle's own sensor disable is currently
                        // running - see sensorMutex's own comment in the companion object. A real
                        // unlock enqueues this as a SEPARATE WorkManager job from whatever lock
                        // cycle preceded it (different unique work name, so nothing else
                        // serialises them) - without this, the enable could finish first and the
                        // still-running disable could finish after it, leaving camera/mic off
                        // despite a real, confirmed unlock.
                        sensorMutex.withLock {
                          val currentSensorStatus = getCurrentStatus()
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
                          // Symmetric to the lock-side check - if a fresher lock has already
                          // been recorded, that lock's own disable either already ran or is
                          // queued right behind this check, so enabling now would stomp on it
                          // with a stale instruction.
                          if (filteredSensorFeatures.isNotEmpty() &&
                              isSupersededByFresherOppositeAction(lastLockAtMillis, lastUnlockAtMillis)) {
                              logDebug("⚠️ A newer lock was recorded since this unlock - skipping sensor enable")
                              debugNotifier.notifyFeatureSkipped(
                                  filteredSensorFeatures.map { it.displayName }.joinToString(", "),
                                  "locked since this unlock cycle started"
                              )
                          } else if (filteredSensorFeatures.isNotEmpty()) {
                              logDebug("⚡ Enabling sensors immediately (no delay): ${filteredSensorFeatures.map { it.displayName }}")
                              val sensorResults = enableFeatures(filteredSensorFeatures.toSet())
                              processResults(sensorResults, filteredSensorFeatures, "🔓", "enabled", "Re-enabled", isLockCycle = false, didEnable = true)
                          }
                        }
                    }
                    // sensorEnableInProgress cleared HERE, right after the sensor block
                    // concludes - same reasoning as sensorDisableInProgress above, including
                    // marking ownSensorGuardCleared.
                    sensorEnableInProgress = false
                    ownSensorGuardCleared = true

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

                        // The enable-side twin of the lock branch's own lastUnlockAtMillis check
                        // above. With unlockDelaySeconds==0 (a real, ordinary setting, symmetric
                        // to lockDelaySeconds==0) this is the ONLY re-validation this stage gets,
                        // the same reason that check is unconditional on the lock side. Without
                        // it, a lock recorded after this unlock started would never be consulted
                        // before WiFi/Bluetooth/NFC got switched back on and Airplane Mode/
                        // Battery Saver got switched off - on a phone that is actually locked
                        // right now. Compares against lastUnlockAtMillis read fresh here, not a
                        // value frozen at this job's own start - see
                        // isSupersededByFresherOppositeAction's comment above.
                        if (isSupersededByFresherOppositeAction(lastLockAtMillis, lastUnlockAtMillis)) {
                            logWarning("⚠️ Locked since this unlock - cancelling remaining enable actions")
                            debugNotifier.notifyActionCancelled("Locked during unlock cycle - enable cancelled")
                            return Result.success()
                        }

                        // Enable regular features (WiFi, Bluetooth, etc.)
                        if (regularFeatures.isNotEmpty()) {
                            // Filter features based on "only if not already enabled" setting
                            // This prevents connection resets (e.g., WiFi/VPN disconnections)
                            val currentStatus = getCurrentStatus()
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
                                val regularResults = enableFeatures(filteredRegularFeatures.toSet())
                                processResults(regularResults, filteredRegularFeatures, "🔓", "enabled", "Re-enabled", isLockCycle = false, didEnable = true)
                            }
                        }

                        // DISABLE protection modes (Airplane Mode, Battery Saver) - note: DISABLE, not enable!
                        // Check "only if not manually set" preference before disabling
                        if (protectionModes.isNotEmpty()) {
                            logDebug("🛡️ Disabling protection modes on unlock: ${protectionModes.map { it.displayName }}")

                            // The mirror of the lock-side guard. Read fresh, immediately before
                            // switching a radio kill-switch OFF: the enable step above makes real
                            // shell calls, and a re-lock during it would otherwise let this stale
                            // unlock undo protection on a phone that is locked right now.
                            if (isSupersededByFresherOppositeAction(lastLockAtMillis, lastUnlockAtMillis)) {
                                logWarning("🛡️ Locked again while this unlock was still running - leaving protection modes alone")
                                debugNotifier.notifyActionCancelled("Locked during the unlock action - protection modes left on")
                                return Result.success()
                            }

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
                                    val results = disableFeatures(setOf(mode))
                                    // Cleared only when the disable actually worked, mirroring the
                                    // enable side. Clearing it after a FAILED disable told the app
                                    // the user had set this themselves, so every later unlock
                                    // skipped it and it could never be turned off again.
                                    if (allSucceeded(results)) {
                                        preferenceManager.setFeatureEnabledByApp(mode, false)
                                    } else {
                                        logWarning("🛡️ ${mode.displayName} did not turn off - keeping it marked as set by this app so the next unlock tries again")
                                    }
                                    processResults(results, listOf(mode), "🛡️", "disabled", "Disabled", isLockCycle = false, didEnable = false)
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
        } finally {
            // Safety net for every early-return and exception path above (no privilege, global
            // privacy disabled, exempt app, or a genuine exception during setup) that happens
            // BEFORE the normal path's own explicit clear, right after this branch's own sensor
            // block - without this, one of those early paths would leave the flag stuck true
            // forever, since nothing else ever clears it.
            //
            // Clearing BOTH flags unconditionally here is not safe: a lock-direction and
            // unlock-direction doWork() are 2 separate WorkManager jobs (different unique work
            // names) that this app's default configuration can genuinely run concurrently (see
            // sensorMutex's own comment above), so clearing both would wipe the OTHER, still
            // in-flight direction's flag the moment THIS direction reaches an early return -
            // reopening the exact REPLACE-cancels-an-in-flight-worker race this finally exists to
            // close, just cross-direction. Only the ONE flag this invocation itself armed at the
            // top of doWork() is touched, symmetric to that arming.
            //
            // Touching even that ONE flag unconditionally is still not safe. If THIS invocation
            // reaches its own explicit clear (ownSensorGuardCleared = true), then later gets
            // cancelled (e.g. REPLACE-cancelled by a NEWER same-direction trigger while still
            // waiting out the regularFeatures delay), this finally still runs - and by the time
            // it does, that newer invocation may have already armed the SAME flag and be actively
            // mid-sensor-block. Clearing it here would wipe THAT invocation's flag while its own
            // sensor action is genuinely in flight - the same race, reopened one layer later,
            // same-direction instead of cross-direction. Only acts when ownSensorGuardCleared is
            // still false - meaning the normal path never got this far, so no newer invocation
            // could have raced in yet (every REPLACE/enqueue guard still correctly sees this
            // invocation's own flag as true and skips), and the flag really would otherwise be
            // stuck true forever with nothing else to clear it.
            if (!ownSensorGuardCleared) {
                if (isLocking) {
                    sensorDisableInProgress = false
                } else {
                    sensorEnableInProgress = false
                }
            }
        }
    }

    private fun processResults(
        results: List<PrivacyResult>,
        features: List<PrivacyFeature>,
        logIcon: String,
        actionPastTense: String,
        toastPrefix: String,
        isLockCycle: Boolean,
        didEnable: Boolean
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
            debugNotifier.notifyPrivacyAction(isLockCycle, didEnable, successfulFeatures)
        }

        // Notify about failures
        if (failedResults.isNotEmpty()) {
            val failedFeatureNames = failedResults.map { it.feature.displayName }
            debugNotifier.notifyError("Failed to ${if (didEnable) "enable" else "disable"}: ${failedFeatureNames.joinToString(", ")}")
        }
    }
}
