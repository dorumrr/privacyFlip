package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.WorkManager
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker

/**
 * Cancels the pending lock-triggered privacy action, and records that an unlock happened.
 * Shared by every place that can detect a genuine unlock - ScreenStateReceiver's own
 * ACTION_USER_PRESENT and ACTION_SCREEN_ON handlers (#B2), PrivacyAccessibilityService's own
 * window-transition detection (#C3), and PrivacyMonitorService's restart catch-up (#C3) - so the
 * 4 call sites can never drift into cancelling different work, logging differently about doing
 * it, or (#C2 Part 1) recording the unlock timestamp differently.
 */
object PendingLockWork {
    fun cancel(context: Context, tag: String) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(Constants.Work.NAME_LOCK)
            Log.i(tag, "🚫 Cancelled pending lock work due to a confirmed unlock")
            DebugLogHelper.getInstance(context).i(tag, "🚫 Cancelled pending lock work due to a confirmed unlock")
        } catch (e: Exception) {
            Log.e(tag, "Failed to cancel pending lock work", e)
            DebugLogHelper.getInstance(context).e(tag, "Failed to cancel pending lock work", e)
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
}
