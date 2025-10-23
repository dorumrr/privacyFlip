package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.content.SharedPreferences
import io.github.dorumrr.privacyflip.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Mock implementation of Shizuku executor for testing without actual Shizuku installation
 * This allows developers to test the entire Shizuku workflow without having Shizuku installed
 */
class MockShizukuExecutor : PrivilegeExecutor {
    
    companion object {
        private const val TAG = "MockShizukuExecutor"
        private const val PREFS_NAME = "mock_shizuku_prefs"
        private const val KEY_PERMISSION_GRANTED = "permission_granted"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_BINDER_ALIVE = "binder_alive"
        
        // Simulated states for testing different scenarios
        var simulateBinderDeath = false
        var simulatePermissionDenied = false
        var simulateServiceNotRunning = false
    }
    
    private var logManager: LogManager? = null
    private var prefs: SharedPreferences? = null
    
    override suspend fun initialize(context: Context) {
        logManager = LogManager.getInstance(context)
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Initialize default state - service running, binder alive
        prefs?.let { preferences ->
            if (!preferences.contains(KEY_SERVICE_RUNNING)) {
                preferences.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply()
            }
            if (!preferences.contains(KEY_BINDER_ALIVE)) {
                preferences.edit().putBoolean(KEY_BINDER_ALIVE, true).apply()
            }
        }
        
        logManager?.i(TAG, "🎭 Mock Shizuku executor initialized (for testing)")
    }
    
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        // Simulate checking if Shizuku is installed and running
        delay(50) // Simulate IPC delay
        
        if (simulateServiceNotRunning) {
            logManager?.w(TAG, "🎭 Mock: Shizuku service not running")
            return@withContext false
        }
        
        val isRunning = prefs?.getBoolean(KEY_SERVICE_RUNNING, true) ?: true
        val isBinderAlive = prefs?.getBoolean(KEY_BINDER_ALIVE, true) ?: true
        
