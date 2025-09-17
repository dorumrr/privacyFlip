package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager

class BluetoothToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.BLUETOOTH
    override val featureName = "Bluetooth"

    override val enableCommands = listOf(
        CommandSet("svc bluetooth enable", description = "Service control method"),
        CommandSet("settings put global bluetooth_on 1", description = "Settings database method"),
        CommandSet("am start -a android.bluetooth.adapter.action.REQUEST_ENABLE",
                  description = "Intent method")
    )

    override val disableCommands = listOf(
        CommandSet("svc bluetooth disable", description = "Service control method"),
        CommandSet("settings put global bluetooth_on 0", description = "Settings database method"),
        CommandSet("am start -a android.bluetooth.adapter.action.REQUEST_DISABLE",
                  description = "Intent method")
    )

    override val statusCommands = listOf(
        CommandSet("settings get global bluetooth_on", description = "Check Bluetooth status"),
        CommandSet("dumpsys bluetooth_manager | grep 'enabled'", description = "Dumpsys method")
    )
}
