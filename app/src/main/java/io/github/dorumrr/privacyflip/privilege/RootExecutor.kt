package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import com.topjohnwu.superuser.Shell
import io.github.dorumrr.privacyflip.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        // Don't cache the result - always check fresh to avoid stale state
        // This is important because:
        // 1. User might grant/deny root permission after first check
        // 2. Magisk might be installed/uninstalled
        // 3. Device might be rebooted and root state changed

        try {
            // The most reliable way to check if root is available is to actually try to get a root shell
            // This will trigger the Magisk prompt if needed, which is exactly what we want
            //
            // Shell.getShell() behavior:
            // - Tries to create a root shell (via 'su')
            // - If su is available and user grants permission: returns root shell (isRoot = true)
            // - If su is available but user denies permission: returns non-root shell (isRoot = false)
            // - If su is not available: returns non-root shell (isRoot = false)
            //
            // This approach:
            // - Works on all devices and Android versions (libsu handles compatibility)
            // - Works on modern Magisk setups (su in any location)
            // - Works on custom ROMs with non-standard su locations
            // - Triggers Magisk prompt at the right time (during availability check)
            // - Most compatible solution recommended by libsu author
            val shell = Shell.getShell()
            return@withContext shell.isRoot
        } catch (e: Exception) {
            logManager?.e(TAG, "Error checking root availability: ${e.message}")
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

