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
                triggerPrivacyAction(context, isLocking = false, isDeviceLocked = false, reason = "Screen Unlock")
            }

            Intent.ACTION_SCREEN_ON -> {
                logDebug(context, "💡 Screen turned ON (but may still be locked - waiting for USER_PRESENT)")
                // Cancel any pending lock workers since screen is back on
                cancelPendingLockWork(context)
            }

            else -> {
                logWarning(context, "⚠️ Unexpected intent action: ${intent.action}")
            }
        }
    }
    
    private fun triggerPrivacyAction(context: Context, isLocking: Boolean, isDeviceLocked: Boolean, reason: String) {
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

    private fun cancelPendingLockWork(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_LOCK)
            logDebug(context, "🚫 Cancelled pending lock work due to screen turning back on")
        } catch (e: Exception) {
            logError(context, "Failed to cancel pending lock work", e)
        }
    }

    companion object {
        private const val TAG = "privacyFlip-ScreenStateReceiver"
        private const val WORK_NAME_LOCK = "privacy_action_lock"
        private const val WORK_NAME_UNLOCK = "privacy_action_unlock"
    }
}
