package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dorumrr.privacyflip.service.PrivacyMonitorService
import io.github.dorumrr.privacyflip.util.LogManager
import io.github.dorumrr.privacyflip.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
        private const val TAG = "privacyFlip-ShizukuExecutor"
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
        logManager?.d(TAG, "permissionResultListener called - requestCode: $requestCode, grantResult: $grantResult")
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            logManager?.d(TAG, "permissionResultListener - permission granted: $granted, updating cache")
            permissionGranted = granted

            // Only resume continuation if it's not null (prevent duplicate calls)
            val continuation = permissionContinuation
            if (continuation != null) {
                permissionContinuation = null // Clear BEFORE resuming to prevent race conditions
                continuation.resume(granted)
                logManager?.d(TAG, "permissionResultListener - resumed continuation with result: $granted")
            } else {
                logManager?.w(TAG, "permissionResultListener - continuation already null, ignoring duplicate call")
            }

            // Broadcast to UI to refresh when permission status changes
            context?.let { ctx ->
                val intent = android.content.Intent("io.github.dorumrr.privacyflip.SHIZUKU_STATUS_CHANGED")
                ctx.sendBroadcast(intent)
                logManager?.i(TAG, "Broadcast sent to notify UI of permission change")
            }
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
        logManager?.d(TAG, "isPermissionGranted() called - cached value: $permissionGranted")

        // Always check if Shizuku is available first (binder might be dead)
        if (!isAvailable()) {
            logManager?.w(TAG, "isPermissionGranted() - Shizuku is not available (binder dead)")
            permissionGranted = false
            return@withContext false
        }

        // If cached value is false but Shizuku is now available, clear cache to re-check
        // This handles the case where Shizuku was stopped and then restarted
        if (permissionGranted == false) {
            logManager?.d(TAG, "isPermissionGranted() - Shizuku is available but cache is false, clearing cache to re-check")
            permissionGranted = null
        }

        if (permissionGranted != null) {
            logManager?.d(TAG, "isPermissionGranted() returning cached value: $permissionGranted")
            return@withContext permissionGranted!!
        }

        try {
            if (Shizuku.isPreV11()) {
                logManager?.w(TAG, "isPermissionGranted() - Shizuku is pre-V11")
                return@withContext false
            }

            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            logManager?.d(TAG, "isPermissionGranted() - checked Shizuku.checkSelfPermission(): $granted")
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

            if (Shizuku.isPreV11()) {
                logManager?.w(TAG, "requestPermission() - Shizuku is pre-V11, cannot request permission")
                return@withContext false
            }

            val currentPermission = Shizuku.checkSelfPermission()
            logManager?.d(TAG, "requestPermission() - Shizuku.checkSelfPermission() = $currentPermission")

            if (currentPermission == PackageManager.PERMISSION_GRANTED) {
                logManager?.d(TAG, "requestPermission() - Permission already granted, updating cache and returning true")
                permissionGranted = true
                return@withContext true
            }

            // Check if we should show rationale (user denied with "Don't ask again")
            val shouldShowRationale = Shizuku.shouldShowRequestPermissionRationale()
            logManager?.d(TAG, "requestPermission() - shouldShowRequestPermissionRationale: $shouldShowRationale")
            if (shouldShowRationale) {
                logManager?.w(TAG, "requestPermission() - User previously denied permission with 'Don't ask again'")
                return@withContext false
            }

            logManager?.d(TAG, "requestPermission() - About to show Shizuku permission dialog...")

            // 30-second timeout to match root permission timeout
            val granted = withTimeoutOrNull(30000) {
                suspendCancellableCoroutine { continuation ->
                    logManager?.d(TAG, "requestPermission() - Setting up continuation for permission request")
                    permissionContinuation = continuation

                    try {
                        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                        logManager?.d(TAG, "requestPermission() - Shizuku.requestPermission() called successfully, waiting for user response...")
                    } catch (e: Exception) {
                        logManager?.e(TAG, "requestPermission() - Exception calling Shizuku.requestPermission(): ${e.message}")
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
            val actualPermission = Shizuku.checkSelfPermission()
            logManager?.d(TAG, "requestPermission() - Double-checking: Shizuku.checkSelfPermission() = $actualPermission")

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
        logManager?.i(TAG, "Shizuku binder received - service restarted")

        context?.let { ctx ->
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    // Re-request permission (should be auto-granted if previously granted)
                    val granted = requestPermission()
                    logManager?.d(TAG, "Shizuku auto-restoration: permission granted = $granted")

                    if (granted) {
                        // Check if protection was active before Shizuku died
                        val preferenceManager = PreferenceManager.getInstance(ctx)
                        val wasProtectionActive = preferenceManager.isGlobalPrivacyEnabled

                        if (wasProtectionActive) {
                            logManager?.i(TAG, "Shizuku auto-restoration: restoring protection state")
                            // Restart the privacy monitor service to restore protection
                            PrivacyMonitorService.start(ctx)
                        } else {
                            logManager?.d(TAG, "Shizuku auto-restoration: protection was inactive, not restoring")
                        }
                    }

                    // Broadcast to UI to refresh (permission status may have changed)
                    val intent = android.content.Intent("io.github.dorumrr.privacyflip.SHIZUKU_STATUS_CHANGED")
                    ctx.sendBroadcast(intent)
                    logManager?.i(TAG, "Broadcast sent to notify UI of Shizuku restart")
                } catch (e: Exception) {
                    logManager?.e(TAG, "Error during Shizuku auto-restoration: ${e.message}")
                }
            }
        }
    }

    private fun onBinderDead() {
        logManager?.w(TAG, "Shizuku binder died - service stopped")
        permissionGranted = null

        context?.let { ctx ->
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    // Stop the privacy monitor service since Shizuku is no longer available
                    logManager?.i(TAG, "Stopping PrivacyMonitorService due to Shizuku death")
                    PrivacyMonitorService.stop(ctx)

                    // Broadcast intent to notify UI to refresh
                    val intent = android.content.Intent("io.github.dorumrr.privacyflip.SHIZUKU_STATUS_CHANGED")
                    ctx.sendBroadcast(intent)
                    logManager?.i(TAG, "Broadcast sent to notify UI of Shizuku death")
                } catch (e: Exception) {
                    logManager?.e(TAG, "Error handling Shizuku death: ${e.message}")
                }
            }
        }
    }
}

