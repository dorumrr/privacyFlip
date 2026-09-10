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
import androidx.work.ExistingWorkPolicy
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
            val isScreenLocked = isScreenCurrentlyLocked()
            val reason = "Service Initialization"

            Log.i(TAG, "🔍 Checking initial screen state: ${if (isScreenLocked) "LOCKED" else "UNLOCKED"}")

            // Apply appropriate privacy actions based on current screen state
            triggerInitialPrivacyAction(!isScreenLocked, reason)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply initial privacy state", e)
        }
    }

    /**
     * Checks if the screen is currently locked. #Audit finding 1 (10 Sep): now the shared
     * util/ScreenLockState.kt function - this used to be its own private copy, which meant #D5's
     * fail-closed fix only ever covered this one, not PrivacyActionWorker's separate copy.
     * PrivacyMonitorServiceTest proves this stays fail-closed.
     */
    private fun isScreenCurrentlyLocked(): Boolean =
        io.github.dorumrr.privacyflip.util.isScreenCurrentlyLocked(this, TAG)

    /**
     * Triggers privacy action based on initial screen state.
     * Uses unique work names to prevent conflicts with screen state receiver.
     */
    private fun triggerInitialPrivacyAction(isUnlocking: Boolean, reason: String) {
        try {
            val isLocking = !isUnlocking

            // #Audit finding 2, round 2 (production-readiness audit, 10 Sep - deepened by this
            // round's own adversarial review): recorded unconditionally, before any early return
            // below - the lock-side twin of recordUnlock() further down, same reasoning: a plain
            // volatile write can never interrupt anything mid-flight, so it needs no guard.
            if (isLocking) {
                io.github.dorumrr.privacyflip.util.PendingLockWork.recordLock()
            }

            // #Audit finding 5 (production-readiness audit, 10 Sep), reordered by round 2's own
            // adversarial review: this cancel is independently guarded by sensorEnableInProgress
            // and can never interrupt anything in-flight - cancelling a sensor enable that's
            // actively running would interrupt it mid-command with sensors left disabled and no
            // error shown anywhere, which is exactly what that guard prevents. It must run
            // BEFORE the sensorDisableInProgress early-return just below, not after: the first
            // draft put it after, so that early return (whenever this restart catch-up finds the
            // device locked while an earlier disable is already running - e.g. the service was
            // killed and respawned via START_STICKY without the process dying) skipped this
            // cancel entirely, reopening the exact stale-NAME_UNLOCK gap finding 5 exists to
            // close, just for this one case. Mirrors how the isUnlocking branch below already
            // orders its own independently-safe recordUnlock()/cancel() before its own
            // sensorEnableInProgress-guarded early return.
            if (isLocking && !PrivacyActionWorker.sensorEnableInProgress) {
                io.github.dorumrr.privacyflip.util.PendingLockWork.cancel(
                    this, TAG, io.github.dorumrr.privacyflip.util.Constants.Work.NAME_UNLOCK
                )
            }

            if (isLocking && PrivacyActionWorker.sensorDisableInProgress) {
                // Another trigger is already disabling sensors for this lock - REPLACE would
                // cancel it mid-flight (#G1).
                Log.d(TAG, "⏳ Sensor disable already in progress - not replacing it")
                return
            }

            // #C3: this restart catch-up finds the device unlocked but, unlike
            // ScreenStateReceiver's own ACTION_USER_PRESENT handler (#B2), never cancelled a
            // still-pending lock-side job - only ever REPLACEs the unlock-side one below, a
            // different unique work name. Whenever this service was dead (the only time this
            // catch-up path runs at all), that pending disable had no other way to be
            // cancelled: ScreenStateReceiver is only registered while this service is alive.
            if (isUnlocking) {
                // Recorded unconditionally (#C2 Part 1), same reason as the other 3 call sites:
                // a plain timestamp write cannot interrupt anything mid-flight, so it needs none
                // of the sensorDisableInProgress guard the cancel itself needs just below.
                io.github.dorumrr.privacyflip.util.PendingLockWork.recordUnlock()
                if (!PrivacyActionWorker.sensorDisableInProgress) {
                    io.github.dorumrr.privacyflip.util.PendingLockWork.cancel(
                        this, TAG, io.github.dorumrr.privacyflip.util.Constants.Work.NAME_LOCK
                    )
                }
                // #A2's own #G1 gap, found by this round's adversarial review: without this, a
                // 3rd unlock signal (this restart catch-up firing moments after another path
                // already caught the same real unlock) could REPLACE-enqueue while that 2nd
                // unlock's job is still mid-way through enableFeatures(), cancelling that
                // in-flight coroutine and leaving sensors off after a real unlock. Checked after
                // recordUnlock()/cancel() above - both are still correct and safe to do even
                // when the enqueue itself is about to be skipped as redundant.
                if (PrivacyActionWorker.sensorEnableInProgress) {
                    Log.d(TAG, "⏳ Sensor enable already in progress - not enqueuing a duplicate")
                    return
                }
            }

            // Check if device is currently locked to pass correct flag to worker
            val isDeviceLocked = isScreenCurrentlyLocked()

            val workRequest = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
                .setInputData(
                    workDataOf(
                        "is_locking" to isLocking,
                        "is_device_locked" to isDeviceLocked,
                        "trigger" to "service_init",
                        "reason" to reason
                    )
                )
                .build()

            // Unique work names shared with ScreenStateReceiver and PrivacyAccessibilityService
            val workName = if (isLocking) io.github.dorumrr.privacyflip.util.Constants.Work.NAME_LOCK
                            else io.github.dorumrr.privacyflip.util.Constants.Work.NAME_UNLOCK
            WorkManager.getInstance(this).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.i(TAG, "🔄 Initial privacy action enqueued (unique: $workName): ${if (isUnlocking) "unlock" else "lock"} actions (deviceLocked=$isDeviceLocked)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger initial privacy action", e)
        }
    }
}
