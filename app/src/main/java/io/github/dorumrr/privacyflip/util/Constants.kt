package io.github.dorumrr.privacyflip.util

object Constants {
    
    object ServiceNotification {
        const val CHANNEL_ID = "privacy_monitor"
        const val NOTIFICATION_ID = 1000
    }

    object BootReceiver {
        const val DEBOUNCE_DELAY_MS = 300L
    }
    
    object Preferences {
        const val PRIVACY_SWITCH_PREFS = "privacy_switch_prefs"

        const val KEY_GLOBAL_PRIVACY_ENABLED = "global_privacy_enabled"

        const val KEY_LOCK_DELAY = "lock_delay_seconds"
        const val KEY_UNLOCK_DELAY = "unlock_delay_seconds"
        const val KEY_SHOW_COUNTDOWN = "show_countdown"

        const val KEY_BACKGROUND_SERVICE_ENABLED = "background_service_enabled"

        fun getFeatureLockKey(featureName: String): String = "${featureName.lowercase()}_disable_on_lock"
        fun getFeatureUnlockKey(featureName: String): String = "${featureName.lowercase()}_enable_on_unlock"
    }
    
    object Defaults {
        const val LOCK_DELAY_SECONDS = 10
        const val UNLOCK_DELAY_SECONDS = 1
        const val SHOW_COUNTDOWN = true

        const val BACKGROUND_SERVICE_ENABLED = true

        // On Lock: Disable all privacy features for maximum privacy
        const val WIFI_DISABLE_ON_LOCK = true
        const val BLUETOOTH_DISABLE_ON_LOCK = true
        const val LOCATION_DISABLE_ON_LOCK = true
        const val MOBILE_DATA_DISABLE_ON_LOCK = true

        // On Unlock: Enable essential connectivity, keep Bluetooth and Location disabled for privacy
        const val WIFI_ENABLE_ON_UNLOCK = true
        const val BLUETOOTH_ENABLE_ON_UNLOCK = false
        const val LOCATION_ENABLE_ON_UNLOCK = false
        const val MOBILE_DATA_ENABLE_ON_UNLOCK = true
    }

    object Logging {
        const val LOG_FILE_NAME = "app_logs.txt"
        const val MAX_LOG_SIZE_KB = 500
        const val LOG_ROTATION_KEEP_RATIO = 0.8f
        const val BYTES_PER_KB = 1024
        const val MAX_BATCH_SIZE = 50
    }

    object UI {
        const val AUTHOR_NAME = "Doru Moraru"
        const val AUTHOR_URL = "https://github.com/dorumrr/privacyflip"
        const val CREATED_BY_TEXT = "Created by"
        const val DONATE_URL = "https://buymeacoffee.com/ossdev"

        const val LOG_PROCESSING_INTERVAL_MS = 1000L
    }
}
