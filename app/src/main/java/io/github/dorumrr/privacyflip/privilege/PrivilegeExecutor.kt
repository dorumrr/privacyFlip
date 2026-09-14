package io.github.dorumrr.privacyflip.privilege

import android.content.Context

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
     * @return CommandResult from the first successful command, or last failure
     */
    suspend fun executeWithFallbacks(commands: List<String>): CommandResult {
        if (commands.isEmpty()) {
            return CommandResult.failure("No commands provided")
        }

        var lastResult: CommandResult? = null

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
            lastResult = result
        }

        return lastResult ?: CommandResult.failure("All commands failed")
    }
    
    /**
     * Get the privilege method this executor provides
     */
    fun getPrivilegeMethod(): PrivilegeMethod
    
    /**
     * Clean up resources when executor is no longer needed
     */
    fun cleanup()
}

