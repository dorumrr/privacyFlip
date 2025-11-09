package io.github.dorumrr.privacyflip.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.dorumrr.privacyflip.MainActivity
import io.github.dorumrr.privacyflip.R
import io.github.dorumrr.privacyflip.receiver.ScreenStateReceiver
import io.github.dorumrr.privacyflip.util.Constants
import io.github.dorumrr.privacyflip.util.ScreenStateReceiverManager
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker

class PrivacyMonitorService : Service() {

    companion object {
        private const val TAG = "privacyFlip-PrivacyMonitorService"

        // Track service running state
        @Volatile
        private var isServiceRunning = false

        fun isRunning(): Boolean = isServiceRunning

        fun start(context: Context) {
            try {
                val intent = Intent(context, PrivacyMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PrivacyMonitorService::class.java)
            context.stopService(intent)
        }
    }
    
    private var screenStateReceiver: ScreenStateReceiver? = null
    
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.i(TAG, "🚀 Privacy Monitor Service created")

        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Constants.ServiceNotification.NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Constants.ServiceNotification.NOTIFICATION_ID, createNotification())
            }
            registerScreenStateReceiver()

            // Apply initial privacy state based on current screen lock status
            applyInitialPrivacyState()

            Log.i(TAG, "✅ Privacy Monitor Service initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Privacy Monitor Service", e)
            isServiceRunning = false
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "🔄 Privacy Monitor Service started (flags=$flags, startId=$startId)")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.d(TAG, "Privacy Monitor Service destroyed")

        unregisterScreenStateReceiver()
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.ServiceNotification.CHANNEL_ID,
                "Privacy Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors screen state for privacy actions"
                setShowBadge(false)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, Constants.ServiceNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_privacy_shield)
            .setContentTitle("Privacy Flip Active")
            .setContentText("Monitoring screen state for privacy actions")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun registerScreenStateReceiver() {
        screenStateReceiver = ScreenStateReceiverManager.registerReceiver(this, TAG)
    }

    private fun unregisterScreenStateReceiver() {
        ScreenStateReceiverManager.unregisterReceiver(this, screenStateReceiver, TAG)
        screenStateReceiver = null
    }

    /**
     * Applies initial privacy state based on current screen lock status.
     * This is crucial for boot scenarios where the service starts but doesn't know
     * the current screen state and needs to apply appropriate privacy actions.
     */
    private fun applyInitialPrivacyState() {
        try {
            // Delay slightly to ensure system services are ready
            Handler(Looper.getMainLooper()).postDelayed({
                val isScreenLocked = isScreenCurrentlyLocked()
                val reason = "Service Initialization"

                Log.i(TAG, "🔍 Checking initial screen state: ${if (isScreenLocked) "LOCKED" else "UNLOCKED"}")

                // Apply appropriate privacy actions based on current screen state
                triggerInitialPrivacyAction(!isScreenLocked, reason)

            }, 1000L) // 1 second delay to ensure system is ready

        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply initial privacy state", e)
        }
    }

    /**
     * Checks if the screen is currently locked using KeyguardManager and PowerManager.
     * Returns true if screen is locked, false if unlocked.
     */
    private fun isScreenCurrentlyLocked(): Boolean {
        return try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            val isKeyguardLocked = keyguardManager.isKeyguardLocked
            val isScreenOn = powerManager.isInteractive

            Log.d(TAG, "Screen state check: keyguardLocked=$isKeyguardLocked, screenOn=$isScreenOn")

            // Screen is considered locked if keyguard is active OR screen is off
            isKeyguardLocked || !isScreenOn

        } catch (e: Exception) {
            Log.e(TAG, "Error checking screen lock state", e)
            // Default to unlocked if we can't determine state
            false
        }
    }

    /**
     * Triggers privacy action based on initial screen state.
     * Uses the same WorkManager pattern as ScreenStateReceiver for consistency.
     */
    private fun triggerInitialPrivacyAction(isUnlocking: Boolean, reason: String) {
        try {
            // Check if device is currently locked to pass correct flag to worker
            val isDeviceLocked = isScreenCurrentlyLocked()

            val workRequest = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
                .setInputData(
                    workDataOf(
                        "is_locking" to !isUnlocking,
                        "is_device_locked" to isDeviceLocked,
                        "trigger" to "service_init",
                        "reason" to reason
                    )
                )
                .build()

            WorkManager.getInstance(this).enqueue(workRequest)
            Log.i(TAG, "🔄 Initial privacy action enqueued: ${if (isUnlocking) "unlock" else "lock"} actions (deviceLocked=$isDeviceLocked)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger initial privacy action", e)
        }
    }
}
