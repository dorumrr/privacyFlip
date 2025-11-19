package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import com.topjohnwu.superuser.Shell
import io.github.dorumrr.privacyflip.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RootExecutor : PrivilegeExecutor {

    companion object {
        private const val TAG = "privacyFlip-RootExecutor"

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
                            .setTimeout(30) // 30 seconds to match Shizuku timeout
                    )
                    isShellInitialized = true
                } catch (e: Exception) {
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

            // Only check if su binary exists - don't call Shell.isAppGrantedRoot()
            // because it creates a shell instance before we're ready to show the prompt
            val rootExists = suPaths.any { File(it).exists() }
            _isRootAvailable = rootExists
            return@withContext rootExists
        } catch (e: Exception) {
            logManager?.e(TAG, "Error checking root availability: ${e.message}")
            _isRootAvailable = false
            return@withContext false
        }
    }

    override suspend fun isPermissionGranted(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try to execute a simple root command to check if permission is granted
            // This is more reliable than Shell.isAppGrantedRoot() which may cache results
            val result = Shell.cmd("id -u").exec()
            val hasRoot = result.isSuccess && result.out.isNotEmpty() && result.out[0] == "0"
            _rootPermissionGranted = hasRoot
            return@withContext hasRoot
        } catch (e: Exception) {
            _rootPermissionGranted = false
            return@withContext false
        }
    }

    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        try {
            val shell = Shell.getShell()
            val granted = shell.isRoot
            _rootPermissionGranted = granted
            return@withContext granted
        } catch (e: Exception) {
            _rootPermissionGranted = false
            return@withContext false
        }
    }
    
    override suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        if (!isPermissionGranted()) {
            return@withContext CommandResult.failure("Root permission not granted")
        }

        try {
            val result = Shell.cmd(command).exec()
            return@withContext CommandResult(
                success = result.isSuccess,
                output = result.out,
                error = if (result.err.isNotEmpty()) result.err.joinToString("\n") else null,
                exitCode = result.code
            )
        } catch (e: Exception) {
            return@withContext CommandResult.failure("Exception: ${e.message}")
        }
    }

    override suspend fun executeWithFallbacks(commands: List<String>): CommandResult {
        if (commands.isEmpty()) {
            return CommandResult.failure("No commands provided")
        }

        var lastResult: CommandResult? = null

        for (command in commands) {
            val result = executeCommand(command)
            if (result.success) {
                return result
            }
            lastResult = result
        }

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
            // Ignore
        }
        return@withContext -1
    }

    override fun cleanup() {
        // libsu handles cleanup automatically
    }
}

