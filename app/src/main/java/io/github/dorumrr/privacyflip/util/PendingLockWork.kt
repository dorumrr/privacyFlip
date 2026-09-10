package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.WorkManager
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker
import java.util.concurrent.Executor

/**
 * Cancels pending lock- or unlock-triggered privacy work, and records that an unlock happened.
 * Shared by every place that can detect a genuine unlock - ScreenStateReceiver's own
 * ACTION_USER_PRESENT and ACTION_SCREEN_ON handlers (#B2), PrivacyAccessibilityService's own
 * window-transition detection (#C3), and PrivacyMonitorService's restart catch-up (#C3) - so
 * those call sites can never drift into cancelling different work, logging differently about
 * doing it, or (#C2 Part 1) recording the unlock timestamp differently.
 *
 * #Audit finding 5 (production-readiness audit, 10 Sep): cancel() used to only ever target
 * NAME_LOCK - there was no way to cancel a stale NAME_UNLOCK job at all, so a service restart
 * that found the device newly LOCKED correctly enqueued a fresh lock-side job but left any
 * already-pending unlock-side job to run to completion regardless, re-enabling features on a
 * phone the user had since locked. Parameterised so PrivacyMonitorService's restart catch-up can
 * cancel the opposite direction too, symmetric to the lock side.
 */
object PendingLockWork {
    fun cancel(context: Context, tag: String, workName: String) {
        try {
            val operation = WorkManager.getInstance(context).cancelUniqueWork(workName)
            Log.i(tag, "🚫 Cancelling pending work ($workName) due to a confirmed opposite action")
            DebugLogHelper.getInstance(context).i(tag, "🚫 Cancelling pending work ($workName) due to a confirmed opposite action")
            // #A4 (PLAN.md, confirmed 10 Sep by /phi:debug): cancelUniqueWork()'s returned
            // Operation used to be discarded - it resolves asynchronously, and a genuine failure
            // (WorkManager's own docs name a full internal database as one real cause) could
            // never be detected or logged, leaving a stale lock/disable action free to still fire
            // later with no error shown anywhere. Callers here (broadcast receivers, an
            // accessibility event handler) are not coroutines, so this listens for the real
            // outcome asynchronously rather than blocking to await it - fire-and-forget for the
            // caller, but the actual result is no longer silently dropped. Runs the listener
            // inline (Runnable::run): logging is thread-safe and cheap, no need to hop threads.
            operation.result.addListener({
                try {
                    operation.result.get()
                    Log.i(tag, "✅ Pending work ($workName) cancel confirmed by WorkManager")
                    DebugLogHelper.getInstance(context).i(tag, "✅ Pending work ($workName) cancel confirmed by WorkManager")
                } catch (e: Exception) {
                    Log.e(tag, "❌ Pending work ($workName) cancel FAILED - a stale action may still fire later", e)
                    DebugLogHelper.getInstance(context).e(tag, "❌ Pending work ($workName) cancel FAILED - a stale action may still fire later", e)
                }
            }, Executor { it.run() })
        } catch (e: Exception) {
            Log.e(tag, "Failed to cancel pending work ($workName)", e)
            DebugLogHelper.getInstance(context).e(tag, "Failed to cancel pending work ($workName)", e)
        }
    }

    /**
     * Records that an unlock was just confirmed (#C2 Part 1). Call this UNCONDITIONALLY, even
     * when [cancel] above is skipped because a sensor disable is mid-flight
     * (PrivacyActionWorker.sensorDisableInProgress, #G1) - a plain volatile write can never
     * interrupt anything the way an external WorkManager cancel can, so it needs none of that
     * guard, and it is what lets that same lock cycle's own later checkpoint notice the unlock
     * it could not safely cancel for at the time.
     */
    fun recordUnlock() {
        PrivacyActionWorker.lastUnlockAtMillis = SystemClock.elapsedRealtime()
    }

    /**
     * Records that a lock was just detected - the lock-side twin of [recordUnlock], added by
     * this round's own adversarial review after it found [recordUnlock]'s existing "instant, at
     * the real trigger site" pattern had never been extended to the lock side.
     * PrivacyActionWorker.doWork() used to stamp lastLockAtMillis itself, as its own first
     * statement - closer to the real event than the old, even-later stamp, but still exposed to
     * real WorkManager dispatch latency (Doze, scheduler load) between the trigger firing and
     * doWork() actually starting to run. Call this UNCONDITIONALLY, before the enqueue, at every
     * real lock-trigger call site - same reasoning as [recordUnlock]: a plain volatile write can
     * never interrupt anything mid-flight, so it needs no sensorDisableInProgress guard either.
     */
    fun recordLock() {
        PrivacyActionWorker.lastLockAtMillis = SystemClock.elapsedRealtime()
    }
}