        val available = isRunning && isBinderAlive && !simulateBinderDeath
        logManager?.i(TAG, "🎭 Mock: Shizuku available = $available")
        return@withContext available
    }
    
    override suspend fun isPermissionGranted(): Boolean = withContext(Dispatchers.IO) {
        delay(30) // Simulate IPC delay
        
        if (simulatePermissionDenied) {
            logManager?.w(TAG, "🎭 Mock: Permission denied (simulated)")
            return@withContext false
        }
        
        val granted = prefs?.getBoolean(KEY_PERMISSION_GRANTED, false) ?: false
        logManager?.i(TAG, "🎭 Mock: Permission granted = $granted")
        return@withContext granted
    }
    
    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        logManager?.i(TAG, "🎭 Mock: Requesting Shizuku permission...")
        delay(100) // Simulate user interaction delay
        
        if (simulatePermissionDenied) {
            logManager?.w(TAG, "🎭 Mock: User denied permission")
            return@withContext false
        }
        
        // Simulate user granting permission
        prefs?.edit()?.putBoolean(KEY_PERMISSION_GRANTED, true)?.apply()
        logManager?.i(TAG, "🎭 Mock: User granted permission")
        return@withContext true
    }
    
    override suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        if (!isPermissionGranted()) {
            return@withContext CommandResult.failure("Shizuku permission not granted")
        }
        
        if (!isAvailable()) {
            return@withContext CommandResult.failure("Shizuku service not available")
        }
        
        delay(50) // Simulate command execution delay
        
        logManager?.d(TAG, "🎭 Mock: Executing command: $command")
        
        // Simulate command execution based on command type
        val result = simulateCommandExecution(command)
        
        if (result.success) {
            logManager?.d(TAG, "🎭 Mock: Command succeeded")
        } else {
            logManager?.w(TAG, "🎭 Mock: Command failed: ${result.error}")
        }
        
        return@withContext result
    }
    
    override suspend fun executeWithFallbacks(commands: List<String>): CommandResult {
        if (commands.isEmpty()) {
            return CommandResult.failure("No commands provided")
        }
        
        var lastResult: CommandResult? = null
        
        for ((index, command) in commands.withIndex()) {
            logManager?.d(TAG, "🎭 Mock: Trying command ${index + 1}/${commands.size}")
            val result = executeCommand(command)
            
            if (result.success) {
                logManager?.i(TAG, "🎭 Mock: Command succeeded on attempt ${index + 1}")
                return result
            }
            
            lastResult = result
        }
        
        logManager?.w(TAG, "🎭 Mock: All ${commands.size} commands failed")
        return lastResult ?: CommandResult.failure("All commands failed")
    }
    
    override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.SHIZUKU
    
    override suspend fun getUid(): Int {
        // Shizuku runs as ADB (UID 2000)
        return 2000
    }
    
    override fun cleanup() {
        logManager?.i(TAG, "🎭 Mock: Cleaning up")
    }
    
    /**
     * Simulate command execution results based on command patterns
     */
    private fun simulateCommandExecution(command: String): CommandResult {
        return when {
            // WiFi commands
            command.contains("svc wifi") -> {
                val enabled = command.contains("enable")
                CommandResult.success(listOf("WiFi ${if (enabled) "enabled" else "disabled"}"))
            }
            command.contains("settings") && command.contains("wifi_on") -> {
                CommandResult.success()
            }
            
            // Bluetooth commands
            command.contains("svc bluetooth") -> {
                val enabled = command.contains("enable")
                CommandResult.success(listOf("Bluetooth ${if (enabled) "enabled" else "disabled"}"))
            }
            command.contains("settings") && command.contains("bluetooth_on") -> {
                CommandResult.success()
            }
            
            // Mobile data commands
            command.contains("svc data") -> {
                val enabled = command.contains("enable")
                CommandResult.success(listOf("Mobile data ${if (enabled) "enabled" else "disabled"}"))
            }
            command.contains("settings") && command.contains("mobile_data") -> {
                CommandResult.success()
            }
            
            // Location commands
            command.contains("location_mode") || command.contains("location_providers") -> {
                CommandResult.success()
            }
            
            // NFC commands
            command.contains("svc nfc") -> {
                val enabled = command.contains("enable")
                CommandResult.success(listOf("NFC ${if (enabled) "enabled" else "disabled"}"))
            }
            
            // Status check commands
            command.contains("settings get") -> {
                // Return mock status values
                when {
                    command.contains("wifi_on") -> CommandResult.success(listOf("1"))
                    command.contains("bluetooth_on") -> CommandResult.success(listOf("1"))
                    command.contains("mobile_data") -> CommandResult.success(listOf("1"))
                    command.contains("location_mode") -> CommandResult.success(listOf("3"))
                    else -> CommandResult.success(listOf("0"))
                }
            }
            
            command.contains("dumpsys") -> {
                // Return mock dumpsys output
                when {
                    command.contains("wifi") -> CommandResult.success(listOf("Wi-Fi is enabled"))
                    command.contains("bluetooth") -> CommandResult.success(listOf("Bluetooth is enabled"))
                    command.contains("nfc") -> CommandResult.success(listOf("NFC is enabled"))
                    else -> CommandResult.success(listOf("Service info"))
                }
            }
            
            // ID command
            command == "id" || command == "id -u" -> {
                CommandResult.success(listOf("2000")) // ADB UID
            }
            
            // Unknown command
            else -> {
                logManager?.w(TAG, "🎭 Mock: Unknown command pattern: $command")
                CommandResult.success(listOf("Command executed"))
            }
        }
    }
    
    /**
     * Test helper: Simulate binder death
     */
    fun simulateBinderDeath() {
        prefs?.edit()?.putBoolean(KEY_BINDER_ALIVE, false)?.apply()
        logManager?.w(TAG, "🎭 Mock: Simulated binder death")
    }
    
    /**
     * Test helper: Restore binder
     */
    fun restoreBinder() {
        prefs?.edit()?.putBoolean(KEY_BINDER_ALIVE, true)?.apply()
        logManager?.i(TAG, "🎭 Mock: Binder restored")
    }
    
    /**
     * Test helper: Reset all mock state
     */
    fun resetMockState() {
        prefs?.edit()?.clear()?.apply()
        simulateBinderDeath = false
        simulatePermissionDenied = false
        simulateServiceNotRunning = false
        logManager?.i(TAG, "🎭 Mock: State reset")
    }
}

