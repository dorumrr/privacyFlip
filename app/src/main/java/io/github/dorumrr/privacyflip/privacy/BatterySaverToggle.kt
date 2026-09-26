package io.github.dorumrr.privacyflip.privacy

import android.os.Build
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager

open class BatterySaverToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.BATTERY_SAVER
    override val featureName = "Battery Saver"

    override val enableCommands = commandsFor(1)
    override val disableCommands = commandsFor(0)

    // The power command first: on a charger Android refuses it without touching low_power, while a
    // written low_power=1 stays armed. Before Android 8 the command is a silent no-op, so the key only.
    private fun commandsFor(mode: Int): List<CommandSet> {
        val key = CommandSet("settings put global low_power $mode", description = "Settings database")
        if (Build.VERSION.SDK_INT < 26) return listOf(key)
        return listOf(CommandSet("cmd power set-mode $mode", description = "Power command"), key)
    }

    // The power service's own state: "Enabled=" under the state machine from Android 9, "mLowPowerModeEnabled=" on 8.
    // The key last, for a backend that may not dump services: an unreadable enable is never claimed, so never undone.
    override val statusCommands = listOf(
        CommandSet("dumpsys power | grep -A3 'Battery saver state machine:' | grep -m1 'Enabled='", description = "Battery saver state"),
        CommandSet("dumpsys power | grep -m1 'mLowPowerModeEnabled='", description = "Battery saver state (Android 8)"),
        CommandSet("settings get global low_power", description = "Stored battery saver state")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        Regex("enabled=(true|false)").find(output.lowercase())?.let {
            return if (it.groupValues[1] == "true") FeatureState.ENABLED else FeatureState.DISABLED
        }
        return when (output.trim()) {
            "1" -> FeatureState.ENABLED
            "0" -> FeatureState.DISABLED
            else -> FeatureState.UNKNOWN
        }
    }
}
