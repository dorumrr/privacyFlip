package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.os.Build
import io.github.dorumrr.privacyflip.util.LogManager
import rikka.sui.Sui

/**
 * Detector for Sui (Magisk module that provides Shizuku API)
 * This class is only compiled in the "real" flavor
 */
object SuiDetector {
    
    private const val TAG = "SuiDetector"
    private var logManager: LogManager? = null
    
    /**
     * Check if Sui is available and initialize it
     * @return true if Sui is available, false otherwise
     */
    fun detectAndInitialize(context: Context): Boolean {
        if (logManager == null) {
            logManager = LogManager.getInstance(context)
        }
        
        // Sui requires Android 6.0+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            logManager?.i(TAG, "Sui not supported on Android < 6.0")
            return false
        }
        
        try {
            val packageName = context.packageName
            val suiAvailable = Sui.init(packageName)
            
            if (suiAvailable) {
                logManager?.i(TAG, "✅ Sui detected and initialized successfully")
            } else {
                logManager?.i(TAG, "Sui not available (not installed or not a Magisk module)")
            }
            
            return suiAvailable
        } catch (e: Exception) {
            logManager?.e(TAG, "Error detecting Sui: ${e.message}")
            return false
        }
    }
    
    /**
     * Check if Sui is available without initializing
     */
    fun isSuiAvailable(): Boolean {
        return try {
            Sui.isSui()
        } catch (e: Exception) {
            false
        }
    }
}

