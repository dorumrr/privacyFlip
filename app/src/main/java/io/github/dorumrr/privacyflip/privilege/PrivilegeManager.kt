package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.os.Build
import io.github.dorumrr.privacyflip.util.LogManager
import io.github.dorumrr.privacyflip.util.SingletonHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrivilegeManager private constructor(private val context: Context) {

    companion object : SingletonHolder<PrivilegeManager, Context>({ context ->
        PrivilegeManager(context.applicationContext)
    }) {
        private const val TAG = "privacyFlip-PrivilegeManager"
    }

    private val logManager = LogManager.getInstance(context)
    private var currentExecutor: PrivilegeExecutor? = null
    private var currentMethod: PrivilegeMethod = PrivilegeMethod.NONE

    suspend fun initialize(): PrivilegeMethod = withContext(Dispatchers.IO) {
        currentMethod = detectBestPrivilegeMethod()
        return@withContext currentMethod
    }
    
    private suspend fun detectBestPrivilegeMethod(): PrivilegeMethod {
        // Priority: Sui > Root > Dhizuku > Shizuku
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (SuiDetector.detectAndInitialize(context)) {
                    logManager.d(TAG, "Sui detected and initialized successfully")
                    currentExecutor = createShizukuExecutor()
                    currentExecutor?.initialize(context)
                    return PrivilegeMethod.SUI
                }
            } catch (e: Exception) {
                logManager.d(TAG, "Sui detection failed: ${e.message}")
                // Continue to next method
            }
        }

        try {
            val rootExecutor = RootExecutor()
            rootExecutor.initialize(context)

            if (rootExecutor.isAvailable()) {
                logManager.d(TAG, "Root detected and available")
                currentExecutor = rootExecutor
                return PrivilegeMethod.ROOT
            } else {
                logManager.d(TAG, "Root not available (su binary not found)")
            }
        } catch (e: Exception) {
            logManager.e(TAG, "Root detection failed: ${e.message}")
            // Continue to next method
        }

        // Try Dhizuku (Device Owner)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val dhizukuExecutor = DhizukuExecutor()
                dhizukuExecutor.initialize(context)

                if (dhizukuExecutor.isAvailable()) {
                    logManager.d(TAG, "Dhizuku detected and available")
                    currentExecutor = dhizukuExecutor
                    return PrivilegeMethod.DHIZUKU
                } else {
                    logManager.d(TAG, "Dhizuku not available (service not running)")
                }
            } catch (e: Exception) {
                logManager.d(TAG, "Dhizuku detection failed: ${e.message}")
                // Continue to next method
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val shizukuExecutor = createShizukuExecutor()
                shizukuExecutor.initialize(context)

                if (shizukuExecutor.isAvailable()) {
                    logManager.d(TAG, "Shizuku detected and available")
                    currentExecutor = shizukuExecutor
                    return PrivilegeMethod.SHIZUKU
                } else {
                    logManager.d(TAG, "Shizuku not available (binder not responding)")
                }
            } catch (e: Exception) {
                logManager.d(TAG, "Shizuku detection failed: ${e.message}")
                // No Shizuku available
            }
        }

        logManager.w(TAG, "No privilege method available")
        return PrivilegeMethod.NONE
    }
    
    private fun createShizukuExecutor(): PrivilegeExecutor {
        return ShizukuExecutor()
    }

    fun getCurrentMethod(): PrivilegeMethod = currentMethod

    suspend fun isPrivilegeAvailable(): Boolean {
        return currentExecutor?.isAvailable() ?: false
    }

    suspend fun isPermissionGranted(): Boolean {
        val granted = currentExecutor?.isPermissionGranted() ?: false
        android.util.Log.d(TAG, "PrivilegeManager.isPermissionGranted() - currentMethod: $currentMethod, result: $granted")
        return granted
    }

    suspend fun requestPermission(): Boolean {
        android.util.Log.d(TAG, "PrivilegeManager.requestPermission() - currentMethod: $currentMethod, calling executor...")
        val granted = currentExecutor?.requestPermission() ?: false
        android.util.Log.d(TAG, "PrivilegeManager.requestPermission() - executor returned: $granted")
        return granted
    }

    suspend fun executeCommand(command: String): CommandResult {
        val executor = currentExecutor
        if (executor == null) {
            return CommandResult.failure("No privilege executor available")
        }

        return executor.executeCommand(command)
    }

    suspend fun executeWithFallbacks(commands: List<String>): CommandResult {
        val executor = currentExecutor
        if (executor == null) {
            return CommandResult.failure("No privilege executor available")
        }

        return executor.executeWithFallbacks(commands)
    }

    suspend fun getUid(): Int {
        return currentExecutor?.getUid() ?: -1
    }

    suspend fun redetectPrivilegeMethod(): PrivilegeMethod {
        currentExecutor?.cleanup()
        currentExecutor = null
        return initialize()
    }

    fun cleanup() {
        currentExecutor?.cleanup()
        currentExecutor = null
        currentMethod = PrivilegeMethod.NONE
    }
}

