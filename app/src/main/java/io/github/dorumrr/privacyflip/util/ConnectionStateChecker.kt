package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.media.AudioManager
import android.util.Log
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.root.RootManager

/**
 * Utility class to check if connectivity features are currently in use/connected.
 * Used to implement "only disable if not connected" feature.
 * 
 * Detection methods:
 * - WiFi: Uses dumpsys connectivity to check for active WIFI connection
 * - Bluetooth: Uses dumpsys bluetooth_manager to check ConnectionState
 * - Microphone: Uses AudioManager to check call/communication mode
 */
class ConnectionStateChecker(
    private val context: Context,
    private val rootManager: RootManager
) {
    companion object {
        private const val TAG = "privacyFlip-ConnectionStateChecker"
    }

    /**
     * Check if a feature is currently in use/connected.
     * 
     * @param feature The privacy feature to check
     * @return true if the feature is in use, false otherwise
     */
    suspend fun isFeatureInUse(feature: PrivacyFeature): Boolean {
        return when (feature) {
            PrivacyFeature.WIFI -> isWifiConnected()
            PrivacyFeature.BLUETOOTH -> isBluetoothConnected()
            PrivacyFeature.MICROPHONE -> isMicrophoneInUse()
            // For features where we can't reliably detect usage, return false
            // (they will be disabled normally)
            PrivacyFeature.LOCATION,
            PrivacyFeature.MOBILE_DATA,
            PrivacyFeature.NFC,
            PrivacyFeature.CAMERA,
            PrivacyFeature.AIRPLANE_MODE,
            PrivacyFeature.BATTERY_SAVER -> false
        }
    }

    /**
     * Check if WiFi is connected to a network.
     * Uses dumpsys connectivity which is available with root/shizuku.
     */
    private suspend fun isWifiConnected(): Boolean {
        return try {
            val result = rootManager.executeCommand("dumpsys connectivity | grep -E 'WIFI.*(CONNECTED|state)' | head -5")
            
            if (!result.success) {
                Log.w(TAG, "Failed to check WiFi connection state via dumpsys")
                return false
            }

            val output = result.output.joinToString(" ").uppercase()
            
            // Look for patterns indicating WiFi is connected
            // Pattern 1: "WIFI CONNECTED" from NetworkAgentInfo
            // Pattern 2: "Transports: WIFI" with "VALIDATED" (active connection)
            val isConnected = output.contains("WIFI CONNECTED") ||
                             output.contains("WIFI.*CONNECTED".toRegex()) ||
                             (output.contains("TRANSPORTS: WIFI") && output.contains("VALIDATED"))

            Log.i(TAG, "📶 WiFi connection check: ${if (isConnected) "CONNECTED" else "NOT CONNECTED"}")
            isConnected
        } catch (e: Exception) {
            Log.e(TAG, "Error checking WiFi connection state", e)
            false
        }
    }

    /**
     * Check if Bluetooth is connected to any device.
     * Uses dumpsys bluetooth_manager to check connection state.
     */
    private suspend fun isBluetoothConnected(): Boolean {
        return try {
            val result = rootManager.executeCommand("dumpsys bluetooth_manager | grep -E 'ConnectionState:|mConnectionState:' | head -10")
            
            if (!result.success) {
                Log.w(TAG, "Failed to check Bluetooth connection state via dumpsys")
                return false
            }

            val output = result.output.joinToString(" ").uppercase()
            
            // Look for any CONNECTED state (not STATE_DISCONNECTED)
            // ConnectionState: STATE_CONNECTED indicates an active connection
            // mConnectionState: CONNECTED also indicates connection
            val hasConnectedState = output.contains("STATE_CONNECTED") ||
                                   output.contains("MCONNECTIONSTATE: CONNECTED")
            
            // Make sure we're not just seeing DISCONNECTED
            val hasDisconnectedOnly = output.contains("DISCONNECTED") && !hasConnectedState

            val isConnected = hasConnectedState && !hasDisconnectedOnly

            Log.i(TAG, "🔵 Bluetooth connection check: ${if (isConnected) "CONNECTED" else "NOT CONNECTED"}")
            isConnected
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Bluetooth connection state", e)
            false
        }
    }

    /**
     * Check if microphone is in use (during a call or communication).
     * This uses standard Android API, no root required.
     */
    private fun isMicrophoneInUse(): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let {
                val mode = it.mode
                val inUse = mode == AudioManager.MODE_IN_CALL ||
                           mode == AudioManager.MODE_IN_COMMUNICATION

                if (inUse) {
                    Log.i(TAG, "🎤 Microphone in use (audio mode: $mode)")
                }

                inUse
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking microphone usage", e)
            false
        }
    }
}
