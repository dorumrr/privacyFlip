package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.util.Log
import androidx.work.WorkManager

/**
 * Cancels the pending lock-triggered privacy action. Shared by every place that can detect a
 * genuine unlock - ScreenStateReceiver's own ACTION_USER_PRESENT handler (#B2),
 * PrivacyAccessibilityService's own window-transition detection (#C3), and
 * PrivacyMonitorService's restart catch-up (#C3) - so the 3 call sites can never drift into
 * cancelling different work, or logging differently about doing it.
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
}
