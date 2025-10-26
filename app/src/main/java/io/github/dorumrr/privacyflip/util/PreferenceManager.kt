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
        }
        return prefs.getBoolean(key, default)
    }
    
    fun setFeatureEnableOnUnlock(feature: PrivacyFeature, value: Boolean) {
        val key = Constants.Preferences.getFeatureUnlockKey(feature.name)
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
}
