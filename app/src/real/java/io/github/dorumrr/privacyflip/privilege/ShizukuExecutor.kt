package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dorumrr.privacyflip.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume

class ShizukuExecutor : PrivilegeExecutor {

    companion object {
        private const val TAG = "ShizukuExecutor"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private var logManager: LogManager? = null
    private var context: Context? = null

    @Volatile
    private var permissionGranted: Boolean? = null

    @Volatile
    private var permissionContinuation: kotlin.coroutines.Continuation<Boolean>? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        onBinderReceived()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        onBinderDead()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            permissionGranted = granted
            permissionContinuation?.resume(granted)
            permissionContinuation = null
        }
    }
    
    override suspend fun initialize(context: Context) {
        this.context = context
        logManager = LogManager.getInstance(context)

        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            logManager?.e(TAG, "Error initializing Shizuku: ${e.message}")
        }
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext Shizuku.pingBinder()
        } catch (e: Exception) {
            return@withContext false
        }
    }

    override suspend fun isPermissionGranted(): Boolean = withContext(Dispatchers.IO) {
        if (permissionGranted != null) {
            return@withContext permissionGranted!!
        }

        try {
            if (Shizuku.isPreV11()) {
                return@withContext false
            }

            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            permissionGranted = granted
            return@withContext granted
        } catch (e: Exception) {
            permissionGranted = false
            return@withContext false
        }
    }
    
    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Shizuku.isPreV11()) {
                return@withContext false
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                permissionGranted = true
                return@withContext true
            }

            // 30-second timeout to match root permission timeout
            val granted = withTimeoutOrNull(30000) {
                suspendCancellableCoroutine { continuation ->
                    permissionContinuation = continuation
                    Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                    continuation.invokeOnCancellation {
                        permissionContinuation = null
                    }
                }
            } ?: false

            return@withContext granted

        } catch (e: Exception) {
            logManager?.e(TAG, "Error requesting permission: ${e.message}")
            permissionContinuation = null
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
            // Use reflection to access Shizuku.newProcess() - private in API 13.1.5
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

    override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.SHIZUKU

    override suspend fun getUid(): Int = withContext(Dispatchers.IO) {
        try {
            return@withContext Shizuku.getUid()
        } catch (e: Exception) {
            return@withContext -1
        }
    }

    override fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            logManager?.e(TAG, "Error cleaning up: ${e.message}")
        }
    }

    private fun onBinderReceived() {
        // Could trigger UI update when Shizuku becomes available
    }

    private fun onBinderDead() {
        permissionGranted = null
        // Could show notification that Shizuku service stopped
    }
}

