package io.github.dorumrr.privacyflip.data

enum class PrivacyFeature(
    val displayName: String,
    val description: String
) {
    WIFI("WiFi", "Wireless network connectivity"),
    BLUETOOTH("Bluetooth", "Short-range wireless connectivity"),
    MOBILE_DATA("Mobile Data", "Cellular data connectivity"),
    LOCATION("Location Services", "GPS and location tracking");

    companion object {
        fun getConnectivityFeatures(): List<PrivacyFeature> {
            return listOf(WIFI, BLUETOOTH, MOBILE_DATA, LOCATION)
        }
    }
}

enum class FeatureState {
    ENABLED,
    DISABLED,
    UNKNOWN,
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
        return lockDelaySeconds in 0..60 && unlockDelaySeconds in 0..60
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
