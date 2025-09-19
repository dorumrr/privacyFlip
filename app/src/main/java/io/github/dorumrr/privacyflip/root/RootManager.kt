package io.github.dorumrr.privacyflip.root

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.dorumrr.privacyflip.util.LogManager
import io.github.dorumrr.privacyflip.util.SingletonHolder
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RootManager private constructor() {

    companion object : SingletonHolder<RootManager, Unit>({ RootManager() }) {
        private const val TAG = "RootManager"

        @Volatile
        private var isShellInitialized = false
    }

    private var _isRootAvailable: Boolean? = null
    private var _rootPermissionGranted: Boolean? = null
    private var logManager: LogManager? = null

    fun initialize(context: Context) {
        logManager = LogManager.getInstance(context)
        synchronized(this) {
            if (!isShellInitialized) {
                try {
                    Shell.enableVerboseLogging = false
                    Shell.setDefaultBuilder(
                        Shell.Builder.create()
                            .setFlags(Shell.FLAG_REDIRECT_STDERR)
                            .setTimeout(10)
                    )
                    isShellInitialized = true
                } catch (e: Exception) {
                    logManager?.w(TAG, "Shell already initialized or failed to initialize: ${e.message}")
                    isShellInitialized = true
                }
            }
        }
    }
    
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
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
            
            return@withContext rootExists
        } catch (e: Exception) {
            logManager?.e(TAG, "Error checking root availability: ${e.message}")
            _isRootAvailable = false
            return@withContext false
        }
    }

    suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        if (_rootPermissionGranted != null) {
            return@withContext _rootPermissionGranted!!
        }

        try {
            val hasRoot = Shell.isAppGrantedRoot() == true
            _rootPermissionGranted = hasRoot
            logManager?.d(TAG, "Root permission check (no dialog): granted=$hasRoot")
            return@withContext hasRoot
        } catch (e: Exception) {
            logManager?.e(TAG, "Error checking root permission: ${e.message}")
            _rootPermissionGranted = false
            return@withContext false
        }
    }

    suspend fun requestRootPermission(): Boolean = withContext(Dispatchers.IO) {
        if (_rootPermissionGranted == true) {
            return@withContext true
        }

        try {
            val testResult = Shell.cmd("echo 'Root permission test'").exec()

            val hasRoot = Shell.getShell().isRoot

            val granted = hasRoot && testResult.isSuccess
            _rootPermissionGranted = granted

            if (!granted) {
                logManager?.w(TAG, "Root permission denied or test command failed")
            }

            return@withContext granted
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting root permission", e)
            _rootPermissionGranted = false
            return@withContext false
        }
    }
    
    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            if (!isRootPermissionGranted()) {
                return@withContext CommandResult(
                    success = false,
                    output = emptyList(),
                    error = "Root permission not granted"
                )
            }
            
            logManager?.d(TAG, "Executing command: $command")
            val result = Shell.cmd(command).exec()

            val commandResult = CommandResult(
                success = result.isSuccess,
                output = result.out,
                error = if (result.err.isNotEmpty()) result.err.joinToString("\n") else null,
                exitCode = result.code
            )

            logManager?.d(TAG, "Command result: success=${commandResult.success}, exitCode=${commandResult.exitCode}")
            if (!commandResult.success) {
                logManager?.w(TAG, "Command failed: ${commandResult.error}")
            }
            
            return@withContext commandResult
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
        for (command in commands) {
            val result = executeCommand(command)
            if (result.success) {
                return@withContext result
            }
        }
        
        return@withContext CommandResult(
            success = false,
            output = emptyList(),
            error = "All fallback commands failed"
        )
    }
    
    fun isRootPermissionGranted(): Boolean {
        return _rootPermissionGranted == true
    }

    suspend fun forceRootPermissionRequest(): Boolean = withContext(Dispatchers.IO) {
        _rootPermissionGranted = null

        try {
            val testResult = Shell.cmd("id").exec()

            val hasRoot = Shell.getShell().isRoot

            val granted = hasRoot && testResult.isSuccess
            _rootPermissionGranted = granted

            if (!testResult.isSuccess) {
                Log.w(TAG, "Test command failed: ${testResult.err.joinToString()}")
            }

            return@withContext granted
        } catch (e: Exception) {
            Log.e(TAG, "Error in force root permission request", e)
            _rootPermissionGranted = false
            return@withContext false
        }
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
