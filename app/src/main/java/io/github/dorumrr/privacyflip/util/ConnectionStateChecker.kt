package io.github.dorumrr.privacyflip.util

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.root.RootManager

/**
 * Utility class to check if connectivity features are currently in use/connected.
 * Used to implement "only disable if not connected" feature.
 * 
 * Detection methods:
 * - WiFi: Uses dumpsys connectivity to check for active WIFI connection
 * - Bluetooth: Uses BluetoothAdapter's own connection-state API (needs BLUETOOTH_CONNECT
 *   on Android 12+) - no dumpsys parsing, see isBluetoothConnected()
 * - Hotspot: Uses dumpsys tethering to check for an active tethered interface
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
     * Check if a WiFi hotspot (tethering) is currently active and serving a device.
     *
     * WiFi client mode and hotspot/AP mode share the same radio, so disabling WiFi
     * on lock also kills a running hotspot for anyone tethered to it (#34). Uses
     * Android's own tethering service (dumpsys tethering), which is a stable,
     * OEM-independent part of the platform since Android 11, unlike the WiFi
     * AP-state fields in "dumpsys wifi" which vary by manufacturer and ROM.
     *
     * "TetheredState" is the interface state Android uses specifically for an
     * active, serving hotspot (confirmed on a real device: the idle state reads
     * "ap0 - AvailableState"). Only that exact state counts, so a device merely
     * capable of tethering (but not doing it) is never mistaken for an active one.
     */
    suspend fun isHotspotActive(): Boolean {
        return try {
            val result = rootManager.executeCommand(
                "dumpsys tethering | grep -A6 'Tether state:'"
            )

            if (!result.success) {
                Log.w(TAG, "📡 Failed to check hotspot state via dumpsys tethering")
                return false
            }

            val output = result.output.joinToString("\n")
            // Tetherable WiFi interfaces per AOSP: wlan\d, softap\d, ap\d, swlan0
            val isActive = Regex("(?:wlan\\d+|softap\\d+|ap\\d+|swlan0)\\s*-\\s*TetheredState", RegexOption.IGNORE_CASE)
                .containsMatchIn(output)

            Log.i(TAG, "📡 Hotspot check: ${if (isActive) "ACTIVE" else "NOT ACTIVE"}")
            isActive
        } catch (e: Exception) {
            Log.e(TAG, "📡 Error checking hotspot state", e)
            false
        }
    }

    /**
     * Check if Bluetooth is connected to any device, using Android's own
     * BluetoothAdapter API - not dumpsys text. #28 traced back to the old
     * approach (4 stacked guesses at raw dumpsys wording) silently failing on
     * some phone makers whose dumpsys output doesn't match any of the guessed
     * patterns. getProfileConnectionState() is the same synchronous, no-root
     * API real launcher/accessory apps use, and needs no shell access at all.
     *
     * Needs BLUETOOTH_CONNECT (a runtime-prompted permission from Android 12+,
     * requested from the settings screen when this feature is turned on - see
     * MainFragment's onlyIfUnusedCheckbox listener). Below Android 12 the older,
     * non-prompting BLUETOOTH permission covers it.
     */
    private fun isBluetoothConnected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "🔵 BLUETOOTH_CONNECT not granted - cannot check Bluetooth connection state")
                return false
            }

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                Log.d(TAG, "🔵 Bluetooth adapter unavailable or off - NOT CONNECTED")
                return false
            }

            // The profiles a real accessory (headphones, a car kit, a hearing aid)
            // actually connects through. Not an exhaustive list of every profile
            // Android has - just the ones relevant to "is something in active use".
            val profiles = listOf(
                BluetoothProfile.A2DP to "A2DP (audio)",
                BluetoothProfile.HEADSET to "HEADSET (calls)",
                BluetoothProfile.HEARING_AID to "HEARING_AID"
            )
            val connectedOn = profiles.firstOrNull { (profile, _) ->
                adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED
            }

            Log.i(TAG, "🔵 Bluetooth connection check: ${if (connectedOn != null) "CONNECTED (${connectedOn.second})" else "NOT CONNECTED"}")
            connectedOn != null
        } catch (e: SecurityException) {
            Log.w(TAG, "🔵 Missing Bluetooth permission when checking connection state", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "🔵 Error checking Bluetooth connection state", e)
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
