package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.os.Build
import io.github.dorumrr.privacyflip.util.LogManager
import io.github.dorumrr.privacyflip.util.SingletonHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central manager for privilege execution
 * Automatically detects and uses the best available privilege method:
 * 1. Sui (Magisk module) - best UX for rooted users
 * 2. Root (Magisk/SuperSU) - traditional root
 * 3. Shizuku (ADB) - for non-rooted users
 * 4. Mock (testing) - for development without Shizuku
 */
class PrivilegeManager private constructor(private val context: Context) {
    
    companion object : SingletonHolder<PrivilegeManager, Context>({ context ->
        PrivilegeManager(context.applicationContext)
    }) {
        private const val TAG = "PrivilegeManager"
    }
    
    private val logManager = LogManager.getInstance(context)
    private var currentExecutor: PrivilegeExecutor? = null
    private var currentMethod: PrivilegeMethod = PrivilegeMethod.NONE
    
    /**
     * Initialize and detect the best available privilege method
     */
    suspend fun initialize(): PrivilegeMethod = withContext(Dispatchers.IO) {
        logManager.i(TAG, "Initializing privilege manager...")
        
        // Detect and initialize the best available method
        currentMethod = detectBestPrivilegeMethod()
        
        logManager.i(TAG, "Selected privilege method: ${currentMethod.getDisplayName()}")
        return@withContext currentMethod
    }
    
    /**
     * Detect the best available privilege method
     * Priority: Sui > Root > Shizuku > Mock > None
     */
    private suspend fun detectBestPrivilegeMethod(): PrivilegeMethod {
        // Check if we're in mock flavor by trying to detect build variant
        val isMockFlavor = try {
            val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
            val flavorField = buildConfigClass.getField("FLAVOR")
            flavorField.get(null) == "mock"
        } catch (e: Exception) {
            false
        }

        // For mock flavor, use mock Shizuku
        if (isMockFlavor) {
            logManager.i(TAG, "🎭 Using mock Shizuku executor (mock flavor)")
            currentExecutor = MockShizukuExecutor()
            currentExecutor?.initialize(context)
            return PrivilegeMethod.SHIZUKU
        }
        
        // 1. Try Sui first (best UX for rooted users)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (SuiDetector.detectAndInitialize(context)) {
                    logManager.i(TAG, "✅ Sui available - using Sui executor")
                    currentExecutor = createShizukuExecutor() // Sui uses Shizuku API
                    currentExecutor?.initialize(context)
                    return PrivilegeMethod.SUI
                }
            } catch (e: Exception) {
                logManager.w(TAG, "Error detecting Sui: ${e.message}")
            }
        }
        
        // 2. Try traditional root
        try {
            val rootExecutor = RootExecutor()
            rootExecutor.initialize(context)
            
            if (rootExecutor.isAvailable()) {
                logManager.i(TAG, "✅ Root available - using root executor")
                currentExecutor = rootExecutor
                return PrivilegeMethod.ROOT
            }
        } catch (e: Exception) {
            logManager.w(TAG, "Error detecting root: ${e.message}")
        }
        
        // 3. Try Shizuku (for non-rooted users)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val shizukuExecutor = createShizukuExecutor()
                shizukuExecutor.initialize(context)
                
                if (shizukuExecutor.isAvailable()) {
                    logManager.i(TAG, "✅ Shizuku available - using Shizuku executor")
                    currentExecutor = shizukuExecutor
                    return PrivilegeMethod.SHIZUKU
                }
            } catch (e: Exception) {
                logManager.w(TAG, "Error detecting Shizuku: ${e.message}")
            }
        }
        
        // 4. No privilege available
        logManager.w(TAG, "⚠️ No privilege method available")
        return PrivilegeMethod.NONE
    }
    
    /**
     * Create Shizuku executor based on build flavor
     */
    private fun createShizukuExecutor(): PrivilegeExecutor {
        // Use reflection to avoid compile-time dependency in mock flavor
        return try {
            val clazz = Class.forName("io.github.dorumrr.privacyflip.privilege.ShizukuExecutor")
            clazz.getDeclaredConstructor().newInstance() as PrivilegeExecutor
        } catch (e: Exception) {
            logManager.e(TAG, "Failed to create ShizukuExecutor: ${e.message}")
            throw e
        }
    }
    
    /**
     * Get the current privilege method
     */
    fun getCurrentMethod(): PrivilegeMethod = currentMethod
    
    /**
     * Check if any privilege is available
     */
    suspend fun isPrivilegeAvailable(): Boolean {
        return currentExecutor?.isAvailable() ?: false
    }
    
    /**
     * Check if permission has been granted
     */
    suspend fun isPermissionGranted(): Boolean {
        return currentExecutor?.isPermissionGranted() ?: false
    }
    
    /**
     * Request permission from user
     */
    suspend fun requestPermission(): Boolean {
        return currentExecutor?.requestPermission() ?: false
    }
    
    /**
     * Execute a single command
     */
    suspend fun executeCommand(command: String): CommandResult {
        val executor = currentExecutor
        if (executor == null) {
            return CommandResult.failure("No privilege executor available")
        }
        
        return executor.executeCommand(command)
    }
    
    /**
     * Execute commands with fallback support
     */
    suspend fun executeWithFallbacks(commands: List<String>): CommandResult {
        val executor = currentExecutor
        if (executor == null) {
            return CommandResult.failure("No privilege executor available")
        }
        
        return executor.executeWithFallbacks(commands)
    }
    
    /**
     * Get the UID of the current privilege method
     */
    suspend fun getUid(): Int {
        return currentExecutor?.getUid() ?: -1
    }
    
    /**
     * Force re-detection of privilege method
     * Useful after user installs Shizuku or roots device
     */
    suspend fun redetectPrivilegeMethod(): PrivilegeMethod {
        logManager.i(TAG, "Re-detecting privilege method...")
        currentExecutor?.cleanup()
        currentExecutor = null
        return initialize()
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        currentExecutor?.cleanup()
        currentExecutor = null
        currentMethod = PrivilegeMethod.NONE
    }
}

