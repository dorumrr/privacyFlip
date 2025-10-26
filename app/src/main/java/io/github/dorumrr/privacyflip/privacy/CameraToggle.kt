package io.github.dorumrr.privacyflip.privacy

import android.os.Build
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.StatusParsingUtils

class CameraToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.CAMERA
    override val featureName = "Camera"

    // Enable = Allow camera access (disable privacy)
    // Note: sensor=2 is camera, user_id=0 is the primary user
    // Android blocks changing this while device is locked (security restriction)
    override val enableCommands = listOf(
        CommandSet("cmd sensor_privacy disable 0 camera", description = "Sensor privacy control (Android 12+)")
    )

    // Disable = Block camera access (enable privacy)
    override val disableCommands = listOf(
        CommandSet("cmd sensor_privacy enable 0 camera", description = "Sensor privacy control (Android 12+)")
    )

    override val statusCommands = listOf(
        CommandSet("dumpsys sensor_privacy", description = "Check sensor privacy status")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        // sensor=2 is camera
        return StatusParsingUtils.parseSensorPrivacyOutput(output, sensorId = 2)
    }

    override suspend fun isSupported(): FeatureSupport {
        // cmd sensor_privacy requires Android 12+ (API 31)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            FeatureSupport.FULLY_SUPPORTED
        } else {
            FeatureSupport.UNSUPPORTED
        }
    }
}

