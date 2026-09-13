package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.root.RootManager

class CameraToggle(rootManager: RootManager) :
    SensorPrivacyToggle(rootManager, sensorName = "camera", sensorId = 2) {

    override val feature = PrivacyFeature.CAMERA
    override val featureName = "Camera"
}
