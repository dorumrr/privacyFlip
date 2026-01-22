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
     * Uses multiple detection methods with fallbacks for maximum reliability.
     */
    private suspend fun isBluetoothConnected(): Boolean {
        Log.d(TAG, "🔵 Starting Bluetooth connection check...")
        
        return try {
            // Method 1: Try primary detection (bluetooth_manager)
            val method1Result = checkBluetoothManager()
            if (method1Result != null) {
                Log.i(TAG, "🔵 Bluetooth check - Method 1 (bluetooth_manager): ${if (method1Result) "CONNECTED" else "NOT CONNECTED"}")
                return method1Result
            }
            
            // Method 2: Try audio output detection
            Log.d(TAG, "🔵 Method 1 failed, trying Method 2 (audio output)...")
            val method2Result = checkAudioOutput()
            if (method2Result != null) {
                Log.i(TAG, "🔵 Bluetooth check - Method 2 (audio output): ${if (method2Result) "CONNECTED" else "NOT CONNECTED"}")
                return method2Result
            }
            
            // Method 3: Try media session detection
            Log.d(TAG, "🔵 Method 2 failed, trying Method 3 (media session)...")
            val method3Result = checkMediaSession()
            if (method3Result != null) {
                Log.i(TAG, "🔵 Bluetooth check - Method 3 (media session): ${if (method3Result) "CONNECTED" else "NOT CONNECTED"}")
                return method3Result
            }
            
            // Method 4: Try generic bluetooth service dump
            Log.d(TAG, "🔵 Method 3 failed, trying Method 4 (bluetooth service)...")
            val method4Result = checkBluetoothService()
            if (method4Result != null) {
                Log.i(TAG, "🔵 Bluetooth check - Method 4 (bluetooth service): ${if (method4Result) "CONNECTED" else "NOT CONNECTED"}")
                return method4Result
            }
            
            // All methods failed
            Log.w(TAG, "🔵 All Bluetooth detection methods failed - assuming NOT CONNECTED")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "🔵 Error checking Bluetooth connection state", e)
            false
        }
    }

    /**
     * Method 1: Check Bluetooth connection via bluetooth_manager.
     * Most reliable method for detecting Bluetooth connections.
     * 
     * @return true if connected, false if not connected, null if detection failed
     */
    private suspend fun checkBluetoothManager(): Boolean? {
        return try {
            val result = rootManager.executeCommand(
                "dumpsys bluetooth_manager | grep -i -E 'ConnectionState.*CONNECTED|profile.*CONNECTED|A2DP.*CONNECTED|HFP.*CONNECTED'"
            )
            
            if (!result.success) {
                Log.w(TAG, "🔵 Method 1: bluetooth_manager command failed")
                return null
            }

            val output = result.output.joinToString("\n")
            Log.d(TAG, "🔵 Method 1 output (first 200 chars): ${output.take(200)}")
            
            if (output.isEmpty()) {
                Log.d(TAG, "🔵 Method 1: Empty output, trying next method")
                return null
            }
            
            val outputUpper = output.uppercase()
            
            // Check for connected state
            val hasConnected = outputUpper.contains("STATE_CONNECTED") ||
                              outputUpper.contains("MCONNECTIONSTATE: CONNECTED") ||
                              outputUpper.contains("CONNECTIONSTATE: CONNECTED") ||
                              outputUpper.contains("PROFILE: CONNECTED") ||
                              outputUpper.contains("A2DP: CONNECTED") ||
                              outputUpper.contains("HFP: CONNECTED")
            
            // Make sure we're not just seeing DISCONNECTED
            val hasDisconnectedOnly = outputUpper.contains("DISCONNECTED") && !hasConnected
            
            val isConnected = hasConnected && !hasDisconnectedOnly
            
            Log.d(TAG, "🔵 Method 1: hasConnected=$hasConnected, hasDisconnectedOnly=$hasDisconnectedOnly, result=$isConnected")
            
            isConnected
            
        } catch (e: Exception) {
            Log.e(TAG, "🔵 Method 1 exception", e)
            null
        }
    }

    /**
     * Method 2: Check Bluetooth connection via audio subsystem.
     * Detects active Bluetooth audio output devices.
     * 
     * @return true if connected, false if not connected, null if detection failed
     */
    private suspend fun checkAudioOutput(): Boolean? {
        return try {
            val result = rootManager.executeCommand(
                "dumpsys audio | grep -i -E 'DEVICE_OUT.*BLUETOOTH|Output Device.*BLUETOOTH|mBluetoothA2dp.*true'"
            )
            
            if (!result.success) {
                Log.w(TAG, "🔵 Method 2: audio command failed")
                return null
            }

            val output = result.output.joinToString("\n")
            Log.d(TAG, "🔵 Method 2 output (first 200 chars): ${output.take(200)}")
            
            if (output.isEmpty()) {
                return null
            }
            
            val outputUpper = output.uppercase()
            
            val hasBluetoothOutput = outputUpper.contains("DEVICE_OUT_BLUETOOTH") ||
                                    outputUpper.contains("OUTPUT DEVICE: BLUETOOTH") ||
                                    outputUpper.contains("BLUETOOTHA2DP: TRUE") ||
                                    outputUpper.contains("BLUETOOTH_A2DP")
            
            Log.d(TAG, "🔵 Method 2: hasBluetoothOutput=$hasBluetoothOutput")
            
            hasBluetoothOutput
            
        } catch (e: Exception) {
            Log.e(TAG, "🔵 Method 2 exception", e)
            null
        }
    }

    /**
     * Method 3: Check Bluetooth connection via media session.
     * Detects active Bluetooth media playback sessions.
     * 
     * @return true if connected, false if not connected, null if detection failed
     */
    private suspend fun checkMediaSession(): Boolean? {
        return try {
            val result = rootManager.executeCommand(
                "dumpsys media_session | grep -i -A 5 'bluetooth'"
            )
            
            if (!result.success) {
                Log.w(TAG, "🔵 Method 3: media_session command failed")
                return null
            }

            val output = result.output.joinToString("\n")
            Log.d(TAG, "🔵 Method 3 output (first 200 chars): ${output.take(200)}")
            
            if (output.isEmpty()) {
                return null
            }
            
            val outputUpper = output.uppercase()
            
            val hasBluetoothMedia = (outputUpper.contains("BLUETOOTH") && 
                                    outputUpper.contains("ACTIVE")) ||
                                   (outputUpper.contains("BLUETOOTH") && 
                                    outputUpper.contains("PLAYING"))
            
            Log.d(TAG, "🔵 Method 3: hasBluetoothMedia=$hasBluetoothMedia")
            
            hasBluetoothMedia
            
        } catch (e: Exception) {
            Log.e(TAG, "🔵 Method 3 exception", e)
            null
        }
    }

    /**
     * Method 4: Check Bluetooth connection via bluetooth service.
     * Broad detection of any connected Bluetooth devices.
     * 
     * @return true if connected, false if not connected, null if detection failed
     */
    private suspend fun checkBluetoothService(): Boolean? {
        return try {
            val result = rootManager.executeCommand(
                "dumpsys bluetooth | grep -i -E 'connected devices|bonded.*connected'"
            )
            
            if (!result.success) {
                Log.w(TAG, "🔵 Method 4: bluetooth service command failed")
                return null
            }

            val output = result.output.joinToString("\n")
            Log.d(TAG, "🔵 Method 4 output (first 200 chars): ${output.take(200)}")
            
            if (output.isEmpty()) {
                return null
            }
            
            val outputUpper = output.uppercase()
            
            val hasConnectedDevices = (outputUpper.contains("CONNECTED DEVICES") && 
                                       !outputUpper.contains("CONNECTED DEVICES: NONE")) ||
                                      outputUpper.contains("BONDED AND CONNECTED")
            
            Log.d(TAG, "🔵 Method 4: hasConnectedDevices=$hasConnectedDevices")
            
            hasConnectedDevices
            
        } catch (e: Exception) {
            Log.e(TAG, "🔵 Method 4 exception", e)
            null
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
