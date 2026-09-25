package io.github.dorumrr.privacyflip.util

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log

class ForegroundAppDetector(private val context: Context) {

    companion object {
        private const val TAG = "privacyFlip-ForegroundAppDetector"

        // The lock job asks before the camera/mic race, so the short query runs first.
        private const val RECENT_WINDOW_MS = 10 * 60 * 1000L
        // An app kept on screen for hours, such as navigation, logged one resume when it opened.
        private const val LONG_WINDOW_MS = 24 * 60 * 60 * 1000L
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        // MODE_DEFAULT hands the decision to the permission itself.
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    // Runs inside the lock job, so it must never throw; a stored app that was uninstalled cannot be in front.
    fun exemptAppsNeedUsageAccess(exemptApps: Set<String>): Boolean = try {
        exemptApps.any { isInstalled(it) } && !hasUsageAccess()
    } catch (e: Exception) {
        Log.e(TAG, "Error checking usage access", e)
        false
    }

    private fun isInstalled(packageName: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun getForegroundApp(): String? {
        return try {
            lastOpenedWhileScreenOn(RECENT_WINDOW_MS) ?: lastOpenedWhileScreenOn(LONG_WINDOW_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting foreground app", e)
            null
        }
    }

    private fun lastOpenedWhileScreenOn(windowMs: Long): String? {
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(end - windowMs, end) ?: return null
        val event = UsageEvents.Event()
        var screenOn = true
        var lastOpened: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                // Screen events exist from Android 9; older versions never send them.
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> screenOn = false
                UsageEvents.Event.DEVICE_STARTUP -> lastOpened = null
                // Same value as MOVE_TO_FOREGROUND, which Android 9 and older report.
                UsageEvents.Event.ACTIVITY_RESUMED -> if (screenOn) lastOpened = event.packageName
            }
        }

        Log.d(TAG, "Foreground app detected: $lastOpened (window ${windowMs / 1000}s)")
        return lastOpened
    }

    fun getFirstForegroundApp(packageNames: Set<String>): String? {
        val foregroundApp = getForegroundApp() ?: return null

        return if (packageNames.contains(foregroundApp)) {
            Log.i(TAG, "Exempt app $foregroundApp was the last app opened while the screen was on")
            foregroundApp
        } else {
            null
        }
    }
}
