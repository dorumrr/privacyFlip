package io.github.dorumrr.privacyflip.util

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

// Android refuses a camera/mic privacy change only while unlocking needs a PIN, pattern or password.
// Fails open: a wrong "locked" skips a switch Android may allow, while a refused attempt is reported.
fun isDeviceSecurelyLocked(context: Context, tag: String): Boolean {
    return try {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.isDeviceLocked
    } catch (e: Exception) {
        Log.e(tag, "Error reading keyguard state", e)
        false
    }
}

// The one copy of this check for the service and the worker, so its fail-closed default cannot drift.
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
        // Fail closed: a wrong "unlocked" would cancel a real pending disable, or run a pending enable, on a locked phone.
        true
    }
}
