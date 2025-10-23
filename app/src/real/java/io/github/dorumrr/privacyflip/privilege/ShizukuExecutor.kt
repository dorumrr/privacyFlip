package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dorumrr.privacyflip.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Executor that uses Shizuku API for ADB-level privileges
 * This class is only compiled in the "real" flavor
 */
class ShizukuExecutor : PrivilegeExecutor {
    
    companion object {
        private const val TAG = "ShizukuExecutor"
        private const val PERMISSION_REQUEST_CODE = 1001
    }
    
    private var logManager: LogManager? = null
    private var context: Context? = null
    
    @Volatile
    private var permissionGranted: Boolean? = null
    
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        logManager?.i(TAG, "Shizuku binder received - service available")
        onBinderReceived()
    }
    
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        logManager?.w(TAG, "Shizuku binder dead - service unavailable")
        onBinderDead()
    }
    
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            permissionGranted = granted
            logManager?.i(TAG, "Permission result: ${if (granted) "granted" else "denied"}")
        }
    }
    
    override suspend fun initialize(context: Context) {
        this.context = context
        logManager = LogManager.getInstance(context)
        
        try {
            // Register listeners
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            
            logManager?.i(TAG, "Shizuku executor initialized")
        } catch (e: Exception) {
            logManager?.e(TAG, "Error initializing Shizuku: ${e.message}")
        }
    }
    
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Check if Shizuku is running
            val binderAlive = Shizuku.pingBinder()
            logManager?.i(TAG, "Shizuku availability check: $binderAlive")
            return@withContext binderAlive
        } catch (e: Exception) {
            logManager?.e(TAG, "Error checking Shizuku availability: ${e.message}")
            return@withContext false
        }
    }
    
    override suspend fun isPermissionGranted(): Boolean = withContext(Dispatchers.IO) {
        if (permissionGranted != null) {
            return@withContext permissionGranted!!
        }
        
        try {
            if (Shizuku.isPreV11()) {
                logManager?.w(TAG, "Shizuku pre-v11 is not supported")
                return@withContext false
            }
            
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            permissionGranted = granted
            logManager?.i(TAG, "Permission check: $granted")
            return@withContext granted
        } catch (e: Exception) {
            logManager?.e(TAG, "Error checking permission: ${e.message}")
            permissionGranted = false
            return@withContext false
        }
    }
    
    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Shizuku.isPreV11()) {
                logManager?.w(TAG, "Shizuku pre-v11 is not supported")
                return@withContext false
            }
            
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                logManager?.w(TAG, "User previously denied permission")
                // Still try to request
            }
            
            logManager?.i(TAG, "Requesting Shizuku permission...")
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            
            // Wait a bit for the permission dialog
            kotlinx.coroutines.delay(500)
            
            return@withContext isPermissionGranted()
        } catch (e: Exception) {
            logManager?.e(TAG, "Error requesting permission: ${e.message}")
            return@withContext false
        }
    }
    
    override suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        if (!isPermissionGranted()) {
            return@withContext CommandResult.failure("Shizuku permission not granted")
        }

        if (!isAvailable()) {
            return@withContext CommandResult.failure("Shizuku service not available")
        }

        try {
            logManager?.d(TAG, "Executing command: $command")

            // Use reflection to access Shizuku.newProcess() since it's private in API 13.1.5
            // This is a temporary workaround until we implement UserService
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true

            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

            val output = mutableListOf<String>()
            val error = mutableListOf<String>()

            // Read output
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    output.add(line)
                }
            }

            // Read error
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    error.add(line)
                }
            }

            val exitCode = process.waitFor()
            val success = exitCode == 0

            val result = CommandResult(
                success = success,
                output = output,
                error = if (error.isNotEmpty()) error.joinToString("\n") else null,
                exitCode = exitCode
            )

            if (success) {
                logManager?.d(TAG, "Command succeeded: ${output.size} lines of output")
            } else {
                logManager?.w(TAG, "Command failed with exit code $exitCode: ${result.error}")
            }

            return@withContext result
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
    
    override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.SHIZUKU
    
    override suspend fun getUid(): Int = withContext(Dispatchers.IO) {
        try {
            val uid = Shizuku.getUid()
            logManager?.i(TAG, "Shizuku UID: $uid")
            return@withContext uid
        } catch (e: Exception) {
            logManager?.e(TAG, "Error getting UID: ${e.message}")
            return@withContext -1
        }
    }
    
    override fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            logManager?.i(TAG, "Shizuku executor cleaned up")
        } catch (e: Exception) {
            logManager?.e(TAG, "Error cleaning up: ${e.message}")
        }
    }
    
    private fun onBinderReceived() {
        // Binder is now available - could trigger UI update
        context?.let {
            // Notify that Shizuku is now available
        }
    }
    
    private fun onBinderDead() {
        // Binder died - should notify user
        permissionGranted = null
        context?.let {
            // Show notification that Shizuku service stopped
        }
    }
}

