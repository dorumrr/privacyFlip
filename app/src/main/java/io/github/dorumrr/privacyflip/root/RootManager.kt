package io.github.dorumrr.privacyflip.root

import android.content.Context
import android.util.Log
import io.github.dorumrr.privacyflip.privilege.CommandResult
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

    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            return@withContext privilegeManager?.executeCommand(command)
                ?: CommandResult.failure("Privilege manager not initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command", e)
            return@withContext CommandResult.failure(e.message ?: "Unknown error")
        }
    }

    suspend fun executeWithFallbacks(commands: List<String>): CommandResult = withContext(Dispatchers.IO) {
        try {
            return@withContext privilegeManager?.executeWithFallbacks(commands)
                ?: CommandResult.failure("Privilege manager not initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing commands with fallbacks", e)
            return@withContext CommandResult.failure(e.message ?: "Unknown error")
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

}

