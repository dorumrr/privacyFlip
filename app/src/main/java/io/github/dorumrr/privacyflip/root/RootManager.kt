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

class RootManager private constructor() {

    companion object : SingletonHolder<RootManager, Unit>({ RootManager() }) {
        private const val TAG = "privacyFlip-RootManager"
    }

    private var logManager: LogManager? = null
    private var privilegeManager: PrivilegeManager? = null
    private var context: Context? = null

    suspend fun initialize(context: Context) {
        this.context = context
        logManager = LogManager.getInstance(context)
        privilegeManager = PrivilegeManager.getInstance(context)
        privilegeManager?.initialize()
    }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext privilegeManager?.isPrivilegeAvailable() ?: false
        } catch (e: Exception) {
            return@withContext false
        }
    }

    suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        try {
            val granted = privilegeManager?.isPermissionGranted() ?: false
            Log.d(TAG, "isRootGranted() - privilegeManager.isPermissionGranted() returned: $granted")
            return@withContext granted
        } catch (e: Exception) {
            Log.e(TAG, "isRootGranted() - exception: ${e.message}")
            return@withContext false
        }
    }

    suspend fun requestRootPermission(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext privilegeManager?.requestPermission() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permission", e)
            return@withContext false
        }
    }

    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val result = privilegeManager?.executeCommand(command)
            if (result != null) {
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

    suspend fun executeCommands(commands: List<String>): List<CommandResult> = withContext(Dispatchers.IO) {
        commands.map { executeCommand(it) }
    }

    suspend fun executeWithFallbacks(commands: List<String>): CommandResult = withContext(Dispatchers.IO) {
        try {
            val result = privilegeManager?.executeWithFallbacks(commands)
            if (result != null) {
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

    suspend fun forceRootPermissionRequest(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "forceRootPermissionRequest() - calling privilegeManager.requestPermission()...")
            val granted = privilegeManager?.requestPermission() ?: false
            Log.d(TAG, "forceRootPermissionRequest() - privilegeManager.requestPermission() returned: $granted")

            // Double-check the actual state (this is the source of truth)
            val actualGranted = privilegeManager?.isPermissionGranted() ?: false
            Log.d(TAG, "forceRootPermissionRequest() - double-check: privilegeManager.isPermissionGranted() = $actualGranted")

            // Use the double-check value as it's more reliable
            // requestPermission() might return false due to timing, but permission could still be granted
            if (granted != actualGranted) {
                Log.w(TAG, "forceRootPermissionRequest() - MISMATCH: requestPermission=$granted, isPermissionGranted=$actualGranted - using actualGranted")
            }

            return@withContext actualGranted
        } catch (e: Exception) {
            Log.e(TAG, "forceRootPermissionRequest() - error: ${e.message}", e)
            return@withContext false
        }
    }

    fun getPrivilegeMethod(): PrivilegeMethod {
        return privilegeManager?.getCurrentMethod() ?: PrivilegeMethod.NONE
    }

    suspend fun redetectPrivilegeMethod(): PrivilegeMethod {
        return privilegeManager?.redetectPrivilegeMethod() ?: PrivilegeMethod.NONE
    }

    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            buildId = Build.ID
        )
    }
}

data class CommandResult(
    val success: Boolean,
    val output: List<String>,
    val error: String? = null,
    val exitCode: Int = -1
)

data class DeviceInfo(
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val buildId: String
)
