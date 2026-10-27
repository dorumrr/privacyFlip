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

        // The shell is built with FLAG_REDIRECT_STDERR, which makes libsu write stderr into the
        // SAME list as stdout, so an su banner or warning can sit ahead of `id -u`'s answer.
        internal fun isRootUid(outputLines: List<String>): Boolean =
            outputLines.any { it.trim() == "0" }

        // Only a NON-root cached shell is ever dropped. No privileged command can be running on
        // one, while dropping a working root shell would tear down live work for nothing.
        internal fun shouldDropCachedShell(hasCachedShell: Boolean, cachedIsRoot: Boolean): Boolean =
            hasCachedShell && !cachedIsRoot

        internal fun suOnPath(path: String?, exists: (File) -> Boolean): Boolean =
            path.orEmpty().split(':').filter { it.isNotEmpty() }.any { exists(File(it, "su")) }

        // The flag below is static, so the lock has to be too. Guarding it with the instance lock
        // let two RootExecutors configure the shell at once.
        private val shellInitLock = Any()
    }

    private var logManager: LogManager? = null
    private var _isRootAvailable: Boolean? = null
    private var _rootPermissionGranted: Boolean? = null

    override suspend fun initialize(context: Context) {
        logManager = LogManager.getInstance(context)

        synchronized(shellInitLock) {
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
                    // libsu refuses this once its main shell already exists, so the flags above
                    // are simply not in effect. Nothing here can undo that, but it must not pass
                    // silently: CommandResult.errorFrom() copes with either arrangement precisely because
                    // this can fail.
                    logManager?.w(TAG, "Shell defaults not applied (shell already built): ${e.message}")
                    isShellInitialized = true
                }
            }
        }
    }
    
    // Shell.getShell() runs su, which is what shows the Magisk prompt. A denied su still counts as
    // available: it is the one state "Check Again" (requestPermission) can recover from.
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext Shell.getShell().isRoot || suOnPath(System.getenv("PATH")) { it.exists() }
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
            return@withContext CommandResult.fromProcess(result.code, result.out, result.err)
        } catch (e: Exception) {
            return@withContext CommandResult.failure("Exception: ${e.message}")
        }
    }

    override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.ROOT

    override fun cleanup() {
        // Deliberately nothing. libsu's main shell is process-wide and shared with whatever
        // executor is built next, so closing it here would tear down work this instance never
        // owned.
    }
}

