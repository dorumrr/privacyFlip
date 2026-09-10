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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        //
        // #Audit finding 3 (production-readiness audit, 10 Sep): armed at the very start of
        // doWork(), before any setup work (privilege check, object construction) that can itself
        // take real time talking to a real root/Shizuku shell - it used to be armed only once
        // the sensor block itself began, well after that setup, so a second trigger for the SAME
        // real-world lock could see this still false and REPLACE-cancel the worker mid-setup.
        // Cleared explicitly right after the sensor block concludes (see doWork()), and
        // unconditionally in doWork()'s own outer finally as a safety net for every early-return
        // and exception path.
        @Volatile
        var sensorDisableInProgress: Boolean = false
            private set

        // #A2's own #G1 gap, found by an earlier round's adversarial review: the enable-side
        // twin of sensorDisableInProgress above never existed, so nothing stopped a THIRD unlock
        // signal from REPLACE-enqueuing a new NAME_UNLOCK job while a SECOND one was still
        // mid-flight, cancelling that in-flight coroutine outright. Same #G1 pattern, applied
        // symmetrically: every unlock-enqueue call site checks this before REPLACE. Armed and
        // cleared the same way as sensorDisableInProgress above (see #Audit finding 3).
        @Volatile
        var sensorEnableInProgress: Boolean = false
            private set

        // #A2: a lock cycle's sensor DISABLE and a later unlock cycle's sensor ENABLE are 2
        // separate WorkManager jobs (different unique work names, so ExistingWorkPolicy never
        // serialises them against each other) that this app's default WorkManager configuration
        // can genuinely run concurrently. This Mutex makes the two blocks mutually exclusive, so
        // their privileged calls can never interleave with each other. Not the same thing as
        // sensorDisableInProgress above - that flag is a soft signal external callers read
        // before deciding whether to enqueue/cancel; this is real mutual exclusion around the
        // actual privileged disable/enable calls themselves. internal, not private: exposed so
        // PrivacyActionWorkerSensorMutexTest can prove the mutex itself actually serialises 2
        // concurrent coroutines - doWork() as a whole needs a real root/Shizuku shell and isn't
        // otherwise unit-testable, so this is the part of the fix that can be proven directly.
        //
        // Mutual exclusion alone is NOT enough on its own: it stops the two calls overlapping,
        // but says nothing about ORDER. Closed with lastLockAtMillis/lastUnlockAtMillis below:
        // each side checks, right before its own privileged call and still inside the mutex,
        // whether the OPPOSITE action has already been recorded as happening after this cycle
        // started - if so, a fresher instruction has already superseded this one, so it skips
        // instead of acting stale.
        internal val sensorMutex = Mutex()

        // #A2: the lock-side twin of lastUnlockAtMillis below.
        //
        // #Audit finding 2 (production-readiness audit, 10 Sep), deepened by round 2's own
        // adversarial review: this must be captured as close to the real-world trigger event as
        // possible, same reasoning as lastUnlockAtMillis. It used to be captured inside doWork()
        // itself - first only after real setup work (privilege check, object construction), then
        // (round 1's fix) as doWork()'s own first statement - but doWork() starting at all is
        // still gated on real WorkManager dispatch latency (Doze, scheduler load) outside
        // doWork()'s own control, which could take real time on its own. A genuinely LATER real
        // unlock, recorded promptly at its own trigger site, could still end up with a SMALLER
        // raw timestamp than this cycle's own "start", making the supersede check below read
        // backwards and miss it - the exact bug finding 2 already fixed once, reopened one layer
        // out. Now written by util/PendingLockWork.kt's recordLock(), at every real lock-trigger
        // call site, before the job is even enqueued - doWork() only ever reads this, never
        // writes it. Read fresh at each of doWork()'s 4 supersede checkpoints (see
        // isSupersededByFresherOppositeAction's own comment) rather than frozen into a local
        // once - a further ultrareview finding, after this one.
        @Volatile
        var lastLockAtMillis: Long = 0L

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
        //
        // Found by ultrareview to have a second role, missed when lastLockAtMillis's own second
        // role was added: doWork()'s unlock branch also reads this as its own cycle's own
        // reference point, not just the opposite-action value the lock side reads. Same
        // reasoning as lastLockAtMillis: recordUnlock() stamps this at the real trigger site,
        // before the job is even enqueued, so doWork() never needs to (and must not) re-stamp a
        // fresh, WorkManager-dispatch-delayed value of its own. Read fresh at each of doWork()'s
        // 4 supersede checkpoints, never frozen into a local - a further ultrareview finding,
        // after this one (see isSupersededByFresherOppositeAction's own comment).
        @Volatile
        var lastUnlockAtMillis: Long = 0L

        // #Audit finding 7 (production-readiness audit, 10 Sep): the 4 supersede checks below
        // (sensor and regularFeatures/protectionModes stage, for both lock and unlock) used to
        // each write this comparison inline - PrivacyActionWorkerSensorMutexTest's own "ordering"
        // test then hand-copied it a 5th time rather than calling any of the 4 real copies, so
        // PLAN.md's "Verified at runtime" tag for this logic was not actually true: a real
        // regression in any of the 4 inline copies could have passed that test undetected. One
        // shared function closes both problems at once - all 4 real call sites and the test now
        // share the same code, so a fix or a regression can never land in only one of them.
        // internal, not private: same reason sensorMutex above is internal, so the test can call
        // the real thing.
        //
        // Ultrareview finding: the second argument used to be a value frozen once, at the start
        // of THIS job (thisLockCycleStartedAt/thisUnlockCycleStartedAt, each a local val read
        // from lastLockAtMillis/lastUnlockAtMillis exactly once). That misses a re-trigger for
        // the SAME direction that arrives later but never gets its own job, because the
        // REPLACE-guard (sensorDisableInProgress/sensorEnableInProgress) correctly folds it into
        // this already-running one instead of starting a new one - lock, then unlock, then
        // re-lock, all while this job's own sensor step is still running, leaves this job
        // comparing the unlock against the FIRST lock's stale timestamp, never learning about
        // the re-lock at all. Now takes ownLastKnownAtMillis fresh at each call site
        // (lastLockAtMillis/lastUnlockAtMillis read directly, not a frozen local) - always
        // >= any earlier snapshot, since real lock/unlock timestamps only ever move forward, so
        // this can only ever see a MORE complete picture than the frozen version did, never a
        // worse one.
        internal fun isSupersededByFresherOppositeAction(
            oppositeActionAtMillis: Long,
            ownLastKnownAtMillis: Long
        ): Boolean = oppositeActionAtMillis > ownLastKnownAtMillis
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
     * Checks if the screen is currently locked. Used to validate screen state after delays to
     * prevent executing stale actions.
     *
     * #Audit finding 1 (production-readiness audit, 10 Sep): this used to be its own private
     * copy with its own exception fallback - "default to unlocked", the exact bug #D5 fixed in
     * PrivacyMonitorService's separate copy of the same function, left untouched here because
     * nobody had searched for every implementation when fixing #D5. Now the shared
     * util/ScreenLockState.kt function, so there is only one place this can go wrong.
     *
     * @return true if screen is locked, false if unlocked
     */
    private fun isScreenCurrentlyLocked(): Boolean =
        io.github.dorumrr.privacyflip.util.isScreenCurrentlyLocked(applicationContext, TAG)

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
        val isLocking = inputData.getBoolean("is_locking", false)
        // #Audit finding 3: armed as the very first thing doWork() does, before any setup work
        // below (privilege check, object construction) that can itself take real time talking
        // to a real root/Shizuku shell - see sensorDisableInProgress's own comment above for why
        // this matters. Cleared explicitly right after each branch's own sensor block concludes
        // (the normal path, preserving #B2's "still cancellable during the regular-features
        // delay" behaviour), and in the outer finally below for every early-return and exception
        // path, for THIS direction only (see that finally's own comment).
        if (isLocking) {
            sensorDisableInProgress = true
        } else {
            sensorEnableInProgress = true
        }
        // Found by round 2's own adversarial review, after round 1 had already corrected the
        // finally below once: tracks whether THIS invocation's own explicit clear (right after
        // its own sensor block, the normal path) has already run - see that finally's own
        // comment for why this is needed even after round 1's cross-direction fix.
        var ownSensorGuardCleared = false
        try {
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

                    // #C2 Part 1: the 2 checkpoints below read lastLockAtMillis directly, fresh,
                    // each time - not a value frozen into a local once at the top of this
                    // branch. Used to be frozen (thisLockCycleStartedAt); ultrareview found that
                    // missed a re-lock that arrives while THIS job's own sensor step is still
                    // running (lock, then unlock, then re-lock, none of the later 2 getting
                    // their own job because the REPLACE-guard correctly folds them into this
                    // one) - the frozen snapshot never learned about the re-lock, so the
                    // regularFeatures checkpoint compared the unlock against the FIRST lock's
                    // stale timestamp and wrongly concluded it had been superseded. Reading
                    // lastLockAtMillis fresh is always safe: real lock timestamps only ever
                    // move forward (see recordLock()'s own comment), so a fresh read can only
                    // ever be a MORE complete picture than a frozen one, never a worse one.

                    // Categorise by TYPE ONLY, unfiltered (#C2 Part 2). Filtering used to happen
                    // here too, in a shared pass, before the lock delay - narrowing this list
                    // once, then (for regular features) narrowing it again after the delay, only
                    // ever able to subtract. A feature excluded at THIS point (in use, or
                    // hotspot active) could then never be re-added even if the reason stopped
                    // applying before the delay ended. Each group below is now filtered exactly
                    // once, at the latest moment safe for that group: sensors immediately, since
                    // they act with no delay anyway and always have; regular features and
                    // protection modes together after the delay (or immediately if lockDelay==0).
                    // Ultrareview nit: hoisted once, not called again for every feature inside
                    // each filter predicate below (getSensorFeatures()/getSystemModeFeatures()
                    // each allocate their own Set on every call).
                    val sensorFeatureSet = PrivacyFeature.getSensorFeatures()
                    val systemModeFeatureSet = PrivacyFeature.getSystemModeFeatures()
                    val sensorFeatures = featuresToDisable.filter { it in sensorFeatureSet }
                    val protectionModes = featuresToDisable.filter { it in systemModeFeatureSet }
                    val regularFeatures = featuresToDisable.filter {
                        it !in sensorFeatureSet && it !in systemModeFeatureSet
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
                        // #A2: waits its turn if an unlock cycle's own sensor enable (below) is
                        // currently running - see sensorMutex's own comment above. Uncontended in
                        // the common case, so this changes nothing except in the exact race it
                        // exists to close.
                        sensorMutex.withLock {
                          // #A2: this cycle may have waited its turn at the mutex above - re-check
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
                        }
                      }
                    }
                    // #Audit finding 3: sensorDisableInProgress cleared HERE, right after the
                    // sensor block concludes (whether or not there was anything to disable) -
                    // not left true through the delay below, which would silently take away
                    // #B2's own "still cancellable while waiting" behaviour. The outer finally
                    // is a safety net for early returns/exceptions above this point only - marked
                    // done here so it knows not to touch the flag again (see that finally's own
                    // comment, round 2 of this round's adversarial review).
                    sensorDisableInProgress = false
                    ownSensorGuardCleared = true

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
                        // rather than only after the wait. Compares against lastLockAtMillis read
                        // fresh here, not a value frozen at this job's own start - ultrareview's
                        // own finding, see isSupersededByFresherOppositeAction's comment above.
                        if (isSupersededByFresherOppositeAction(lastUnlockAtMillis, lastLockAtMillis)) {
                            logWarning("⚠️ Unlocked since this lock - cancelling remaining disable actions")
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
                            // #A5: Airplane Mode is deliberately NOT named here any more. This
                            // notification fires before the regularFeatures round-trip below, but
                            // Airplane Mode's own decision is now made LATER, with its own fresh
                            // re-check (hotspotActiveForAirplaneMode, in the protection-modes loop)
                            // - naming it here could tell the user it was kept off while the fresh
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

                            // #A5: hotspotActiveNow was computed once, above, before the
                            // regularFeatures block's own disableFeatures() root/Shizuku
                            // round-trip just ran - a real, possibly slow call. A hotspot
                            // starting or stopping in that gap would have been invisible to the
                            // Airplane Mode decision below, which is exactly the check #C1 added
                            // to stop Airplane Mode taking a live hotspot down. Re-read fresh,
                            // right here, rather than reusing the stale value - only when
                            // Airplane Mode is actually configured, so this never costs an extra
                            // shell call for the common case where it isn't.
                            val hotspotActiveForAirplaneMode = PrivacyFeature.AIRPLANE_MODE in protectionModes &&
                                connectionChecker.isHotspotActive()

                            for (mode in protectionModes) {
                                // #C1: Airplane Mode is a full radio kill switch - unlike WiFi/
                                // Mobile Data it has no per-feature "only if unused" setting to
                                // check, so this hotspot re-check is the only thing standing
                                // between it and taking a live hotspot down outright. Battery
                                // Saver does NOT get the same treatment: Android documents it as
                                // throttling background activity, not disabling radios - unlike
                                // the Airplane Mode case, this was not verified live against a
                                // real hotspot (this device's shell lacks the permission to start
                                // one), so it rests on documented platform behaviour, not a test.
                                if (mode == PrivacyFeature.AIRPLANE_MODE && hotspotActiveForAirplaneMode) {
                                    // #A5: the user-facing notification for this now happens HERE,
                                    // at the fresh re-check, not at the earlier hotspotActiveNow
                                    // point above - so what the user is told always matches what
                                    // the code actually does, even if hotspot state changed
                                    // between the two checks.
                                    logDebug("🛡️ Skipping Airplane Mode - hotspot is active")
                                    debugNotifier.notifyFeatureSkipped("Airplane Mode", "hotspot is active")
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

                    // #A2, the enable-side twin of the lock branch's own lastLockAtMillis reads.
                    // The 2 checkpoints below read lastUnlockAtMillis directly, fresh, each time
                    // - not a value frozen into a local once here. Ultrareview found the frozen
                    // version (thisUnlockCycleStartedAt) missed a re-trigger for the same
                    // direction that arrives while THIS job's own sensor step is still running -
                    // see isSupersededByFresherOppositeAction's own comment above for the full
                    // reasoning, symmetric here.

                    // Split into sensor features, protection modes, and regular features.
                    // Ultrareview nit, fixed: used to hardcode CAMERA/MICROPHONE here while the
                    // lock branch's own split (above) used PrivacyFeature.getSensorFeatures() -
                    // the same categorisation expressed 2 different ways in the same doWork(), so
                    // a future change to what counts as a sensor feature could be applied to only
                    // one. Now shares the same helper, hoisted once for the same reason as the
                    // lock branch's own equivalent hoist above.
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
                    // here, same as regular features already do below, but this block never
                    // actually read that preference (#21's real, if minor, finding: every
                    // catch-up re-check - the app can only detect a real lock while its
                    // background service is alive, and gets restarted often on some phones -
                    // was unconditionally re-enabling the microphone even when it was already on).
                    if (sensorFeatures.isNotEmpty()) {
                        // #A2: waits its turn if a lock cycle's own sensor disable is currently
                        // running - see sensorMutex's own comment in the companion object. A real
                        // unlock enqueues this as a SEPARATE WorkManager job from whatever lock
                        // cycle preceded it (different unique work name, so nothing else
                        // serialises them) - without this, the enable could finish first and the
                        // still-running disable could finish after it, leaving camera/mic off
                        // despite a real, confirmed unlock.
                        sensorMutex.withLock {
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
                          // #A2: symmetric to the lock-side check - if a fresher lock has already
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
                              val sensorResults = privacyManager.enableFeatures(filteredSensorFeatures.toSet())
                              processResults(sensorResults, filteredSensorFeatures, "🔓", "enabled", "Re-enabled", isLockAction = false)
                          }
                        }
                    }
                    // #Audit finding 3: sensorEnableInProgress cleared HERE, right after the
                    // sensor block concludes - same reasoning as sensorDisableInProgress above,
                    // including marking ownSensorGuardCleared (round 2 of this round's
                    // adversarial review).
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

                        // #Audit finding 4 (production-readiness audit, 10 Sep): the enable-side
                        // twin of the lock branch's own lastUnlockAtMillis check above - this
                        // simply never existed before. With unlockDelaySeconds==0 (a real,
                        // ordinary setting, symmetric to lockDelaySeconds==0) this is the ONLY
                        // re-validation this stage gets, the same reason that check is
                        // unconditional on the lock side. Without it, a lock recorded after this
                        // unlock started was never consulted before WiFi/Bluetooth/NFC got
                        // switched back on and Airplane Mode/Battery Saver got switched off - on
                        // a phone that is actually locked right now. Compares against
                        // lastUnlockAtMillis read fresh here, not a value frozen at this job's
                        // own start - ultrareview's finding, see
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
        } finally {
            // #Audit finding 3: safety net for every early-return and exception path above (no
            // privilege, global privacy disabled, exempt app, or a genuine exception during
            // setup) that happens BEFORE the normal path's own explicit clear, right after this
            // branch's own sensor block - without this, one of those early paths would leave the
            // flag stuck true forever, since nothing else ever clears it.
            //
            // Found by round 1 of this round's adversarial review: this used to clear BOTH flags
            // unconditionally - but a lock-direction and unlock-direction doWork() are 2 separate
            // WorkManager jobs (different unique work names) that this app's default
            // configuration can genuinely run concurrently (see sensorMutex's own comment above).
            // Clearing both here would wipe the OTHER, still in-flight direction's flag the
            // moment THIS direction reaches an early return - reopening the exact
            // REPLACE-cancels-an-in-flight-worker race this finally exists to close, just
            // cross-direction. Fixed to only ever touch the ONE flag this invocation itself armed
            // at the top of doWork(), symmetric to that arming.
            //
            // Found by round 2: touching even that ONE flag unconditionally was still not safe.
            // If THIS invocation reaches its own explicit clear (ownSensorGuardCleared = true),
            // then later gets cancelled (e.g. REPLACE-cancelled by a NEWER same-direction trigger
            // while still waiting out the regularFeatures delay), this finally still runs - and
            // by the time it does, that newer invocation may have already armed the SAME flag
            // and be actively mid-sensor-block. Clearing it here would wipe THAT invocation's
            // flag while its own sensor action is genuinely in flight - the same #G1 race,
            // reopened one layer later, same-direction instead of cross-direction. Only acts when
            // ownSensorGuardCleared is still false - meaning the normal path never got this far,
            // so no newer invocation could have raced in yet (every REPLACE/enqueue guard still
            // correctly sees this invocation's own flag as true and skips), and the flag really
            // would otherwise be stuck true forever with nothing else to clear it.
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
