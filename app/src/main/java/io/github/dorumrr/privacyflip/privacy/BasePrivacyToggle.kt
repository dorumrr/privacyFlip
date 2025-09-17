package io.github.dorumrr.privacyflip.privacy

import android.util.Log
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager

abstract class BasePrivacyToggle(
    protected val rootManager: RootManager
) : PrivacyToggle {

    protected val TAG: String = this::class.simpleName ?: "BasePrivacyToggle"

    protected abstract val enableCommands: List<CommandSet>
    protected abstract val disableCommands: List<CommandSet>
    protected abstract val statusCommands: List<CommandSet>
    protected abstract val featureName: String
    
    override suspend fun isSupported(): FeatureSupport {
        return try {
            if (!rootManager.isRootPermissionGranted()) {
                return FeatureSupport.FULLY_SUPPORTED
            }

            val result = rootManager.executeWithFallbacks(statusCommands.map { it.primary })
            if (result.success) {
                FeatureSupport.FULLY_SUPPORTED
            } else {
                Log.w(TAG, "$featureName support check failed: ${result.error}")
                FeatureSupport.BASIC_SUPPORT
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking $featureName support", e)
            FeatureSupport.UNSUPPORTED
        }
    }
    
    override suspend fun enable(): PrivacyResult {
        return executeCommand(enableCommands, "enable")
    }
    
    override suspend fun disable(): PrivacyResult {
        return executeCommand(disableCommands, "disable")
    }
    
    private suspend fun executeCommand(commands: List<CommandSet>, action: String): PrivacyResult {
        return try {
            Log.d(TAG, "${action.replaceFirstChar { it.uppercase() }} $featureName")
            val result = rootManager.executeWithFallbacks(commands.map { it.primary })
            
            PrivacyResult(
                feature = feature,
                success = result.success,
                message = if (result.success) {
                    "$featureName ${action}d"
                } else {
                    "Failed to $action $featureName: ${result.error}"
                },
                commandUsed = if (result.success) commands.first().primary else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error ${action}ing $featureName", e)
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
    
    protected open fun parseStatusOutput(output: String): FeatureState {
        return when {
            output.contains("1") || output.contains("enabled") || output.contains("on") -> {
                FeatureState.ENABLED
            }
            output.contains("0") || output.contains("disabled") || output.contains("off") -> {
                FeatureState.DISABLED
            }
            else -> {
                Log.w(TAG, "$featureName status unknown, output: '$output'")
                FeatureState.UNKNOWN
            }
        }
    }
}


