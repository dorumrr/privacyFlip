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

        PrivacyFeature.values().forEach { feature ->
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
