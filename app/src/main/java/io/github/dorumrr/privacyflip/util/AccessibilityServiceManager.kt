package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Utility object to manage the Privacy Flip Accessibility Service.
 * 
 * The Accessibility Service allows the app to detect side/power button locks earlier,
 * before the keyguard fully engages, enabling sensor privacy features to be disabled
 * even when using instant-lock methods.
 */
object AccessibilityServiceManager {
    
    private const val TAG = "privacyFlip-AccessibilityManager"
    
    /**
     * Check if the Privacy Flip Accessibility Service is currently enabled.
     * 
     * @param context Application context
     * @return true if service is enabled, false otherwise
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val service = "${context.packageName}/.accessibility.PrivacyAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            
            val isEnabled = enabledServices?.contains(service) == true
            
            Log.d(TAG, "Accessibility service enabled check: $isEnabled")
            isEnabled
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service status", e)
            false
        }
    }
    
    /**
     * Open Android system Accessibility Settings where user can enable the service.
     * 
     * @param context Application context
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "Opened Accessibility Settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
        }
    }
}
