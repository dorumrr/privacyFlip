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
 * ACTION_USER_PRESENT and ACTION_SCREEN_ON handlers, PrivacyAccessibilityService's own
 * window-transition detection, and PrivacyMonitorService's restart catch-up - so those call
 * sites can never drift into cancelling different work, logging differently about doing it, or
 * recording the unlock timestamp differently.
 *
 * Parameterised by work name rather than fixed to NAME_LOCK: a restart catch-up that finds the
 * device newly LOCKED must be able to cancel a stale, already-pending NAME_UNLOCK job too, or it
 * runs to completion regardless and re-enables features on a phone the user has since locked.
 */
object PendingLockWork {
    // cancel() below would otherwise hand-write every log call twice (Log.X, then the identical
    // message again to DebugLogHelper.X) at each of the 4 log statements inside its own body -
    // the same dual-write pattern PrivacyActionWorker/PrivacyMonitorService already collapse
    // into their own logDebug/logWarning/logError helpers. One shared pair here does the same
    // for this object's own single caller-supplied tag, so a message can only ever drift
    // between the two sinks if this one place gets it wrong, not up to 4 places inside cancel().
    private fun logInfo(context: Context, tag: String, message: String) {
        Log.i(tag, message)
        DebugLogHelper.getInstance(context).i(tag, message)
    }

    private fun logError(context: Context, tag: String, message: String, e: Exception? = null) {
        Log.e(tag, message, e)
        DebugLogHelper.getInstance(context).e(tag, message, e)
    }

    fun cancel(context: Context, tag: String, workName: String) {
        try {
            val operation = WorkManager.getInstance(context).cancelUniqueWork(workName)
            logInfo(context, tag, "🚫 Cancelling pending work ($workName) due to a confirmed opposite action")
            // cancelUniqueWork()'s returned Operation would otherwise be discarded, silently
            // assuming success at the call site - PendingLockWorkTest's "cancel reads the real
            // Operation outcome..." test proves this listener genuinely reads the resolved
            // result instead. Whether a genuine Operation FAILURE reaches the log below is not
            // provable by that test (Robolectric's test WorkManager cannot force a real
            // cancelUniqueWork() failure) and stays Verified in code only. None of this object's
            // callers (see the class KDoc above) are coroutines, so this listens asynchronously
            // rather than blocking to await it. Runs inline (Runnable::run): logging is
            // thread-safe and cheap, no need to hop threads.
            operation.result.addListener({
                try {
                    operation.result.get()
                    logInfo(context, tag, "✅ Pending work ($workName) cancel confirmed by WorkManager")
                } catch (e: Exception) {
                    logError(context, tag, "❌ Pending work ($workName) cancel FAILED - a stale action may still fire later", e)
                }
            }, Executor { it.run() })
        } catch (e: Exception) {
            logError(context, tag, "Failed to cancel pending work ($workName)", e)
        }
    }

    /**
     * Records that an unlock was just confirmed. Call this UNCONDITIONALLY, even when [cancel]
     * above is skipped because a sensor disable is mid-flight
     * (PrivacyActionWorker.sensorDisableInProgress) - a plain volatile write can never interrupt
     * anything the way an external WorkManager cancel can, so it needs none of that guard, and it
     * is what lets that same lock cycle's own later checkpoint notice the unlock it could not
     * safely cancel for at the time.
     */
    fun recordUnlock() {
        PrivacyActionWorker.lastUnlockAtMillis = SystemClock.elapsedRealtime()
    }

    /**
     * Records that a lock was just detected - the lock-side twin of [recordUnlock].
     * PrivacyActionWorker.doWork() used to stamp lastLockAtMillis itself, as its own first
     * statement - closer to the real event than an even-later stamp, but still exposed to real
     * WorkManager dispatch latency (Doze, scheduler load) between the trigger firing and
     * doWork() actually starting to run. Call this UNCONDITIONALLY, before the enqueue, at every
     * real lock-trigger call site - same reasoning as [recordUnlock]: a plain volatile write can
     * never interrupt anything mid-flight, so it needs no sensorDisableInProgress guard either.
     */
    fun recordLock() {
        PrivacyActionWorker.lastLockAtMillis = SystemClock.elapsedRealtime()
    }
}
