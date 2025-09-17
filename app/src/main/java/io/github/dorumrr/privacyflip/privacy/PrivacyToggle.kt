package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.data.FeatureSupport
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.data.PrivacyResult

interface PrivacyToggle {
    val feature: PrivacyFeature
    
    suspend fun isSupported(): FeatureSupport
    
    suspend fun enable(): PrivacyResult
    
    suspend fun disable(): PrivacyResult
    
    suspend fun getCurrentState(): FeatureState
}
