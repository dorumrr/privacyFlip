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
 * - Location: Uses dumpsys location to check for active location requests (e.g., navigation apps)
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
            PrivacyFeature.LOCATION -> isLocationInUse()
            // For features where we can't reliably detect usage, return false
            // (they will be disabled normally)
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
     * Check if location is currently being used by any app.
     * Uses dumpsys location to check for active location requests/listeners.
     * This detects apps like navigation (Google Maps) actively requesting location.
     */
    private suspend fun isLocationInUse(): Boolean {
        return try {
            // Query dumpsys location for active requests and listeners
            // Look for LocationRequest entries which indicate apps actively requesting location
            val result = rootManager.executeCommand(
                "dumpsys location | grep -E 'LocationRequest|UpdateRecord|Active|Listener.*\\[' | grep -v 'passive' | head -30"
            )
            
            if (!result.success) {
                Log.w(TAG, "Failed to check location usage state via dumpsys")
                return false
            }

            val output = result.output.joinToString(" ").uppercase()
            
            // If output is empty or very short, no active requests found
            if (output.length < 10) {
                Log.i(TAG, "📍 Location usage check: NOT IN USE (no active requests)")
                return false
            }
            
            // Look for patterns indicating active location usage:
            // - "LOCATIONREQUEST" with quality/interval indicates active requests
            // - "UPDATERECORD" shows active update subscriptions
            // - "ACTIVE" in context of listeners indicates ongoing use
            val hasActiveRequest = output.contains("LOCATIONREQUEST") ||
                                   output.contains("UPDATERECORD") ||
                                   output.contains("ACTIVE")
            
            // Additional check: look for specific app patterns that indicate active navigation
            val hasNavigationApp = output.contains("COM.GOOGLE.ANDROID.APPS.MAPS") ||
                                  output.contains("COM.WAZE") ||
                                  output.contains("MAPS") // Broader match for map apps

            val isInUse = hasActiveRequest || hasNavigationApp

            Log.i(TAG, "📍 Location usage check: ${if (isInUse) "IN USE" else "NOT IN USE"}")
            isInUse
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location usage state", e)
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
