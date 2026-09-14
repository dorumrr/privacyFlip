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

        // The shell is built with FLAG_REDIRECT_STDERR, which makes libsu write stderr into the
        // SAME list as stdout, so an su banner or warning can sit ahead of `id -u`'s answer.
        internal fun isRootUid(outputLines: List<String>): Boolean =
            outputLines.any { it.trim() == "0" }

        // Only a NON-root cached shell is ever dropped. No privileged command can be running on
        // one, while dropping a working root shell would tear down live work for nothing.
        internal fun shouldDropCachedShell(hasCachedShell: Boolean, cachedIsRoot: Boolean): Boolean =
            hasCachedShell && !cachedIsRoot
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
            val hasRoot = result.isSuccess && isRootUid(result.out)
            _rootPermissionGranted = hasRoot
            return@withContext hasRoot
        } catch (e: Exception) {
            _rootPermissionGranted = false
            return@withContext false
        }
    }

    /**
     * libsu keeps ONE main shell and only replaces it when it dies. A non-root shell stays alive,
     * so it is never replaced, and every later check re-reads the same answer. Without dropping it
     * here, a re-check can never see root that was granted after that shell was built.
     */
    private fun dropStaleNonRootShell() {
        val cached = Shell.getCachedShell()
        if (!shouldDropCachedShell(cached != null, cached?.isRoot ?: false)) return
        try {
            cached?.close()
            logManager?.d(TAG, "Dropped the cached non-root shell so the next check can re-run su")
        } catch (e: Exception) {
            logManager?.w(TAG, "Could not close the cached shell: ${e.message}")
        }
    }

    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        try {
            dropStaleNonRootShell()
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

    override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.ROOT

    override fun cleanup() {
        // libsu handles cleanup automatically
    }
}

