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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume

@RequiresApi(Build.VERSION_CODES.O)
class DhizukuExecutor : PrivilegeExecutor {

    companion object {
        private const val TAG = "privacyFlip-DhizukuExecutor"
    }

    private var logManager: LogManager? = null
    private var context: Context? = null

    // Use a dedicated coroutine scope instead of GlobalScope
    private val executorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var permissionGranted: Boolean? = null

    @Volatile
    private var permissionContinuation: kotlin.coroutines.Continuation<Boolean>? = null

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
        logManager?.d(TAG, "isPermissionGranted() called - cached value: $permissionGranted")

        // Always check if Dhizuku is available first
        if (!isAvailable()) {
            logManager?.w(TAG, "isPermissionGranted() - Dhizuku is not available")
            permissionGranted = false
            return@withContext false
        }

        // If cached value is false but Dhizuku is now available, clear cache to re-check
        if (permissionGranted == false) {
            logManager?.d(TAG, "isPermissionGranted() - Dhizuku is available but cache is false, clearing cache to re-check")
            permissionGranted = null
        }

        if (permissionGranted != null) {
            logManager?.d(TAG, "isPermissionGranted() returning cached value: $permissionGranted")
            return@withContext permissionGranted!!
        }

        try {
            val granted = Dhizuku.isPermissionGranted()
            logManager?.d(TAG, "isPermissionGranted() - checked Dhizuku.isPermissionGranted(): $granted")
            permissionGranted = granted
            return@withContext granted
        } catch (e: Exception) {
            logManager?.e(TAG, "isPermissionGranted() - exception: ${e.message}")
            permissionGranted = false
            return@withContext false
        }
    }
    
    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        try {
            logManager?.d(TAG, "========== requestPermission() START ==========")
            logManager?.d(TAG, "requestPermission() - current cached permissionGranted: $permissionGranted")

            val currentPermission = Dhizuku.isPermissionGranted()
            logManager?.d(TAG, "requestPermission() - Dhizuku.isPermissionGranted() = $currentPermission")

            if (currentPermission) {
                logManager?.d(TAG, "requestPermission() - Permission already granted, updating cache and returning true")
                permissionGranted = true
                return@withContext true
            }

            logManager?.d(TAG, "requestPermission() - About to show Dhizuku permission dialog...")

            // 30-second timeout to match root/Shizuku permission timeout
            val granted = withTimeoutOrNull(30000) {
                suspendCancellableCoroutine { continuation ->
                    logManager?.d(TAG, "requestPermission() - Setting up continuation for permission request")
                    permissionContinuation = continuation

                    try {
                        Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                            override fun onRequestPermission(grantResult: Int) {
                                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                                logManager?.d(TAG, "onRequestPermission() - grantResult: $grantResult, granted: $granted")
                                
                                permissionGranted = granted

                                // Only resume continuation if it's not null
                                val cont = permissionContinuation
                                if (cont != null) {
                                    permissionContinuation = null
                                    cont.resume(granted)
                                    logManager?.d(TAG, "onRequestPermission() - resumed continuation with result: $granted")
                                } else {
                                    logManager?.w(TAG, "onRequestPermission() - continuation already null, ignoring duplicate call")
                                }

                                // Broadcast to UI to refresh when permission status changes
                                context?.let { ctx ->
                                    val intent = android.content.Intent("io.github.dorumrr.privacyflip.DHIZUKU_STATUS_CHANGED")
                                    ctx.sendBroadcast(intent)
                                    logManager?.i(TAG, "Broadcast sent to notify UI of permission change")
                                }
                            }
                        })
                        logManager?.d(TAG, "requestPermission() - Dhizuku.requestPermission() called successfully, waiting for user response...")
                    } catch (e: Exception) {
                        logManager?.e(TAG, "requestPermission() - Exception calling Dhizuku.requestPermission(): ${e.message}")
                        continuation.resume(false)
                        return@suspendCancellableCoroutine
                    }

                    continuation.invokeOnCancellation {
                        logManager?.d(TAG, "requestPermission() - Permission request cancelled")
                        permissionContinuation = null
                    }
                }
            } ?: false

            logManager?.d(TAG, "requestPermission() - User responded, result: $granted")
            logManager?.d(TAG, "requestPermission() - Updating permissionGranted cache to: $granted")
            permissionGranted = granted

            // Double-check the actual permission state
            val actualPermission = Dhizuku.isPermissionGranted()
            logManager?.d(TAG, "requestPermission() - Double-checking: Dhizuku.isPermissionGranted() = $actualPermission")

            logManager?.d(TAG, "========== requestPermission() END - returning $granted ==========")
            return@withContext granted

        } catch (e: Exception) {
            logManager?.e(TAG, "requestPermission() - ERROR: ${e.message}")
            logManager?.e(TAG, "requestPermission() - Stack trace: ${e.stackTraceToString()}")
            permissionContinuation = null
            permissionGranted = false
            return@withContext false
        }
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

    override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.DHIZUKU

    override suspend fun getUid(): Int = withContext(Dispatchers.IO) {
        try {
            // Dhizuku runs with Device Owner privileges
            // UID varies by implementation, typically system UID (1000) or similar
            // We'll return a special value to indicate Device Owner
            return@withContext 1000
        } catch (e: Exception) {
            return@withContext -1
        }
    }

    override fun cleanup() {
        try {
            executorScope.cancel() // Cancel the coroutine scope
        } catch (e: Exception) {
            logManager?.e(TAG, "Error cleaning up: ${e.message}")
        }
    }
}

