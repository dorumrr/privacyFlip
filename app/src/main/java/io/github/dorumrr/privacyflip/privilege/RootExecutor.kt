package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import com.topjohnwu.superuser.Shell
import io.github.dorumrr.privacyflip.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Executor that uses traditional root access via libsu (Magisk/SuperSU)
 */
class RootExecutor : PrivilegeExecutor {
    
    companion object {
        private const val TAG = "RootExecutor"
        
        @Volatile
        private var isShellInitialized = false
    }
    
    private var logManager: LogManager? = null
    private var _isRootAvailable: Boolean? = null
    private var _rootPermissionGranted: Boolean? = null
    
    override suspend fun initialize(context: Context) {
        logManager = LogManager.getInstance(context)
        
        synchronized(this) {
            if (!isShellInitialized) {
                try {
                    Shell.enableVerboseLogging = false
                    Shell.setDefaultBuilder(
                        Shell.Builder.create()
                            .setFlags(Shell.FLAG_REDIRECT_STDERR)
                            .setTimeout(30) // 30 seconds to match Shizuku timeout - gives user time to respond to Magisk prompt
                    )
                    isShellInitialized = true
                    logManager?.i(TAG, "Root shell initialized successfully")
                } catch (e: Exception) {
                    logManager?.w(TAG, "Shell already initialized or failed to initialize: ${e.message}")
                    isShellInitialized = true
                }
            }
        }
    }
    
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (_isRootAvailable != null) {
            return@withContext _isRootAvailable!!
        }
        
        try {
            val suPaths = listOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/vendor/bin/su",
                "/system/app/Superuser.apk",
                "/system/app/SuperSU.apk"
            )
            
            val rootExists = suPaths.any { File(it).exists() } || Shell.isAppGrantedRoot() == true
            _isRootAvailable = rootExists
            
            logManager?.i(TAG, "Root availability check: $rootExists")
            return@withContext rootExists
        } catch (e: Exception) {
            logManager?.e(TAG, "Error checking root availability: ${e.message}")
            _isRootAvailable = false
            return@withContext false
        }
    }
    
    override suspend fun isPermissionGranted(): Boolean = withContext(Dispatchers.IO) {
        // CRITICAL: Always check fresh status from Shell.isAppGrantedRoot()
        // Don't rely on cached value because permission can be denied/revoked at any time
        try {
            val hasRoot = Shell.isAppGrantedRoot() == true
            _rootPermissionGranted = hasRoot
            logManager?.i(TAG, "Root permission check: $hasRoot")
            return@withContext hasRoot
        } catch (e: Exception) {
            logManager?.e(TAG, "Error checking root permission: ${e.message}")
            _rootPermissionGranted = false
            return@withContext false
        }
    }
    
    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        try {
            logManager?.i(TAG, "Requesting root permission...")

            // Execute a simple command to trigger root permission dialog
            val result = Shell.cmd("id").exec()

            // CRITICAL: Don't rely on result.isSuccess alone - it might succeed without root
            // Instead, explicitly check if root was actually granted using Shell.isAppGrantedRoot()
            val granted = Shell.isAppGrantedRoot() == true

            _rootPermissionGranted = granted

            if (granted) {
                logManager?.i(TAG, "Root permission granted")
            } else {
                logManager?.w(TAG, "Root permission denied or ignored by user")
            }

            return@withContext granted
        } catch (e: Exception) {
            logManager?.e(TAG, "Error requesting root permission: ${e.message}")
            _rootPermissionGranted = false
            return@withContext false
        }
    }
    
    override suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        if (!isPermissionGranted()) {
            return@withContext CommandResult.failure("Root permission not granted")
        }
        
        try {
            logManager?.d(TAG, "Executing command: $command")
            val result = Shell.cmd(command).exec()
            
            val commandResult = CommandResult(
                success = result.isSuccess,
                output = result.out,
                error = if (result.err.isNotEmpty()) result.err.joinToString("\n") else null,
                exitCode = result.code
            )
            
            if (commandResult.success) {
                logManager?.d(TAG, "Command succeeded: ${commandResult.output.size} lines of output")
            } else {
                logManager?.w(TAG, "Command failed with exit code ${commandResult.exitCode}: ${commandResult.error}")
            }
            
            return@withContext commandResult
        } catch (e: Exception) {
            logManager?.e(TAG, "Exception executing command: ${e.message}")
            return@withContext CommandResult.failure("Exception: ${e.message}")
        }
    }
    
    override suspend fun executeWithFallbacks(commands: List<String>): CommandResult {
        if (commands.isEmpty()) {
            return CommandResult.failure("No commands provided")
        }
        
        var lastResult: CommandResult? = null
        
        for ((index, command) in commands.withIndex()) {
            logManager?.d(TAG, "Trying command ${index + 1}/${commands.size}: $command")
            val result = executeCommand(command)
            
            if (result.success) {
                logManager?.i(TAG, "Command succeeded on attempt ${index + 1}")
                return result
            }
            
            lastResult = result
        }
        
        logManager?.w(TAG, "All ${commands.size} commands failed")
        return lastResult ?: CommandResult.failure("All commands failed")
    }
    
    override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.ROOT
    
    override suspend fun getUid(): Int = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("id -u").exec()
            if (result.isSuccess && result.out.isNotEmpty()) {
                return@withContext result.out[0].toIntOrNull() ?: -1
            }
        } catch (e: Exception) {
            logManager?.e(TAG, "Error getting UID: ${e.message}")
        }
        return@withContext -1
    }
    
    override fun cleanup() {
        logManager?.i(TAG, "Cleaning up root executor")
        // libsu handles cleanup automatically
    }
}

