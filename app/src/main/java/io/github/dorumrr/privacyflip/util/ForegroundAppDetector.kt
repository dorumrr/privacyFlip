package io.github.dorumrr.privacyflip.util

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Utility class to detect which app is currently in the foreground.
 * Used for app exemption feature to prevent disabling features when exempt apps are active.
 */
class ForegroundAppDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "privacyFlip-ForegroundAppDetector"
    }
    
    /**
     * Get the package name of the currently foreground app.
     * Requires PACKAGE_USAGE_STATS permission.
     * 
     * @return Package name of foreground app, or null if unable to detect
     */
    fun getForegroundApp(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                getForegroundAppUsingUsageStats()
            } else {
                getForegroundAppUsingActivityManager()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting foreground app", e)
            null
        }
    }
    
    /**
     * Get foreground app using UsageStatsManager (Android 5.1+).
     * This is the recommended method for modern Android versions.
     */
    private fun getForegroundAppUsingUsageStats(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return null
        }
        
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            
            val currentTime = System.currentTimeMillis()
            // Query events from last 10 seconds
            val events = usageStatsManager.queryEvents(currentTime - 10000, currentTime)
            
            var lastForegroundApp: String? = null
            val event = UsageEvents.Event()
            
            while (events.hasNextEvent()) {
                events.getNextEvent(event)

                // Look for foreground events
                // Use ACTIVITY_RESUMED on Android 10+ (API 29+), MOVE_TO_FOREGROUND on older versions
                val isForegroundEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    @Suppress("DEPRECATION")
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                }

                if (isForegroundEvent) {
                    lastForegroundApp = event.packageName
                }
            }
            
            Log.d(TAG, "Foreground app detected: $lastForegroundApp")
            return lastForegroundApp
            
        } catch (e: Exception) {
            Log.e(TAG, "Error using UsageStatsManager", e)
            return null
        }
    }
    
    /**
     * Get foreground app using ActivityManager (deprecated, for older Android versions).
     * This method is deprecated but works on Android < 5.1.
     */
    @Suppress("DEPRECATION")
    private fun getForegroundAppUsingActivityManager(): String? {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            
            val runningTasks = activityManager.getRunningTasks(1)
            if (runningTasks.isNotEmpty()) {
                val topActivity = runningTasks[0].topActivity
                val packageName = topActivity?.packageName
                Log.d(TAG, "Foreground app detected (ActivityManager): $packageName")
                return packageName
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error using ActivityManager", e)
        }
        
        return null
    }
    
    /**
     * Check if a specific app is currently in the foreground.
     * 
     * @param packageName Package name to check
     * @return true if the app is in foreground, false otherwise
     */
    fun isAppInForeground(packageName: String): Boolean {
        val foregroundApp = getForegroundApp()
        val isInForeground = foregroundApp == packageName
        
        if (isInForeground) {
            Log.i(TAG, "App $packageName is in foreground")
        }
        
        return isInForeground
    }
    
    /**
     * Check if any of the given apps is currently in the foreground.
     * 
     * @param packageNames List of package names to check
     * @return Package name of the foreground app if it's in the list, null otherwise
     */
    fun getFirstForegroundApp(packageNames: Set<String>): String? {
        val foregroundApp = getForegroundApp() ?: return null
        
        return if (packageNames.contains(foregroundApp)) {
            Log.i(TAG, "Exempt app $foregroundApp is in foreground")
            foregroundApp
        } else {
            null
        }
    }
}

