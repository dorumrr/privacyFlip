package io.github.dorumrr.privacyflip.util

import android.os.Build

/**
 * Utility object to detect device manufacturer and models.
 * Used to implement manufacturer-specific workarounds and warnings.
 */
object DeviceDetector {
    
    /**
     * Can this Android version switch the camera and microphone at all?
     *
     * Both go through `cmd sensor_privacy`, added in Android 12, and there is no older
     * equivalent to fall back to the way LocationToggle falls back per version. Below 12 the
     * command is simply not there, so the app must neither act on those two features nor offer
     * them as if it could: it used to do both, failing on every lock while the switches said
     * the camera was protected.
     */
    fun supportsSensorPrivacyToggle(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
