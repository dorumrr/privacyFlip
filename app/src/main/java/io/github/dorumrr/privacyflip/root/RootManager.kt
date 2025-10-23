package io.github.dorumrr.privacyflip.root

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.dorumrr.privacyflip.privilege.PrivilegeManager
import io.github.dorumrr.privacyflip.privilege.PrivilegeMethod
import io.github.dorumrr.privacyflip.util.LogManager
import io.github.dorumrr.privacyflip.util.SingletonHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RootManager - Backward compatibility wrapper around PrivilegeManager
 * This class maintains the existing API while delegating to the new privilege abstraction layer
 * that supports Root, Shizuku, and Sui
 */
class RootManager private constructor() {

    companion object : SingletonHolder<RootManager, Unit>({ RootManager() }) {
        private const val TAG = "RootManager"
    }

    private var logManager: LogManager? = null
    private var privilegeManager: PrivilegeManager? = null
    private var context: Context? = null

    suspend fun initialize(context: Context) {
        this.context = context
        logManager = LogManager.getInstance(context)

        // Initialize the new privilege manager
        privilegeManager = PrivilegeManager.getInstance(context)
        val method = privilegeManager?.initialize()

        logManager?.i(TAG, "Initialized with privilege method: ${method?.getDisplayName()}")
    }

    /**
     * Check if any privilege method is available (Root, Shizuku, or Sui)
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext privilegeManager?.isPrivilegeAvailable() ?: false
        } catch (e: Exception) {
            logManager?.e(TAG, "Error checking privilege availability: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Check if permission has been granted for the current privilege method
     */
    suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext privilegeManager?.isPermissionGranted() ?: false
        } catch (e: Exception) {
            logManager?.e(TAG, "Error checking permission: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Request permission from the user for the current privilege method
     */
    suspend fun requestRootPermission(): Boolean = withContext(Dispatchers.IO) {
        try {
            val granted = privilegeManager?.requestPermission() ?: false
            if (!granted) {
                logManager?.w(TAG, "Permission denied")
            }
            return@withContext granted
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permission", e)
            return@withContext false
        }
    }

    /**
     * Execute a single command with the current privilege method
     */
    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val result = privilegeManager?.executeCommand(command)
            if (result != null) {
                // Convert privilege.CommandResult to root.CommandResult
                return@withContext CommandResult(
                    success = result.success,
                    output = result.output,
                    error = result.error,
                    exitCode = result.exitCode
                )
            }

            return@withContext CommandResult(
                success = false,
                output = emptyList(),
                error = "Privilege manager not initialized"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command", e)
            return@withContext CommandResult(
                success = false,
                output = emptyList(),
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Execute multiple commands sequentially
     */
    suspend fun executeCommands(commands: List<String>): List<CommandResult> = withContext(Dispatchers.IO) {
        commands.map { executeCommand(it) }
    }

    /**
     * Execute commands with fallback support - tries each command until one succeeds
     */
    suspend fun executeWithFallbacks(commands: List<String>): CommandResult = withContext(Dispatchers.IO) {
        try {
            val result = privilegeManager?.executeWithFallbacks(commands)
            if (result != null) {
                // Convert privilege.CommandResult to root.CommandResult
                return@withContext CommandResult(
                    success = result.success,
                    output = result.output,
                    error = result.error,
                    exitCode = result.exitCode
                )
            }

            return@withContext CommandResult(
                success = false,
                output = emptyList(),
                error = "Privilege manager not initialized"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing commands with fallbacks", e)
            return@withContext CommandResult(
                success = false,
                output = emptyList(),
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Force re-request of permission
     */
    suspend fun forceRootPermissionRequest(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext privilegeManager?.requestPermission() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error in force permission request", e)
            return@withContext false
        }
    }

    /**
     * Get the current privilege method being used
     */
    fun getPrivilegeMethod(): PrivilegeMethod {
        return privilegeManager?.getCurrentMethod() ?: PrivilegeMethod.NONE
    }

    /**
     * Force re-detection of privilege method
     * Useful after user installs Shizuku or roots device
     */
    suspend fun redetectPrivilegeMethod(): PrivilegeMethod {
        return privilegeManager?.redetectPrivilegeMethod() ?: PrivilegeMethod.NONE
    }

    /**
     * Get device information
     */
    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            buildId = Build.ID
        )
    }
}

/**
 * Result of executing a command
 * Kept for backward compatibility - delegates to privilege.CommandResult
 */
data class CommandResult(
    val success: Boolean,
    val output: List<String>,
    val error: String? = null,
    val exitCode: Int = -1
)

/**
 * Device information
 */
data class DeviceInfo(
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val buildId: String
)
