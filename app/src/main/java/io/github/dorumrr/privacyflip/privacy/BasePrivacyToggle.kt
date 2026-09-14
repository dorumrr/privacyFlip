package io.github.dorumrr.privacyflip.privacy

import android.util.Log
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.privilege.CommandResult
import io.github.dorumrr.privacyflip.root.RootManager
import kotlinx.coroutines.delay

abstract class BasePrivacyToggle(
    protected val rootManager: RootManager
) : PrivacyToggle {

    protected val TAG: String = "privacyFlip-${this::class.simpleName ?: "BasePrivacyToggle"}"

    protected abstract val enableCommands: List<CommandSet>
    protected abstract val disableCommands: List<CommandSet>
    protected abstract val statusCommands: List<CommandSet>
    protected abstract val featureName: String

    override suspend fun enable(): PrivacyResult =
        executeCommand(enableCommands, "enable", FeatureState.ENABLED)

    override suspend fun disable(): PrivacyResult =
        executeCommand(disableCommands, "disable", FeatureState.DISABLED)

    // The single point where this class reaches the privileged shell, so a test can stand in for
    // the shell without a mocking framework.
    protected open suspend fun runCommands(commands: List<String>): CommandResult =
        rootManager.executeWithFallbacks(commands)

    // Backends that are not a shell can switch some features through a real API instead. null
    // means this backend has no such route, so the commands below are used as before.
    protected open suspend fun runFeatureAction(enable: Boolean): CommandResult? =
        rootManager.setFeatureState(feature, enable)

    private suspend fun executeCommand(
        commands: List<CommandSet>,
        action: String,
        intended: FeatureState
    ): PrivacyResult {
        return try {
            Log.d(TAG, "📍 ${action.replaceFirstChar { it.uppercase() }} $featureName - attempting ${commands.size} command(s)")
            commands.forEachIndexed { index, cmd ->
                Log.d(TAG, "  Command ${index + 1}: ${cmd.primary}")
            }

            val viaApi = runFeatureAction(intended == FeatureState.ENABLED)
            val result = viaApi ?: runCommands(commands.map { it.primary })

            Log.d(TAG, "📊 Command execution result: success=${result.success}, exitCode=${result.exitCode}")
            if (result.output.isNotEmpty()) {
                Log.d(TAG, "📊 Command output: ${result.output.joinToString("; ")}")
            }
            if (result.error != null) {
                Log.w(TAG, "⚠️ Command error: ${result.error}")
            }

            // Every command that could have run, not one guessed winner: executeWithFallbacks does
            // not report which of them succeeded.
            // null when the backend's own API did it: naming shell commands that never ran put
            // them in the user-facing log as "Commands attempted".
            val commandUsed = if (viaApi != null) null else commands.joinToString(" | ") { it.primary }

            if (!result.success) {
                return PrivacyResult(
                    feature = feature,
                    success = false,
                    // A failing command often prints nothing at all, and "...: null" told the user
                    // less than saying so plainly.
                    message = "Failed to $action $featureName: ${result.error ?: "the command gave no reason"}",
                    commandUsed = commandUsed
                )
            }

            when (confirmReachedState(intended)) {
                Confirmation.CONFIRMED -> PrivacyResult(
                    feature = feature,
                    success = true,
                    message = "$featureName ${action}d",
                    commandUsed = commandUsed
                )
                Confirmation.CONTRADICTED -> PrivacyResult(
                    feature = feature,
                    success = false,
                    message = "$featureName was not ${action}d: the system accepted the command and left the state unchanged",
                    commandUsed = commandUsed
                )
                Confirmation.UNREADABLE -> PrivacyResult(
                    feature = feature,
                    success = true,
                    message = "$featureName ${action}d, but the state could not be read back",
                    commandUsed = commandUsed
                )
                Confirmation.ABSENT -> PrivacyResult(
                    feature = feature,
                    success = false,
                    message = "$featureName is not available on this device",
                    commandUsed = commandUsed
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ EXCEPTION ${action}ing $featureName", e)
            PrivacyResult(
                feature = feature,
                success = false,
                message = "Exception ${action}ing $featureName: ${e.message}"
            )
        }
    }

    /**
     * A privileged command can exit 0 and change nothing: Android accepts a sensor privacy change
     * while the keyguard is up and then ignores it. Only the state itself is proof.
     *
     * A state that cannot be READ is reported as unconfirmed rather than as a failure, so an
     * unreadable status command never turns a real success into a false "it did not work".
     */
    private suspend fun confirmReachedState(intended: FeatureState): Confirmation {
        repeat(READ_BACK_ATTEMPTS) { attempt ->
            when (val actual = getCurrentState()) {
                intended -> return Confirmation.CONFIRMED
                // UNAVAILABLE is a definite "not present here", not an unreadable one, so it is
                // never reported as an unconfirmed success.
                FeatureState.UNAVAILABLE -> return Confirmation.ABSENT
                FeatureState.UNKNOWN, FeatureState.ERROR -> {
                    Log.w(TAG, "⚠️ $featureName reads as $actual - cannot confirm, reporting unconfirmed")
                    return Confirmation.UNREADABLE
                }
                else -> if (attempt < READ_BACK_ATTEMPTS - 1) delay(READ_BACK_GAP_MS)
            }
        }
        Log.w(TAG, "⚠️ $featureName never reached $intended - command was accepted but ignored")
        return Confirmation.CONTRADICTED
    }

    private enum class Confirmation { CONFIRMED, CONTRADICTED, UNREADABLE, ABSENT }

    // The API route's twin for reads. Without it a backend whose WRITE worked would still read
    // back through a shell it cannot use, and report its own successful change as a failure.
    protected open suspend fun readFeatureState(): FeatureState? =
        rootManager.readFeatureState(feature)

    override suspend fun getCurrentState(): FeatureState {
        return try {
            // ENABLED or DISABLED only. Letting UNKNOWN/ERROR/UNAVAILABLE stand in for a status
            // read skips the commands below, and confirmReachedState then maps it to UNREADABLE,
            // which reports success for a change nothing observed.
            readFeatureState()
                ?.takeIf { it == FeatureState.ENABLED || it == FeatureState.DISABLED }
                ?.let { return it }

            val result = runCommands(statusCommands.map { it.primary })

            if (!result.success) {
                return FeatureState.UNKNOWN
            }

            val output = result.output.joinToString(" ").lowercase()

            parseStatusOutput(output)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting $featureName state", e)
            FeatureState.ERROR
        }
    }

    // Abstract, not a default body: every subclass reads a different command's output, and the
    // generic fallback that used to live here duplicated StatusParsingUtils.parseStandardOutput()
    // while never running in production (all subclasses override). A new subclass that forgets to
    // parse its own output now fails to compile, instead of silently getting a parser nothing
    // tested against its command.
    protected abstract fun parseStatusOutput(output: String): FeatureState

    private companion object {
        // Measured on the device: WiFi, Bluetooth and both sensors all reported their new state on
        // the FIRST read, so the second try is headroom for a slower phone, not the expected path.
        const val READ_BACK_ATTEMPTS = 2
        const val READ_BACK_GAP_MS = 150L
    }
}
