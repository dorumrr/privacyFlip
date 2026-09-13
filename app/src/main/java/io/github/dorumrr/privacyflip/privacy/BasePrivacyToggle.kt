package io.github.dorumrr.privacyflip.privacy

import android.util.Log
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager

abstract class BasePrivacyToggle(
    protected val rootManager: RootManager
) : PrivacyToggle {

    protected val TAG: String = "privacyFlip-${this::class.simpleName ?: "BasePrivacyToggle"}"

    protected abstract val enableCommands: List<CommandSet>
    protected abstract val disableCommands: List<CommandSet>
    protected abstract val statusCommands: List<CommandSet>
    protected abstract val featureName: String
    
    override suspend fun enable(): PrivacyResult {
        return executeCommand(enableCommands, "enable")
    }
    
    override suspend fun disable(): PrivacyResult {
        return executeCommand(disableCommands, "disable")
    }
    
    private suspend fun executeCommand(commands: List<CommandSet>, action: String): PrivacyResult {
        return try {
            Log.d(TAG, "📍 ${action.replaceFirstChar { it.uppercase() }} $featureName - attempting ${commands.size} command(s)")
            commands.forEachIndexed { index, cmd ->
                Log.d(TAG, "  Command ${index + 1}: ${cmd.primary}")
            }

            val result = rootManager.executeWithFallbacks(commands.map { it.primary })

            Log.d(TAG, "📊 Command execution result: success=${result.success}, exitCode=${result.exitCode}")
            if (result.output.isNotEmpty()) {
                Log.d(TAG, "📊 Command output: ${result.output.joinToString("; ")}")
            }
            if (result.error != null) {
                Log.w(TAG, "⚠️ Command error: ${result.error}")
            }

            PrivacyResult(
                feature = feature,
                success = result.success,
                message = if (result.success) {
                    "$featureName ${action}d"
                } else {
                    "Failed to $action $featureName: ${result.error}"
                },
                // Every command that could have run, not one guessed winner. executeWithFallbacks
                // does not report which of them succeeded, and this used to be null on exactly
                // the failure path that is the only thing reading it - so the diagnostic said
                // "Command used: null" every single time it mattered.
                commandUsed = commands.joinToString(" | ") { it.primary }
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ EXCEPTION ${action}ing $featureName", e)
            PrivacyResult(
                feature = feature,
                success = false,
                message = "Exception ${action}ing $featureName: ${e.message}"
            )
        }
    }
    
    override suspend fun getCurrentState(): FeatureState {
        return try {
            val result = rootManager.executeWithFallbacks(statusCommands.map { it.primary })
            
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
}


