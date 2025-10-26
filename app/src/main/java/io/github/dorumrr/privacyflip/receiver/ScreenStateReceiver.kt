package io.github.dorumrr.privacyflip.receiver

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker

class ScreenStateReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ScreenStateReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Screen state changed: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                // Check if device is already locked (keyguard engaged)
                val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: true

                if (!isKeyguardLocked) {
                    Log.i(TAG, "✅ Screen OFF but device NOT locked yet - triggering privacy actions")
                    triggerPrivacyAction(context, isLocking = true, isDeviceLocked = false, reason = "Screen Off (Unlocked)")
                } else {
                    Log.w(TAG, "⚠️ Screen OFF and device ALREADY locked - camera/mic cannot be disabled")
                    triggerPrivacyAction(context, isLocking = true, isDeviceLocked = true, reason = "Screen Off (Locked)")
                }
            }

            Intent.ACTION_USER_PRESENT -> {
                Log.i(TAG, "🔓 Screen UNLOCKED (user authenticated) - triggering privacy actions")
                triggerPrivacyAction(context, isLocking = false, isDeviceLocked = false, reason = "Screen Unlock")
            }

            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "💡 Screen turned ON (but may still be locked - waiting for USER_PRESENT)")
            }

            else -> {
                Log.w(TAG, "⚠️ Unexpected intent action: ${intent.action}")
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

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.i(TAG, "Privacy action work enqueued for ${if (isLocking) "lock" else "unlock"} (deviceLocked=$isDeviceLocked)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger privacy action", e)
        }
    }
}
