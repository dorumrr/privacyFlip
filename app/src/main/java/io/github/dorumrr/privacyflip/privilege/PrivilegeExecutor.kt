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
    suspend fun executeWithFallbacks(commands: List<String>): CommandResult
    
    /**
     * Get the privilege method this executor provides
     */
    fun getPrivilegeMethod(): PrivilegeMethod
    
    /**
     * Get the UID (user ID) this executor runs as
     * @return 0 for root, 2000 for ADB/Shizuku, -1 for unknown
     */
    suspend fun getUid(): Int
    
    /**
     * Clean up resources when executor is no longer needed
     */
    fun cleanup()
}

