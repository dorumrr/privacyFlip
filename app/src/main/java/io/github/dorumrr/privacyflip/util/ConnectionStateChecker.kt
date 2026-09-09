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
 * - Location: Uses dumpsys appops to check for active location requests (e.g., navigation apps)
 * - Microphone: Uses AudioManager to check call/communication mode
 */
class ConnectionStateChecker(
    private val context: Context,
    private val rootManager: RootManager
) {
    companion object {
        private const val TAG = "privacyFlip-ConnectionStateChecker"

        // Split out from isLocationInUse() so it can be unit-tested without a real device: the
        // awk command it classifies can only run through a real shell, but the classification
        // itself is plain string logic. See ConnectionStateCheckerTest.
        internal fun parseLocationInUseOutput(output: String): Boolean =
            output.isNotBlank() && !output.contains("NONE") && !output.contains("NOBLOCKS")
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
     *
     * #20: the original version of this check grepped dumpsys location for the literal words
     * "LocationRequest", "UpdateRecord", "Active" and "Listener[" - words that turn out not to
     * exist at all in modern Android's actual dumpsys location output. That almost certainly
     * explains why this always reported "not in use" even during active navigation.
     *
     * Reads Android's own AppOps usage tracking instead (dumpsys appops), the same subsystem the
     * OS's own privacy indicators are built on. An op can be logged as a single instant ("noteOp",
     * a one-off read) or as a held-open session ("startOp"/"finishOp", which is what produces the
     * "Running start at:" line this check looks for).
     *
     * Tracks two op families, not one, after a second audit round found the first attempt was
     * still incomplete:
     *  - COARSE_LOCATION / FINE_LOCATION: the per-app location-read ops.
     *  - MONITOR_LOCATION / MONITOR_HIGH_POWER_LOCATION: the "continually monitoring" ops -
     *    Android's own app-ops documentation (AppOps.md) names these as its example of the
     *    held-open pattern. On a real device, COARSE_LOCATION/FINE_LOCATION never once showed a
     *    held-open session across a full dumpsys appops dump, while MONITOR_LOCATION did - a
     *    navigation app's continuous fix stream is far more likely to hold that one open, so
     *    leaving it out would have reproduced #20 under a different name.
     *
     * Excludes the "android" package specifically. Confirmed live: Android's own system process
     * holds MONITOR_LOCATION open near-permanently for its own bookkeeping (observed on a real,
     * otherwise-idle device: the dark-theme sunrise/sunset timer and a system sensor-notification
     * component, both "running" 30+ minutes with no navigation happening at all). Without this
     * exclusion the check would read "in use" almost always, regardless of what the user is
     * actually doing - the opposite failure from #20, but just as broken. Any real third-party
     * app, including OEM-preloaded ones, carries its own package name and is still caught.
     *
     * A dumpsys shape this parser doesn't recognise (an untested Android version, an unexpected
     * OEM change) used to print the same "NONE" as a genuinely idle device - indistinguishable
     * even in debug logs. The command now prints "NOBLOCKS" instead when it never matched a
     * single location-family op header at all, so the two are told apart in the logs. This is
     * separate from the command failing outright (awk missing, dumpsys itself erroring), which
     * is still caught below by result.success and logged with whatever the shell reported.
     * Either way this function still returns the same safe "not in use".
     */
    private suspend fun isLocationInUse(): Boolean {
        return try {
            val result = rootManager.executeCommand(
                "dumpsys appops | awk '/^    Package /{pkg=\$0; sub(/^    Package /,\"\",pkg); sub(/:\$/,\"\",pkg)} /^      [A-Z_]+ \\(/{if (\$0 ~ /^      (COARSE_LOCATION|FINE_LOCATION|MONITOR_LOCATION|MONITOR_HIGH_POWER_LOCATION) \\(/){inloc=1;sawblock=1}else{inloc=0}} inloc && pkg!=\"android\" && /Running start at/{print; found=1} END{if (!found){if (sawblock) print \"NONE\"; else print \"NOBLOCKS\"}}'"
            )

            if (!result.success) {
                // Include the shell's own error (e.g. "awk: not found" on a device without it) -
                // the previous version of this log dropped it, leaving no way to tell why this
                // failed from the logs alone.
                Log.w(TAG, "📍 Failed to check location usage state via dumpsys appops: ${result.error ?: "no error detail"}")
                return false
            }

            val output = result.output.joinToString("\n")
            if (output.contains("NOBLOCKS")) {
                Log.w(TAG, "📍 dumpsys appops never showed a location op block at all - detection may not work on this Android version/OEM, treating as not in use")
            }
            val isInUse = parseLocationInUseOutput(output)

            Log.i(TAG, "📍 Location usage check: ${if (isInUse) "IN USE" else "NOT IN USE"}")
            isInUse
        } catch (e: Exception) {
            Log.e(TAG, "📍 Error checking location usage state", e)
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
