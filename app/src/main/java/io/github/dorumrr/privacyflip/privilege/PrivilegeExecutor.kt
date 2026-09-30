package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.data.PrivacyFeature

/**
 * Interface for executing privileged commands through different backends
 * (Root, Shizuku, Sui, or Mock)
 */
interface PrivilegeExecutor {
    
    /**
     * Initialize the executor with application context
     */
    suspend fun initialize(context: Context)
    
    /**
     * Check if this privilege method is available on the device
     */
    suspend fun isAvailable(): Boolean
    
    /**
     * Check if permission has been granted for this privilege method
     */
    suspend fun isPermissionGranted(): Boolean
    
    /**
     * Request permission from the user
     * @return true if permission was granted, false otherwise
     */
    suspend fun requestPermission(): Boolean
    
    /**
     * Execute a single command with privilege
     * @param command The shell command to execute
     * @return CommandResult containing success status, output, and error
     */
    suspend fun executeCommand(command: String): CommandResult
    
    /**
     * Execute multiple commands with fallback support
     * Tries each command in order until one succeeds
     * @param commands List of commands to try
     * @return CommandResult from the first successful command, or a failure naming every command's reason
     */
    suspend fun executeWithFallbacks(commands: List<String>): CommandResult {
        if (commands.isEmpty()) {
            return CommandResult.failure("No commands provided")
        }

        val failures = mutableListOf<Pair<String, CommandResult>>()

        for (command in commands) {
            // A command that THROWS must not abort the chain. A dead binder, or a command this
            // Android version does not know, is exactly when the next fallback is the one that
            // would have worked.
            val result = try {
                executeCommand(command)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CommandResult.failure(e.message ?: "${e::class.simpleName} while running: $command")
            }
            if (result.success) {
                return result
            }
            failures += command to result
        }

        val last = failures.last().second
        return CommandResult(
            success = false,
            output = last.output,
            error = failures.joinToString("; ") { (command, result) -> describeFailure(command, result) },
            exitCode = last.exitCode
        )
    }
    
    /**
     * Switch a feature through this backend's own API rather than a shell command.
     *
     * Dhizuku holds Device Owner rights, not shell rights, so the commands the toggles build are
     * denied there and a real API call is the only route.
     *
     * @return null when this backend has no such route, meaning the caller should run the commands
     */
    suspend fun setFeatureState(feature: PrivacyFeature, enable: Boolean): CommandResult? = null

    /**
     * Why this backend can never act on a feature, or null when it can. A backend that cannot
     * says so, so the user is told the thing is impossible here rather than shown a bare failure.
     */
    fun unsupportedReason(feature: PrivacyFeature): String? = null

    /**
     * What unlock always does to a feature on this backend, whatever "Enable on unlock" says: true
     * turns it on, false can never turn it on, null follows the setting.
     */
    fun fixedAtUnlock(feature: PrivacyFeature): Boolean? = null

    /**
     * Read a feature's state through this backend's own API.
     *
     * @return null when this backend has no such route, meaning the caller should run its
     *         status commands
     */
    suspend fun readFeatureState(feature: PrivacyFeature): FeatureState? = null

    /**
     * Get the privilege method this executor provides
     */
    fun getPrivilegeMethod(): PrivilegeMethod
    
    /**
     * Clean up resources when executor is no longer needed
     */
    fun cleanup()
}

private const val REASON_LINES = 3
private const val REASON_CHARS = 200

// Capped: a stack trace on stdout would otherwise flood the debug log.
internal fun describeFailure(command: String, result: CommandResult): String {
    val exit = result.exitCode?.let { " (exit $it)" }.orEmpty()
    val reason = result.error?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.take(REASON_LINES)
        ?.joinToString(" / ")
        ?.take(REASON_CHARS)
        ?.ifEmpty { null }
    return "$command$exit: ${reason ?: "no output"}"
}
