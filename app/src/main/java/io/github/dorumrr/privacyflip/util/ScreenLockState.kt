package io.github.dorumrr.privacyflip.util

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Checks whether the screen is currently locked (keyguard engaged, or the screen is simply off).
 *
 * Shared by PrivacyMonitorService and PrivacyActionWorker rather than each holding its own copy:
 * two independent copies of this same check previously drifted (one failed open on an
 * exception, the other failed closed), so a fix to one silently left the other wrong. One shared
 * function means only one place is left to get this right.
 */
/**
 * Whether the keyguard is already enforcing.
 *
 * This is a different question from [isScreenCurrentlyLocked] and must not be answered with it.
 * Android refuses a sensor privacy change once the KEYGUARD is up, and does not care whether the
 * screen is merely off, so treating screen-off as locked skips the camera and microphone on a
 * phone that has no secure lock screen at all, and during the grace period before the keyguard
 * engages.
 *
 * Fails OPEN, unlike [isScreenCurrentlyLocked]: an unreadable keyguard means "try anyway", since
 * a refused attempt is now read back and reported honestly, while a skipped one leaves the
 * sensor on.
 */
fun isKeyguardEngaged(context: Context, tag: String): Boolean {
    return try {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.isKeyguardLocked
    } catch (e: Exception) {
        Log.e(tag, "Error reading keyguard state", e)
        false
    }
}

fun isScreenCurrentlyLocked(context: Context, tag: String): Boolean {
    return try {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val isKeyguardLocked = keyguardManager.isKeyguardLocked
        val isScreenOn = powerManager.isInteractive

        // Screen is considered locked if keyguard is active OR screen is off
        isKeyguardLocked || !isScreenOn
    } catch (e: Exception) {
        Log.e(tag, "Error checking screen lock state", e)
        // Fail closed: default to LOCKED when the read itself fails, matching every other
        // lock-state read in this app (ScreenStateReceiver, PrivacyAccessibilityService both use
        // `?: true`) - assuming unlocked on a failed read risks cancelling a real pending
        // disable, or letting a real pending enable proceed, on a phone that may still be locked.
        true
    }
}
