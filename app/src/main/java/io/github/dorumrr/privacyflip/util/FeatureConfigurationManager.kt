package io.github.dorumrr.privacyflip.util

import io.github.dorumrr.privacyflip.data.PrivacyFeature

class FeatureConfigurationManager(private val preferenceManager: PreferenceManager) {

    fun getFeaturesToDisableOnLock(): List<PrivacyFeature> {
        return getConfiguredFeatures(true)
    }

    fun getFeaturesToEnableOnUnlock(): List<PrivacyFeature> {
        return getConfiguredFeatures(false)
    }

    private fun getConfiguredFeatures(isLockAction: Boolean): List<PrivacyFeature> {
        val features = mutableListOf<PrivacyFeature>()

        val sensorFeatures = PrivacyFeature.getSensorFeatures()
        val sensorsUsable = DeviceDetector.supportsSensorPrivacyToggle()

        PrivacyFeature.values().forEach { feature ->
            // Below Android 12 there is no way to switch camera or microphone, so they are
            // dropped here rather than attempted and failed on every lock. The stored
            // preference is deliberately left alone: it is the user's, this device just
            // cannot act on it.
            if (feature in sensorFeatures && !sensorsUsable) {
                return@forEach
            }
            val shouldInclude = if (isLockAction) {
                preferenceManager.getFeatureDisableOnLock(feature)
            } else {
                preferenceManager.getFeatureEnableOnUnlock(feature)
            }
            if (shouldInclude) {
                features.add(feature)
            }
        }

        return features
    }
}
