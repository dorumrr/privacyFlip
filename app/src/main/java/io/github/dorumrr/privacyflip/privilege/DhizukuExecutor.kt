package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import io.github.dorumrr.privacyflip.service.PrivacyMonitorService
import io.github.dorumrr.privacyflip.util.LogManager
import io.github.dorumrr.privacyflip.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

@RequiresApi(Build.VERSION_CODES.O)
class DhizukuExecutor : PrivilegeExecutor {

    companion object {
        private const val TAG = "privacyFlip-DhizukuExecutor"
    }

    private var logManager: LogManager? = null
    private var context: Context? = null

    // Use a dedicated coroutine scope instead of GlobalScope
    private val executorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The caching, timeout and hand-back-the-answer rules live in the gate, shared with
    // ShizukuExecutor. Everything Dhizuku-specific stays here, as the hooks below - including
    // the fact that Dhizuku delivers its answer to a callback passed into each request, rather
    // than to a permanent listener the way Shizuku does.
    private val permissionGate: PrivilegePermissionGate = PrivilegePermissionGate(
        tag = TAG,
        logger = { logManager },
        isBackendAvailable = { isAvailable() },
        readPermissionFromBackend = { Dhizuku.isPermissionGranted() },
        preCheck = {
            if (Dhizuku.isPermissionGranted()) {
                PermissionPreCheck.AlreadyGranted
            } else {
                PermissionPreCheck.AskTheUser
            }
        },
        startRequest = { Dhizuku.requestPermission(permissionResultListener()) }
    )

    /**
     * Dhizuku takes a fresh listener per request, unlike Shizuku's single permanent one. Built
     * in a function rather than inline above, so it can refer to the gate it reports back to.
     */
    private fun permissionResultListener() = object : DhizukuRequestPermissionListener() {
        override fun onRequestPermission(grantResult: Int) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            logManager?.d(TAG, "onRequestPermission() - grantResult: $grantResult, granted: $granted")
            permissionGate.deliverResult(granted)

            // Broadcast to UI to refresh when permission status changes
            context?.let { ctx ->
                val intent = android.content.Intent("io.github.dorumrr.privacyflip.DHIZUKU_STATUS_CHANGED")
                ctx.sendBroadcast(intent)
                logManager?.i(TAG, "Broadcast sent to notify UI of permission change")
            }
        }
    }

    override suspend fun initialize(context: Context) {
        this.context = context
        logManager = LogManager.getInstance(context)

        try {
            val initialized = Dhizuku.init(context)
            logManager?.d(TAG, "Dhizuku.init() returned: $initialized")
        } catch (e: Exception) {
            logManager?.e(TAG, "Error initializing Dhizuku: ${e.message}")
        }
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Re-initialize to check if Dhizuku is running
            // init() returns true if binder is received and pingBinder() succeeds
            val initialized = context?.let { Dhizuku.init(it) } ?: false
            if (!initialized) {
                logManager?.d(TAG, "Dhizuku not available (init failed)")
                return@withContext false
            }
            return@withContext true
        } catch (e: Exception) {
            logManager?.d(TAG, "Dhizuku not available: ${e.message}")
            return@withContext false
        }
    }

    override suspend fun isPermissionGranted(): Boolean = withContext(Dispatchers.IO) {
        return@withContext permissionGate.isGranted()
    }

    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        val granted = permissionGate.request()

        // Read back what Dhizuku itself now reports, for the log only - the gate's answer is
        // what the caller gets.
        if (granted) {
            logManager?.d(TAG, "requestPermission() - Dhizuku now reports: ${Dhizuku.isPermissionGranted()}")
        }
        return@withContext granted
    }

    override suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        if (!isPermissionGranted()) {
            return@withContext CommandResult.failure("Dhizuku permission not granted")
        }

        if (!isAvailable()) {
            return@withContext CommandResult.failure("Dhizuku service not available")
        }

        try {
            // Execute command using Dhizuku's process execution
            // Dhizuku.newProcess() requires String[] cmd, String[] env, File dir
            // We need to split the command into shell invocation
            val cmdArray = arrayOf("sh", "-c", command)
            val process = Dhizuku.newProcess(cmdArray, null, null)

            val output = mutableListOf<String>()
            val error = mutableListOf<String>()

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    output.add(line)
                }
            }

            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    error.add(line)
                }
            }

            val exitCode = process.waitFor()

            return@withContext CommandResult(
                success = exitCode == 0,
                output = output,
                error = if (error.isNotEmpty()) error.joinToString("\n") else null,
                exitCode = exitCode
            )
        } catch (e: Exception) {
            logManager?.e(TAG, "executeCommand() failed: ${e.message}")
            return@withContext CommandResult.failure("Exception: ${e.message}")
        }
    }

    override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.DHIZUKU

    override fun cleanup() {
        try {
            executorScope.cancel() // Cancel the coroutine scope
        } catch (e: Exception) {
            logManager?.e(TAG, "Error cleaning up: ${e.message}")
        }
    }
}

