package io.github.dorumrr.privacyflip.data

enum class PrivacyFeature(
    val displayName: String,
    val description: String
) {
    WIFI("WiFi", "Wireless network connectivity"),
    BLUETOOTH("Bluetooth", "Short-range wireless connectivity"),
    MOBILE_DATA("Mobile Data", "Cellular data connectivity"),
    LOCATION("Location Services", "GPS and location tracking"),
    NFC("NFC", "Near Field Communication"),
    CAMERA("Camera", "Camera sensor access"),
    MICROPHONE("Microphone", "Microphone sensor access");

    companion object {
        fun getConnectivityFeatures(): List<PrivacyFeature> {
            return listOf(WIFI, BLUETOOTH, MOBILE_DATA, LOCATION, NFC)
        }

        fun getSensorFeatures(): List<PrivacyFeature> {
            return listOf(CAMERA, MICROPHONE)
        }
    }
}

enum class FeatureState {
    ENABLED,
    DISABLED,
    UNKNOWN,
    UNAVAILABLE,
    ERROR
}

enum class FeatureSupport {
    FULLY_SUPPORTED,
    BASIC_SUPPORT,
    UNSUPPORTED
}

data class PrivacyConfig(
    val lockFeatures: Set<PrivacyFeature> = emptySet(),
    val unlockFeatures: Set<PrivacyFeature> = emptySet(),
    val lockDelaySeconds: Int = 0,
    val unlockDelaySeconds: Int = 0,
    val showCountdown: Boolean = true
)

data class PrivacyResult(
    val feature: PrivacyFeature,
    val success: Boolean,
    val message: String? = null,
    val commandUsed: String? = null
)

data class TimerSettings(
    val lockDelaySeconds: Int = 10,
    val unlockDelaySeconds: Int = 1,
    val showCountdown: Boolean = true
) {
    fun isValid(): Boolean {
        return lockDelaySeconds in 0..300 && unlockDelaySeconds in 0..300
    }

    companion object {
        // Total positions: 100 (0-100)
        // First 60% (0-60): 0-60 seconds (positions 0-60)
        // Position 80: 120 seconds (2 minutes) - DISCRETE JUMP
        // Position 100: 300 seconds (5 minutes) - DISCRETE JUMP
        const val MAX_POSITION = 100

        /**
         * Converts SeekBar position to actual delay seconds.
         * Mapping:
         * - Positions 0-60: Direct mapping (0-60 seconds) - First 60% of slider
         * - Position 80: 120 seconds (2 minutes) - Discrete jump at 80%
         * - Position 100: 300 seconds (5 minutes) - Discrete jump at 100%
         *
         * Note: Positions 61-79 and 81-99 are NOT selectable - they snap to nearest tick.
         */
        fun positionToSeconds(position: Int): Int {
            return when (position) {
                in 0..60 -> position
                in 61..79 -> 120  // Snap to 2m
                80 -> 120
                in 81..99 -> 300  // Snap to 5m
                100 -> 300
                else -> 0
            }
        }

        /**
         * Converts delay seconds to SeekBar position.
         * Reverse mapping of positionToSeconds.
         */
        fun secondsToPosition(seconds: Int): Int {
            return when (seconds) {
                in 0..60 -> seconds
                120 -> 80
                300 -> 100
                in 61..119 -> 80   // Snap to 2m position
                in 121..299 -> 100 // Snap to 5m position
                else -> if (seconds > 300) 100 else 0
            }
        }

        /**
         * Formats seconds as a human-readable string.
         * Examples: "0s", "30s", "2m", "5m"
         */
        fun formatSeconds(seconds: Int): String {
            return when {
                seconds == 0 -> "0s"
                seconds < 60 -> "${seconds}s"
                seconds == 60 -> "1m"
                seconds == 120 -> "2m"
                seconds == 300 -> "5m"
                seconds % 60 == 0 -> "${seconds / 60}m"
                else -> "${seconds}s"
            }
        }
    }
}



data class CommandSet(
    val primary: String,
    val fallbacks: List<String> = emptyList(),
    val minApi: Int? = null,
    val maxApi: Int? = null,
    val description: String = ""
)

data class PrivacyStatus(
    val isActive: Boolean = false,
    val activeFeatures: Set<PrivacyFeature> = emptySet()
)
