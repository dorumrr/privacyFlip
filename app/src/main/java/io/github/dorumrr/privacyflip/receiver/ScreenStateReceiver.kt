package io.github.dorumrr.privacyflip.receiver

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.dorumrr.privacyflip.util.DebugLogHelper
import io.github.dorumrr.privacyflip.util.PendingLockWork
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker

class ScreenStateReceiver : BroadcastReceiver() {

    private fun logDebug(context: Context, message: String) {
        Log.i(TAG, message)
        DebugLogHelper.getInstance(context).i(TAG, message)
    }

    private fun logWarning(context: Context, message: String) {
        Log.w(TAG, message)
        DebugLogHelper.getInstance(context).w(TAG, message)
    }

    private fun logError(context: Context, message: String, e: Exception? = null) {
        Log.e(TAG, message, e)
        DebugLogHelper.getInstance(context).e(TAG, message, e)
    }

    override fun onReceive(context: Context, intent: Intent) {
        logDebug(context, "Screen state changed: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                // #Audit finding 2, round 2 (production-readiness audit, 10 Sep - deepened by
                // this round's own adversarial review): recorded as close to the real trigger
                // event as possible, before the enqueue this branch leads to - the lock-side
                // twin of recordUnlock() below, same reasoning: a plain volatile write can never
                // interrupt anything mid-flight, so it needs no guard, and doWork() itself can no
                // longer be trusted to stamp this promptly enough (real WorkManager dispatch
                // latency, outside doWork()'s own control, sits between this line and doWork()
                // actually starting).
                PendingLockWork.recordLock()
                // Check if device is already locked (keyguard engaged)
                val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: true

                if (!isKeyguardLocked) {
                    logDebug(context, "✅ Screen OFF but device NOT locked yet - triggering privacy actions")
                    triggerPrivacyAction(context, isLocking = true, isDeviceLocked = false, reason = "Screen Off (Unlocked)")
                } else {
                    logWarning(context, "⚠️ Screen OFF and device ALREADY locked - camera/mic cannot be disabled")
                    triggerPrivacyAction(context, isLocking = true, isDeviceLocked = true, reason = "Screen Off (Locked)")
                }
            }

            Intent.ACTION_USER_PRESENT -> {
                logDebug(context, "🔓 Screen UNLOCKED (user authenticated) - triggering privacy actions")
                // Record the unlock unconditionally, before anything else (#C2 Part 1) - even
                // when the cancel below is skipped, so a lock cycle that could not safely be
                // cancelled at this moment still finds out about this unlock at its own later
                // checkpoint. A plain timestamp write cannot interrupt anything mid-flight, so it
                // needs none of the guard the cancel itself needs.
                PendingLockWork.recordUnlock()
                // Cancel any still-pending lock-side work (#B2). Unlike ACTION_SCREEN_ON, this
                // broadcast has no "still locked" ambiguity to guard against - Android only
                // sends it on a genuine, confirmed unlock - so whatever the lock delay was
                // waiting to disable should never fire after this. Without this, a pending
                // disable only gets caught later by accident (the post-delay re-checks further
                // down this same file), which a lockDelay of 0 skips entirely, and which never
                // covers the camera/mic path, which runs with no delay at all.
                // Skipped only while a sensor disable is actively in flight (#G1) - cancelling
                // that would interrupt it mid-command with sensors left on and no error shown
                // anywhere, the same race the other producers already guard against before
                // they'd REPLACE this same unique work.
                if (!PrivacyActionWorker.sensorDisableInProgress) {
                    PendingLockWork.cancel(context, TAG, WORK_NAME_LOCK)
                }
                triggerPrivacyAction(context, isLocking = false, isDeviceLocked = false, reason = "Screen Unlock")
            }

            Intent.ACTION_SCREEN_ON -> {
                // Only cancel the pending lock work if the phone is genuinely back in
                // use (no keyguard, or none configured). A screen that merely wakes
                // while still locked - a notification, raise-to-wake, a glance at the
                // lock screen - is not a real return to use, and used to cancel the
                // whole pending disable outright even though the phone was still
                // locked the entire time. On an already-locked phone the lock delay
                // is now always honoured (#30), so this blip window got a lot easier
                // to hit than when the already-locked case used to skip the wait.
                val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                // Same conservative default as the SCREEN_OFF handler above: if the
                // lock state genuinely can't be read, assume still locked rather than
                // cancel protection on a guess.
                val isStillLocked = keyguardManager?.isKeyguardLocked ?: true

                if (isStillLocked) {
                    logDebug(context, "💡 Screen turned ON but still locked - keeping pending lock work")
                } else {
                    logDebug(context, "💡 Screen turned ON and not locked - cancelling pending lock work")
                    // Recorded unconditionally (#C2 Part 1), same reason as ACTION_USER_PRESENT
                    // above - this branch only reaches here once isStillLocked is already
                    // confirmed false, so it is always a genuine unlock signal.
                    PendingLockWork.recordUnlock()
                    // Found while wiring up recordUnlock() here: unlike every other cancel call
                    // site in this app, this one had no sensorDisableInProgress guard (#G1) - a
                    // screen-on this fast could in principle land while this same lock cycle's
                    // own sensor block is still running, and cancelUniqueWork() can interrupt a
                    // running coroutine worker the same way an ExistingWorkPolicy.REPLACE can.
                    // Matches the guard ACTION_USER_PRESENT, PrivacyAccessibilityService and
                    // PrivacyMonitorService's restart catch-up all already had.
                    if (!PrivacyActionWorker.sensorDisableInProgress) {
                        PendingLockWork.cancel(context, TAG, WORK_NAME_LOCK)
                    }
                    // #A1 (PLAN.md, confirmed 10 Sep by /phi:debug): this branch used to only
                    // cancel, never re-enable - unlike the other 3 unlock-detection paths. That
                    // left the exact scenario this branch exists for (screen off during Android's
                    // own pre-keyguard grace period, then back on before the keyguard actually
                    // engaged) with no way to give back whatever the immediate sensor block had
                    // already disabled, since a real unlock was never technically dismissed and
                    // ACTION_USER_PRESENT never fires either. Mirrors ACTION_USER_PRESENT's own
                    // call below, matching what every other confirmed-unlock path already does.
                    triggerPrivacyAction(context, isLocking = false, isDeviceLocked = false, reason = "Screen On (Not Locked)")
                }
            }

            else -> {
                logWarning(context, "⚠️ Unexpected intent action: ${intent.action}")
            }
        }
    }
    
    private fun triggerPrivacyAction(context: Context, isLocking: Boolean, isDeviceLocked: Boolean, reason: String) {
        // If the accessibility-triggered path is mid-way through disabling the camera/mic for
        // THIS same lock, don't REPLACE it - that would cancel a disable that's already running,
        // silently, with sensors left on (#G1). The regular-features delay this trigger would
        // otherwise start still gets a chance to run once the in-flight worker completes, since
        // that worker (enqueued with isDeviceLocked=false at the time) carries its own delay
        // through to the same regular-features step.
        if (isLocking && PrivacyActionWorker.sensorDisableInProgress) {
            logDebug(context, "⏳ Sensor disable already in progress - not replacing it (reason: $reason)")
            return
        }
        // #A2's own #G1 gap: the enable-side twin of the guard above. A 3rd unlock signal
        // REPLACE-enqueuing while a 2nd is still mid-way through enabling camera/mic would
        // cancel that in-flight coroutine outright, leaving sensors off after a real unlock.
        if (!isLocking && PrivacyActionWorker.sensorEnableInProgress) {
            logDebug(context, "⏳ Sensor enable already in progress - not replacing it (reason: $reason)")
            return
        }
        try {
            val workRequest = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
                .setInputData(
                    workDataOf(
                        "is_locking" to isLocking,
                        "is_device_locked" to isDeviceLocked,
                        "trigger" to "screen_state",
                        "reason" to reason
                    )
                )
                .build()

            // Use unique work names to prevent multiple workers from running simultaneously
            // REPLACE policy cancels any existing work with the same name
            val workName = if (isLocking) WORK_NAME_LOCK else WORK_NAME_UNLOCK
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            logDebug(context, "Privacy action work enqueued (unique: $workName) for ${if (isLocking) "lock" else "unlock"} (deviceLocked=$isDeviceLocked)")

        } catch (e: Exception) {
            logError(context, "Failed to trigger privacy action", e)
        }
    }

    companion object {
        private const val TAG = "privacyFlip-ScreenStateReceiver"
        private val WORK_NAME_LOCK = io.github.dorumrr.privacyflip.util.Constants.Work.NAME_LOCK
        private val WORK_NAME_UNLOCK = io.github.dorumrr.privacyflip.util.Constants.Work.NAME_UNLOCK
    }
}
