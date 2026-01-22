package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.content.SharedPreferences
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.data.TimerSettings

class PreferenceManager private constructor(private val context: Context) {
    
    companion object : SingletonHolder<PreferenceManager, Context>({ context ->
        PreferenceManager(context.applicationContext)
    })
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(Constants.Preferences.PRIVACY_SWITCH_PREFS, Context.MODE_PRIVATE)
    }
    
    var isGlobalPrivacyEnabled: Boolean
        get() = prefs.getBoolean(Constants.Preferences.KEY_GLOBAL_PRIVACY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(Constants.Preferences.KEY_GLOBAL_PRIVACY_ENABLED, value).apply()
    
    var lockDelaySeconds: Int
        get() = prefs.getInt(Constants.Preferences.KEY_LOCK_DELAY, Constants.Defaults.LOCK_DELAY_SECONDS)
        set(value) = prefs.edit().putInt(Constants.Preferences.KEY_LOCK_DELAY, value).apply()
    
    var unlockDelaySeconds: Int
        get() = prefs.getInt(Constants.Preferences.KEY_UNLOCK_DELAY, Constants.Defaults.UNLOCK_DELAY_SECONDS)
        set(value) = prefs.edit().putInt(Constants.Preferences.KEY_UNLOCK_DELAY, value).apply()
    
    var showCountdown: Boolean
        get() = prefs.getBoolean(Constants.Preferences.KEY_SHOW_COUNTDOWN, Constants.Defaults.SHOW_COUNTDOWN)
        set(value) = prefs.edit().putBoolean(Constants.Preferences.KEY_SHOW_COUNTDOWN, value).apply()

    var backgroundServiceEnabled: Boolean
        get() = prefs.getBoolean(Constants.Preferences.KEY_BACKGROUND_SERVICE_ENABLED, Constants.Defaults.BACKGROUND_SERVICE_ENABLED)
        set(value) = prefs.edit().putBoolean(Constants.Preferences.KEY_BACKGROUND_SERVICE_ENABLED, value).apply()

    var debugNotificationsEnabled: Boolean
        get() = prefs.getBoolean(Constants.Preferences.KEY_DEBUG_NOTIFICATIONS_ENABLED, Constants.Defaults.DEBUG_NOTIFICATIONS_ENABLED)
        set(value) = prefs.edit().putBoolean(Constants.Preferences.KEY_DEBUG_NOTIFICATIONS_ENABLED, value).apply()

    var debugLogsEnabled: Boolean
        get() = prefs.getBoolean(Constants.Preferences.KEY_DEBUG_LOGS_ENABLED, Constants.Defaults.DEBUG_LOGS_ENABLED)
        set(value) = prefs.edit().putBoolean(Constants.Preferences.KEY_DEBUG_LOGS_ENABLED, value).apply()

    /**
     * Samsung NFC auto-retry preference.
     * When enabled, the app will automatically retry disabling NFC if Samsung's payment
     * framework overrides the initial disable command. This may cause slight battery drain
     * but ensures NFC is properly disabled on Samsung devices.
     * Default: false (opt-in)
     */
    var samsungNfcAutoRetry: Boolean
        get() = prefs.getBoolean(Constants.Preferences.KEY_SAMSUNG_NFC_AUTO_RETRY, false)
        set(value) = prefs.edit().putBoolean(Constants.Preferences.KEY_SAMSUNG_NFC_AUTO_RETRY, value).apply()

    /**
     * Accessibility service preference.
     * Tracks whether the user has chosen to enable the accessibility service feature.
     * The actual service must be enabled in Android Settings > Accessibility.
     * Default: false (opt-in)
     */
    var accessibilityServiceEnabled: Boolean
        get() = prefs.getBoolean(Constants.Preferences.KEY_ACCESSIBILITY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(Constants.Preferences.KEY_ACCESSIBILITY_SERVICE_ENABLED, value).apply()
    
    fun getFeatureDisableOnLock(feature: PrivacyFeature): Boolean {
        val key = Constants.Preferences.getFeatureLockKey(feature.name)
        val default = when (feature) {
            PrivacyFeature.WIFI -> Constants.Defaults.WIFI_DISABLE_ON_LOCK
            PrivacyFeature.BLUETOOTH -> Constants.Defaults.BLUETOOTH_DISABLE_ON_LOCK
            PrivacyFeature.LOCATION -> Constants.Defaults.LOCATION_DISABLE_ON_LOCK
            PrivacyFeature.MOBILE_DATA -> Constants.Defaults.MOBILE_DATA_DISABLE_ON_LOCK
            PrivacyFeature.NFC -> Constants.Defaults.NFC_DISABLE_ON_LOCK
            PrivacyFeature.CAMERA -> Constants.Defaults.CAMERA_DISABLE_ON_LOCK
            PrivacyFeature.MICROPHONE -> Constants.Defaults.MICROPHONE_DISABLE_ON_LOCK
            PrivacyFeature.AIRPLANE_MODE -> Constants.Defaults.AIRPLANE_MODE_DISABLE_ON_LOCK
            PrivacyFeature.BATTERY_SAVER -> Constants.Defaults.BATTERY_SAVER_DISABLE_ON_LOCK
        }
        return prefs.getBoolean(key, default)
    }
    
    fun setFeatureDisableOnLock(feature: PrivacyFeature, value: Boolean) {
        val key = Constants.Preferences.getFeatureLockKey(feature.name)
        prefs.edit().putBoolean(key, value).apply()
    }
    
    fun getFeatureEnableOnUnlock(feature: PrivacyFeature): Boolean {
        val key = Constants.Preferences.getFeatureUnlockKey(feature.name)
        val default = when (feature) {
            PrivacyFeature.WIFI -> Constants.Defaults.WIFI_ENABLE_ON_UNLOCK
            PrivacyFeature.BLUETOOTH -> Constants.Defaults.BLUETOOTH_ENABLE_ON_UNLOCK
            PrivacyFeature.LOCATION -> Constants.Defaults.LOCATION_ENABLE_ON_UNLOCK
            PrivacyFeature.MOBILE_DATA -> Constants.Defaults.MOBILE_DATA_ENABLE_ON_UNLOCK
            PrivacyFeature.NFC -> Constants.Defaults.NFC_ENABLE_ON_UNLOCK
            PrivacyFeature.CAMERA -> Constants.Defaults.CAMERA_ENABLE_ON_UNLOCK
            PrivacyFeature.MICROPHONE -> Constants.Defaults.MICROPHONE_ENABLE_ON_UNLOCK
            PrivacyFeature.AIRPLANE_MODE -> Constants.Defaults.AIRPLANE_MODE_ENABLE_ON_UNLOCK
            PrivacyFeature.BATTERY_SAVER -> Constants.Defaults.BATTERY_SAVER_ENABLE_ON_UNLOCK
        }
        return prefs.getBoolean(key, default)
    }
    
    fun setFeatureEnableOnUnlock(feature: PrivacyFeature, value: Boolean) {
        val key = Constants.Preferences.getFeatureUnlockKey(feature.name)
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getFeatureOnlyIfUnused(feature: PrivacyFeature): Boolean {
        val key = Constants.Preferences.getFeatureOnlyIfUnusedKey(feature.name)
        val default = when (feature) {
            PrivacyFeature.WIFI -> Constants.Defaults.WIFI_ONLY_IF_UNUSED
            PrivacyFeature.BLUETOOTH -> Constants.Defaults.BLUETOOTH_ONLY_IF_UNUSED
            PrivacyFeature.LOCATION -> Constants.Defaults.LOCATION_ONLY_IF_UNUSED
            PrivacyFeature.MOBILE_DATA -> Constants.Defaults.MOBILE_DATA_ONLY_IF_UNUSED
            PrivacyFeature.NFC -> Constants.Defaults.NFC_ONLY_IF_UNUSED
            PrivacyFeature.CAMERA -> Constants.Defaults.CAMERA_ONLY_IF_UNUSED
            PrivacyFeature.MICROPHONE -> Constants.Defaults.MICROPHONE_ONLY_IF_UNUSED
            PrivacyFeature.AIRPLANE_MODE -> Constants.Defaults.AIRPLANE_MODE_ONLY_IF_UNUSED
            PrivacyFeature.BATTERY_SAVER -> Constants.Defaults.BATTERY_SAVER_ONLY_IF_UNUSED
        }
        return prefs.getBoolean(key, default)
    }

    fun setFeatureOnlyIfUnused(feature: PrivacyFeature, value: Boolean) {
        val key = Constants.Preferences.getFeatureOnlyIfUnusedKey(feature.name)
        prefs.edit().putBoolean(key, value).apply()
    }

    /**
     * Get "only if not manually set" preference for protection modes (Airplane Mode, Battery Saver).
     * When true, the mode won't be disabled on unlock if it was already enabled before lock.
     */
    fun getFeatureOnlyIfNotManual(feature: PrivacyFeature): Boolean {
        val key = Constants.Preferences.getFeatureOnlyIfNotManualKey(feature.name)
        val default = when (feature) {
            PrivacyFeature.AIRPLANE_MODE -> Constants.Defaults.AIRPLANE_MODE_ONLY_IF_NOT_MANUAL
            PrivacyFeature.BATTERY_SAVER -> Constants.Defaults.BATTERY_SAVER_ONLY_IF_NOT_MANUAL
            else -> false // Not applicable to other features
        }
        return prefs.getBoolean(key, default)
    }

    fun setFeatureOnlyIfNotManual(feature: PrivacyFeature, value: Boolean) {
        val key = Constants.Preferences.getFeatureOnlyIfNotManualKey(feature.name)
        prefs.edit().putBoolean(key, value).apply()
    }

    /**
     * Get "only if not already enabled" preference for regular features.
     * When true, the feature won't be enabled on unlock if it's already enabled.
     * This prevents connection resets (e.g., WiFi/VPN disconnections).
     */
    fun getFeatureOnlyIfNotEnabled(feature: PrivacyFeature): Boolean {
        val key = Constants.Preferences.getFeatureOnlyIfNotEnabledKey(feature.name)
        val default = when (feature) {
            PrivacyFeature.WIFI -> Constants.Defaults.WIFI_ONLY_IF_NOT_ENABLED
            PrivacyFeature.BLUETOOTH -> Constants.Defaults.BLUETOOTH_ONLY_IF_NOT_ENABLED
            PrivacyFeature.LOCATION -> Constants.Defaults.LOCATION_ONLY_IF_NOT_ENABLED
            PrivacyFeature.MOBILE_DATA -> Constants.Defaults.MOBILE_DATA_ONLY_IF_NOT_ENABLED
            PrivacyFeature.NFC -> Constants.Defaults.NFC_ONLY_IF_NOT_ENABLED
            PrivacyFeature.CAMERA -> Constants.Defaults.CAMERA_ONLY_IF_NOT_ENABLED
            PrivacyFeature.MICROPHONE -> Constants.Defaults.MICROPHONE_ONLY_IF_NOT_ENABLED
            PrivacyFeature.AIRPLANE_MODE -> false // Not applicable to protection modes
            PrivacyFeature.BATTERY_SAVER -> false // Not applicable to protection modes
        }
        return prefs.getBoolean(key, default)
    }

    fun setFeatureOnlyIfNotEnabled(feature: PrivacyFeature, value: Boolean) {
        val key = Constants.Preferences.getFeatureOnlyIfNotEnabledKey(feature.name)
        prefs.edit().putBoolean(key, value).apply()
    }

    /**
     * Runtime state tracking: was this protection mode enabled by the app (not manually)?
     * This is not a user preference, but a state flag used to determine unlock behavior.
     */
    fun getFeatureEnabledByApp(feature: PrivacyFeature): Boolean {
        val key = Constants.Preferences.getFeatureEnabledByAppKey(feature.name)
        return prefs.getBoolean(key, false)
    }

    fun setFeatureEnabledByApp(feature: PrivacyFeature, value: Boolean) {
        val key = Constants.Preferences.getFeatureEnabledByAppKey(feature.name)
        prefs.edit().putBoolean(key, value).apply()
    }

    fun updateTimerSettings(settings: TimerSettings) {
        prefs.edit().apply {
            putInt(Constants.Preferences.KEY_LOCK_DELAY, settings.lockDelaySeconds)
            putInt(Constants.Preferences.KEY_UNLOCK_DELAY, settings.unlockDelaySeconds)
            putBoolean(Constants.Preferences.KEY_SHOW_COUNTDOWN, settings.showCountdown)
            apply()
        }
    }

    fun updateScreenLockConfig(feature: PrivacyFeature, disableOnLock: Boolean, enableOnUnlock: Boolean) {
        prefs.edit().apply {
            putBoolean(Constants.Preferences.getFeatureLockKey(feature.name), disableOnLock)
            putBoolean(Constants.Preferences.getFeatureUnlockKey(feature.name), enableOnUnlock)
            apply()
        }
    }

    fun updateBatch(updates: (SharedPreferences.Editor) -> Unit) {
        val editor = prefs.edit()
        updates(editor)
        editor.apply()
    }

    fun getRawPreferences(): SharedPreferences = prefs

    // ========== App Exemption Methods ==========

    /**
     * Get the set of package names that are exempt from privacy toggles.
     * When these apps are in the foreground, privacy features won't be disabled.
     *
     * @return Set of exempt package names
     */
    fun getExemptApps(): Set<String> {
        val exemptAppsString = prefs.getString(Constants.Preferences.KEY_EXEMPT_APPS, "") ?: ""
        return if (exemptAppsString.isEmpty()) {
            emptySet()
        } else {
            exemptAppsString.split(",").toSet()
        }
    }

    /**
     * Set the list of apps that are exempt from privacy toggles.
     *
     * @param packageNames Set of package names to exempt
     */
    fun setExemptApps(packageNames: Set<String>) {
        val exemptAppsString = packageNames.joinToString(",")
        prefs.edit().putString(Constants.Preferences.KEY_EXEMPT_APPS, exemptAppsString).apply()
    }

    /**
     * Add an app to the exemption list.
     *
     * @param packageName Package name to add
     */
    fun addExemptApp(packageName: String) {
        val currentExempt = getExemptApps().toMutableSet()
        currentExempt.add(packageName)
        setExemptApps(currentExempt)
    }

    /**
     * Remove an app from the exemption list.
     *
     * @param packageName Package name to remove
     */
    fun removeExemptApp(packageName: String) {
        val currentExempt = getExemptApps().toMutableSet()
        currentExempt.remove(packageName)
        setExemptApps(currentExempt)
    }

    /**
     * Check if an app is exempt from privacy toggles.
     *
     * @param packageName Package name to check
     * @return true if the app is exempt, false otherwise
     */
    fun isAppExempt(packageName: String): Boolean {
        return getExemptApps().contains(packageName)
    }
}
