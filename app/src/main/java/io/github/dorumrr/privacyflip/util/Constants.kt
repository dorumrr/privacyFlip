package io.github.dorumrr.privacyflip.util

object Constants {
    
    object ServiceNotification {
        const val CHANNEL_ID = "privacy_monitor"
        const val NOTIFICATION_ID = 1000
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
        const val LOCK_DELAY_SECONDS = 0
        const val UNLOCK_DELAY_SECONDS = 0
        const val SHOW_COUNTDOWN = true

        const val BACKGROUND_SERVICE_ENABLED = true

        const val WIFI_DISABLE_ON_LOCK = true
        const val BLUETOOTH_DISABLE_ON_LOCK = true
        const val LOCATION_DISABLE_ON_LOCK = true
        const val MOBILE_DATA_DISABLE_ON_LOCK = false

        const val WIFI_ENABLE_ON_UNLOCK = true
        const val BLUETOOTH_ENABLE_ON_UNLOCK = true
        const val LOCATION_ENABLE_ON_UNLOCK = false
        const val MOBILE_DATA_ENABLE_ON_UNLOCK = false
    }

    object Logging {
        const val LOG_FILE_NAME = "app_logs.txt"
        const val MAX_LOG_SIZE_KB = 500
        const val LOG_ROTATION_KEEP_RATIO = 0.8f
        const val BYTES_PER_KB = 1024
        const val MAX_BATCH_SIZE = 50
    }

    object UI {
        const val APP_NAME_VERSION = "PrivacyFlip v1.0.0"
        const val AUTHOR_NAME = "Doru Moraru"
        const val AUTHOR_URL = "https://github.com/dorumrr/privacyflip"
        const val CREATED_BY_TEXT = "Created by "
        const val APP_LOGS_TEXT = "App Logs"

        const val LOG_VIEWER_TITLE = "Application Logs"
        const val LOG_SIZE_FORMAT = "Size: %dKB / 500KB"
        const val CLEAR_LOGS_TITLE = "Clear Logs"
        const val CLEAR_LOGS_MESSAGE = "Are you sure you want to clear all logs? This action cannot be undone."
        const val CLEAR_BUTTON = "Clear"
        const val CANCEL_BUTTON = "Cancel"
        const val BACK_BUTTON = "Back"
        const val COPY_LOGS_BUTTON = "Copy Logs"
        const val LOGS_COPIED_MESSAGE = "Logs copied to clipboard"
        const val NO_LOGS_TITLE = "No logs available"
        const val NO_LOGS_MESSAGE = "Logs will appear here as you use the app"

        const val COPY_SNACKBAR_DURATION_MS = 2000L
        const val LOG_PROCESSING_INTERVAL_MS = 1000L
    }
}
