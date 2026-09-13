package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.root.RootManager

class MicrophoneToggle(rootManager: RootManager) :
    SensorPrivacyToggle(rootManager, sensorName = "microphone", sensorId = 1) {

    override val feature = PrivacyFeature.MICROPHONE
    override val featureName = "Microphone"
}
