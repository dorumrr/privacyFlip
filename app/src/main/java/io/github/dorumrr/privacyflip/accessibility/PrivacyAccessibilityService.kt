package io.github.dorumrr.privacyflip.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker

/**
 * Accessibility Service that detects screen-off / lock events earlier than the standard
 * ACTION_SCREEN_OFF broadcast. This allows sensor privacy features (camera/microphone)
 * to be disabled even when using the side/power button for instant-lock.
 * 
 * Android's security restriction prevents changing sensor privacy while the device is locked.
 * By detecting the lock earlier (before keyguard fully engages), we can disable sensors
 * in time even with instant-lock methods.
 * 
 * This service is OPTIONAL and requires explicit user permission via Android Settings > Accessibility.
 * Users must opt-in to this experimental feature.
 */
class PrivacyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "privacyFlip-AccessibilityService"
        
        @Volatile
        private var isServiceRunning = false
        
        /**
         * Check if the accessibility service is currently running.
         * Used by UI to show service status.
         */
        fun isRunning(): Boolean = isServiceRunning
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, "✅ Accessibility Service connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Only process window state changes (lock screen appearance)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }
        
        try {
            // Check if user has enabled this feature in app preferences
            val preferenceManager = PreferenceManager.getInstance(applicationContext)
            if (!preferenceManager.accessibilityServiceEnabled) {
                Log.d(TAG, "Accessibility feature disabled in app preferences - ignoring event")
                return
            }
            
            val className = event.className?.toString() ?: ""

            // Detect if this is a lock screen window. This has to fire and act
            // immediately, with no confirmation step: by the time
            // KeyguardManager.isKeyguardLocked() can be confirmed true, Android's own
            // lock restriction already blocks changing sensor privacy, so gating on
            // it here would make this service unable to ever do the one thing it
            // exists for. isLockScreenClass() is the only gate, so its wording has to
            // carry the whole burden of telling a real lock apart from the
            // notification shade (see its own doc for why "StatusBar" was dropped).
            if (isLockScreenClass(className)) {
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                // Package name logged alongside the class name (#H1): isLockScreenClass() has no
                // package scoping, matching by class name substring alone, so if a third-party
                // app's own screen ever false-triggers this, the package name here is what would
                // actually identify it - without this, a false-positive report would be very
                // hard to track down to its cause.
                Log.d(TAG, "🔒 Lock screen detected via Accessibility (class: $className, package: ${event.packageName}, isKeyguardLocked=${keyguardManager?.isKeyguardLocked})")
                triggerEarlyPrivacyActions()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event", e)
        }
    }

    /**
     * Detects if the window class name indicates a lock screen.
     *
     * Deliberately does NOT match "StatusBar" any more. It used to, and that let a
     * genuine keyguard signal through on some devices - but the notification shade
     * is rendered by the same StatusBar-lineage classes in AOSP, so it also fired
     * this on a mere shade pull while the phone was unlocked and in active use
     * (#26). "Keyguard" and "LockScreen" are specific to the actual lock mechanism
     * itself, and cover every real-world class name this project has seen recorded
     * for lock-screen appearance, so dropping "StatusBar" removes the false
     * positive without (as far as recorded evidence shows) losing real locks:
     * - com.android.internal.policy.impl.keyguard.KeyguardViewMediator
     * - com.android.systemui.keyguard.KeyguardViewMediator
     * - Various manufacturer-specific Keyguard or LockScreen classes
     * If a device's real keyguard reports through neither word, its logs will show
     * no "Lock screen detected via Accessibility" line at all when it locks -
     * that is the signal a device needs a name added here, not a wider net.
     */
    private fun isLockScreenClass(className: String): Boolean {
        return className.contains("Keyguard", ignoreCase = true) ||
               className.contains("LockScreen", ignoreCase = true)
    }

    /**
     * Triggers privacy actions early (before keyguard fully engages).
     * Uses WorkManager with REPLACE policy to prevent duplicate execution
     * if the normal ACTION_SCREEN_OFF flow also triggers.
     */
    private fun triggerEarlyPrivacyActions() {
        try {
            if (PrivacyActionWorker.sensorDisableInProgress) {
                // Another trigger for this same lock is already disabling sensors right now -
                // REPLACE would cancel it mid-flight (#G1). Nothing to gain by racing it.
                Log.d(TAG, "⏳ Sensor disable already in progress - not enqueuing a duplicate")
                return
            }

            val workRequest = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
                .setInputData(
                    workDataOf(
                        "is_locking" to true,
                        "is_device_locked" to false,  // Device not fully locked yet
                        "trigger" to "accessibility_service",
                        "reason" to "Early Lock Detection (Accessibility)"
                    )
                )
                .build()

            // Same unique work name every lock-trigger site uses (Constants.Work.NAME_LOCK).
            // REPLACE policy ensures no duplicate execution once this one starts.
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                io.github.dorumrr.privacyflip.util.Constants.Work.NAME_LOCK,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            
            Log.i(TAG, "✅ Early privacy actions triggered (unique work: privacy_action_lock)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger early privacy actions", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.i(TAG, "❌ Accessibility Service destroyed")
    }
}
