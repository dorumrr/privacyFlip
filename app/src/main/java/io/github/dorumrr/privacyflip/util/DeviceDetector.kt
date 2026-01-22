package io.github.dorumrr.privacyflip.util

import android.os.Build

/**
 * Utility object to detect device manufacturer and models.
 * Used to implement manufacturer-specific workarounds and warnings.
 */
object DeviceDetector {
    
    /**
     * Check if the current device is made by Samsung.
     * 
     * @return true if device is Samsung, false otherwise
     */
    fun isSamsungDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.uppercase()
        val brand = Build.BRAND.uppercase()
        return manufacturer == "SAMSUNG" || brand == "SAMSUNG"
    }
    
    /**
     * Check if the current device is a Samsung device with payment override capability.
     * 
     * Samsung flagship devices (Galaxy S, Note, Z series) have deep integration with
     * Samsung Pay and Google Wallet that can override NFC disable commands to keep
     * payment functionality available even when NFC is "disabled".
     * 
     * @return true if device is Samsung with potential payment override, false otherwise
     */
    fun isSamsungWithPaymentOverride(): Boolean {
        if (!isSamsungDevice()) return false
        
        val model = Build.MODEL.uppercase()
        
        // Samsung Galaxy S series (e.g., SM-S921B, SM-S936B, etc.)
        // Samsung Galaxy Note series (e.g., SM-N986B, etc.)
        // Samsung Galaxy Z Fold series (e.g., SM-F936B, etc.)
        // Samsung Galaxy Z Flip series (e.g., SM-F711B, etc.)
        // Samsung Galaxy (older) series (e.g., SM-G998B, etc.)
        return model.startsWith("SM-S") ||   // S series
               model.startsWith("SM-N") ||   // Note series
               model.startsWith("SM-F") ||   // Z Fold/Flip series
               model.startsWith("SM-Z") ||   // Z series (legacy)
               model.startsWith("SM-G")      // Galaxy series
    }
}
