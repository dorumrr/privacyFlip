package io.github.dorumrr.privacyflip.service

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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.dorumrr.privacyflip.MainActivity
import io.github.dorumrr.privacyflip.R
import io.github.dorumrr.privacyflip.receiver.ScreenStateReceiver
import io.github.dorumrr.privacyflip.util.Constants
import io.github.dorumrr.privacyflip.util.ScreenStateReceiverManager

class PrivacyMonitorService : Service() {
    
    companion object {
        private const val TAG = "PrivacyMonitorService"

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
        Log.i(TAG, "🚀 Privacy Monitor Service created")

        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Constants.ServiceNotification.NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Constants.ServiceNotification.NOTIFICATION_ID, createNotification())
            }
            registerScreenStateReceiver()
            Log.i(TAG, "✅ Privacy Monitor Service initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Privacy Monitor Service", e)
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "🔄 Privacy Monitor Service started (flags=$flags, startId=$startId)")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
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
            .setContentTitle("PrivacyFlip Active")
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
}
