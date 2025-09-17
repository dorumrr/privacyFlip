package io.github.dorumrr.privacyflip.receiver

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
                Log.i(TAG, "📱 Screen LOCKED - triggering privacy actions")
                triggerPrivacyAction(context, isLocking = true, reason = "Screen Lock")
            }

            Intent.ACTION_USER_PRESENT -> {
                Log.i(TAG, "🔓 Screen UNLOCKED (user authenticated) - triggering privacy actions")
                triggerPrivacyAction(context, isLocking = false, reason = "Screen Unlock")
            }

            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "💡 Screen turned ON (but may still be locked - waiting for USER_PRESENT)")
            }

            else -> {
                Log.w(TAG, "⚠️ Unexpected intent action: ${intent.action}")
            }
        }
    }
    
    private fun triggerPrivacyAction(context: Context, isLocking: Boolean, reason: String) {
        try {
            val workRequest = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
                .setInputData(
                    workDataOf(
                        "is_locking" to isLocking,
                        "trigger" to "screen_state",
                        "reason" to reason
                    )
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.i(TAG, "Privacy action work enqueued for ${if (isLocking) "lock" else "unlock"}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger privacy action", e)
        }
    }
}
