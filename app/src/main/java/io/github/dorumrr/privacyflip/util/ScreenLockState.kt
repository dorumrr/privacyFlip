package io.github.dorumrr.privacyflip.util

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Checks whether the screen is currently locked (keyguard engaged, or the screen is simply off).
 *
 * #Audit finding 1 (production-readiness audit, 10 Sep): this used to be two separate, identical
 * copies - PrivacyMonitorService's own private function, and a second, independent one in
 * PrivacyActionWorker.kt. #D5 fixed only the first copy's exception fallback (from "assume
 * unlocked" to "assume locked", matching every other lock-state read in this app) - the second
 * copy, used at 2 high-stakes points in PrivacyActionWorker's own delay re-validation, was never
 * touched and still failed open. Collapsed into one shared function so there is only one place
 * left to get this right, and only one place a future fix needs to touch.
 */
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
