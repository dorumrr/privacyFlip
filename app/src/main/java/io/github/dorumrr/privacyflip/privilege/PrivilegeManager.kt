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
        private const val TAG = "PrivilegeManager"
    }

    private val logManager = LogManager.getInstance(context)
    private var currentExecutor: PrivilegeExecutor? = null
    private var currentMethod: PrivilegeMethod = PrivilegeMethod.NONE

    suspend fun initialize(): PrivilegeMethod = withContext(Dispatchers.IO) {
        currentMethod = detectBestPrivilegeMethod()
        return@withContext currentMethod
    }
    
    private suspend fun detectBestPrivilegeMethod(): PrivilegeMethod {
        val isMockFlavor = try {
            val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
            val flavorField = buildConfigClass.getField("FLAVOR")
            flavorField.get(null) == "mock"
        } catch (e: Exception) {
            false
        }

        if (isMockFlavor) {
            currentExecutor = MockShizukuExecutor()
            currentExecutor?.initialize(context)
            return PrivilegeMethod.SHIZUKU
        }

        // Priority: Sui > Root > Shizuku
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (SuiDetector.detectAndInitialize(context)) {
                    currentExecutor = createShizukuExecutor()
                    currentExecutor?.initialize(context)
                    return PrivilegeMethod.SUI
                }
            } catch (e: Exception) {
                // Continue to next method
            }
        }

        try {
            val rootExecutor = RootExecutor()
            rootExecutor.initialize(context)

            if (rootExecutor.isAvailable()) {
                currentExecutor = rootExecutor
                return PrivilegeMethod.ROOT
            }
        } catch (e: Exception) {
            // Continue to next method
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val shizukuExecutor = createShizukuExecutor()
                shizukuExecutor.initialize(context)

                if (shizukuExecutor.isAvailable()) {
                    currentExecutor = shizukuExecutor
                    return PrivilegeMethod.SHIZUKU
                }
            } catch (e: Exception) {
                // No Shizuku available
            }
        }

        return PrivilegeMethod.NONE
    }
    
    private fun createShizukuExecutor(): PrivilegeExecutor {
        return try {
            val clazz = Class.forName("io.github.dorumrr.privacyflip.privilege.ShizukuExecutor")
            clazz.getDeclaredConstructor().newInstance() as PrivilegeExecutor
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to create ShizukuExecutor: ${e.message}")
            throw e
        }
    }

    fun getCurrentMethod(): PrivilegeMethod = currentMethod

    suspend fun isPrivilegeAvailable(): Boolean {
        return currentExecutor?.isAvailable() ?: false
    }

    suspend fun isPermissionGranted(): Boolean {
        return currentExecutor?.isPermissionGranted() ?: false
    }

    suspend fun requestPermission(): Boolean {
        return currentExecutor?.requestPermission() ?: false
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

