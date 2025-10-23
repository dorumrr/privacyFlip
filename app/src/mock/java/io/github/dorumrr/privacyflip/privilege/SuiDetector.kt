package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import io.github.dorumrr.privacyflip.util.LogManager

/**
 * Mock detector for Sui - always returns false for testing
 * This class is only compiled in the "mock" flavor
 */
object SuiDetector {
    
    private const val TAG = "SuiDetector"
    private var logManager: LogManager? = null
    
    /**
     * Mock implementation - always returns false
     */
    fun detectAndInitialize(context: Context): Boolean {
        if (logManager == null) {
            logManager = LogManager.getInstance(context)
        }
        
        logManager?.i(TAG, "🎭 Mock: Sui not available (mock flavor)")
        return false
    }
    
    /**
     * Mock implementation - always returns false
     */
    fun isSuiAvailable(): Boolean {
        return false
    }
}

