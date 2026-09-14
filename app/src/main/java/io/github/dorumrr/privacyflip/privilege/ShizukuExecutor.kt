package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dorumrr.privacyflip.service.PrivacyMonitorService
import io.github.dorumrr.privacyflip.util.LogManager
import io.github.dorumrr.privacyflip.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuExecutor : PrivilegeExecutor {

    companion object {
        private const val TAG = "privacyFlip-ShizukuExecutor"
    }

    private var logManager: LogManager? = null
    private var context: Context? = null

    // Use a dedicated coroutine scope instead of GlobalScope
    private val executorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The caching, timeout and hand-back-the-answer rules live in the gate, shared with
    // DhizukuExecutor. Everything Shizuku-specific stays here, as the hooks below.
    private val permissionGate: PrivilegePermissionGate = PrivilegePermissionGate(
        tag = TAG,
        logger = { logManager },
        isBackendAvailable = { isAvailable() },
        readPermissionFromBackend = {
            if (Shizuku.isPreV11()) {
                logManager?.w(TAG, "Shizuku is pre-V11 - cannot report permission")
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        },
        preCheck = {
            when {
                Shizuku.isPreV11() -> {
                    logManager?.w(TAG, "Shizuku is pre-V11, cannot request permission")
                    PermissionPreCheck.CannotAsk
                }
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    PermissionPreCheck.AlreadyGranted
                // The user previously denied with "Don't ask again", so a dialog would never show.
                Shizuku.shouldShowRequestPermissionRationale() -> {
                    logManager?.w(TAG, "User previously denied permission with 'Don't ask again'")
                    PermissionPreCheck.CannotAsk
                }
                else -> PermissionPreCheck.AskTheUser
            }
        },
        startRequest = { requestId -> Shizuku.requestPermission(requestId) }
    )

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        onBinderReceived()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        onBinderDead()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        logManager?.d(TAG, "permissionResultListener called - requestCode: $requestCode, grantResult: $grantResult")
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        // The request code is the gate's own request id, so it decides whether this answer belongs
        // to what is waiting. This listener is permanent, so an answer can also arrive with
        // nothing waiting at all; the gate handles that too.
        permissionGate.deliverResult(requestCode, granted)

        // Broadcast to UI to refresh when permission status changes
        context?.let { ctx ->
            val intent = android.content.Intent("io.github.dorumrr.privacyflip.SHIZUKU_STATUS_CHANGED")
            ctx.sendBroadcast(intent)
            logManager?.i(TAG, "Broadcast sent to notify UI of permission change")
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
        return@withContext permissionGate.isGranted()
    }

    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        val granted = permissionGate.request()

        // Read back what Shizuku itself now reports, for the log only - the gate's answer is
        // what the caller gets.
        if (granted) {
            logManager?.d(TAG, "requestPermission() - Shizuku now reports: ${Shizuku.checkSelfPermission()}")
        }
        return@withContext granted
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
    
    override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.SHIZUKU

    override fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            executorScope.cancel() // Cancel the coroutine scope
        } catch (e: Exception) {
            logManager?.e(TAG, "Error cleaning up: ${e.message}")
        }
    }

    private fun onBinderReceived() {
        logManager?.i(TAG, "Shizuku binder received - service restarted")

        context?.let { ctx ->
            executorScope.launch {
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
        permissionGate.forget()

        context?.let { ctx ->
            executorScope.launch {
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

