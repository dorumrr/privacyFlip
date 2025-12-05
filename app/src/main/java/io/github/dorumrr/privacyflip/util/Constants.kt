package io.github.dorumrr.privacyflip.util

object Constants {
    
    object ServiceNotification {
        const val CHANNEL_ID = "privacy_monitor"
        const val NOTIFICATION_ID = 1000
    }

    object DebugNotification {
        const val CHANNEL_ID = "privacy_debug"
        const val NOTIFICATION_ID_BASE = 2000
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
        const val KEY_DEBUG_NOTIFICATIONS_ENABLED = "debug_notifications_enabled"
        const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"

        fun getFeatureLockKey(featureName: String): String = "${featureName.lowercase()}_disable_on_lock"
        fun getFeatureUnlockKey(featureName: String): String = "${featureName.lowercase()}_enable_on_unlock"
        fun getFeatureOnlyIfUnusedKey(featureName: String): String = "${featureName.lowercase()}_only_if_unused"
        fun getFeatureOnlyIfNotManualKey(featureName: String): String = "${featureName.lowercase()}_only_if_not_manual"
        fun getFeatureEnabledByAppKey(featureName: String): String = "${featureName.lowercase()}_enabled_by_app"
    }
    
    object Defaults {
        const val LOCK_DELAY_SECONDS = 10
        const val UNLOCK_DELAY_SECONDS = 1
        const val SHOW_COUNTDOWN = true

        const val BACKGROUND_SERVICE_ENABLED = true
        const val DEBUG_NOTIFICATIONS_ENABLED = false
        const val DEBUG_LOGS_ENABLED = false

        const val WIFI_DISABLE_ON_LOCK = true
        const val BLUETOOTH_DISABLE_ON_LOCK = true
        const val LOCATION_DISABLE_ON_LOCK = true
        const val MOBILE_DATA_DISABLE_ON_LOCK = true
        const val NFC_DISABLE_ON_LOCK = true
        // Camera/Microphone: These are disabled IMMEDIATELY when lock button is pressed
        // (before the device fully locks) to work around Android's security restriction
        const val CAMERA_DISABLE_ON_LOCK = true
        const val MICROPHONE_DISABLE_ON_LOCK = true

        // "Only if unused/not connected" defaults - when true, feature will only be
        // disabled on lock if it's not actively connected/in use
        const val WIFI_ONLY_IF_UNUSED = false
        const val BLUETOOTH_ONLY_IF_UNUSED = false
        const val LOCATION_ONLY_IF_UNUSED = false
        const val MOBILE_DATA_ONLY_IF_UNUSED = false
        const val NFC_ONLY_IF_UNUSED = false
        const val CAMERA_ONLY_IF_UNUSED = false
        const val MICROPHONE_ONLY_IF_UNUSED = true // Microphone already had smart behavior

        const val WIFI_ENABLE_ON_UNLOCK = true
        const val BLUETOOTH_ENABLE_ON_UNLOCK = false
        const val LOCATION_ENABLE_ON_UNLOCK = false
        const val MOBILE_DATA_ENABLE_ON_UNLOCK = true
        const val NFC_ENABLE_ON_UNLOCK = false
        // Enable camera/mic on unlock (unblock them when device is unlocked)
        const val CAMERA_ENABLE_ON_UNLOCK = true
        const val MICROPHONE_ENABLE_ON_UNLOCK = true

        // Airplane Mode defaults (opt-in - disabled by default as it's more drastic)
        const val AIRPLANE_MODE_DISABLE_ON_LOCK = false
        const val AIRPLANE_MODE_ENABLE_ON_UNLOCK = false
        const val AIRPLANE_MODE_ONLY_IF_UNUSED = false

        // Battery Saver defaults (opt-in - disabled by default as it's a system mode)
        const val BATTERY_SAVER_DISABLE_ON_LOCK = false
        const val BATTERY_SAVER_ENABLE_ON_UNLOCK = false
        const val BATTERY_SAVER_ONLY_IF_UNUSED = false

        // "Only if not manually set" defaults for protection modes
        // When true, won't disable on unlock if user manually enabled the mode
        const val AIRPLANE_MODE_ONLY_IF_NOT_MANUAL = true
        const val BATTERY_SAVER_ONLY_IF_NOT_MANUAL = true
    }

    object Logging {
        const val LOG_FILE_NAME = "app_logs.txt"
        const val MAX_LOG_SIZE_KB = 500
        const val LOG_ROTATION_KEEP_RATIO = 0.8f
        const val BYTES_PER_KB = 1024
        const val MAX_BATCH_SIZE = 50
        const val LOG_PROCESSING_INTERVAL_MS = 1000L
    }

    object UI {
        const val DONATE_URL = "https://buymeacoffee.com/ossdev"
    }
}
