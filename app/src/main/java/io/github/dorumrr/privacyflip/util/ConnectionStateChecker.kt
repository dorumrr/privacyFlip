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
     * single location-family op header at all, so the two are told apart in the logs. This
     * reliably catches awk itself being missing (a pipeline's exit status is its last stage's,
     * so a missing awk fails the whole pipe and is caught by result.success below). It does NOT
     * reliably catch dumpsys itself failing partway through while awk still exits 0 on whatever
     * partial input it got - that case is indistinguishable from NOBLOCKS from here. Either way
     * this function still returns the same safe "not in use"; only the log's stated reason can
     * be wrong, never the answer.
     *
     * #A1: confirmed live that awk does not exist at all on Android 8, and that Android 9's awk
     * build (toybox, dated 2012) SEGFAULTS on this command - isolated all the way down to
     * `!found` specifically, a bare logical-not on a variable inside an END block. Neither `!=`
     * elsewhere in this same command, nor an unnegated truthy check, trips it - only that one
     * exact shape, on that one old build. `found==0` does the identical job without it.
     * Re-verified after the fix, live, on the same broken build (the Pixel_2_AOSP_9_API_28 AVD):
     * no crash, right answer for an idle device, right answer for a synthetic active one, right
     * answer for no location blocks at all, and no change on Android 10+ or the real device used
     * all session.
     *
     * #20, second gap: "Running start at" only ever appears while a session is held open
     * (startOp called, finishOp not yet called). An app that asks for a fix in short bursts -
     * noteOp, not startOp/finishOp - never produces that line at all, even mid-navigation, so a
     * lock landing between two bursts used to read as "not in use". Every "Access:" line already
     * carries how long ago that read happened, e.g. "(-2s410ms)" or "(-331ms)" - a second rule
     * now also counts a location-family access from the last ~6 seconds as in use, same package
     * exclusion, same op-block scoping. Matches only the two shapes dumpsys is expected to print
     * for "just now": bare milliseconds (confirmed against real captured output, e.g. "-331ms"
     * in android-housekeeping-api28-real.txt) or a single-digit second plus milliseconds (NOT
     * yet seen in any real capture this repo has - both real fixtures only ever show
     * two-digit-or-larger seconds for anything under a minute, e.g. "-29s137ms", "-14s3ms"; this
     * branch is proven only against the synthetic fixture written for it,
     * active-thirdparty-burst-synthetic.txt). Neither shape is a bare whole-second form (e.g.
     * "-5s" with no trailing ms), which also has not been seen in any real capture, so this
     * cannot mistake an older access ("-14s3ms", "-58m47s35ms") for a recent one without doing
     * time arithmetic in awk, which is exactly the kind of construct #A1 found an old toybox
     * build cannot be trusted with. A dumpsys shape that doesn't match either rule falls back to
     * the existing NONE/NOBLOCKS handling - never a crash, only a missed detection, same failure
     * mode this check already had.
     *
     * Audit, same night: 2 findings against the block above, both fixed here.
     *  - The gate that decides "did a new op start" used to require the exact shape
     *    `[A-Z_]+ \(` (op name in caps, then a space and a paren). A header this parser doesn't
     *    recognise - a lowercase or differently-punctuated OEM op name - matched nothing, so the
     *    block-membership flag never reset and leaked into whatever came after it, which a
     *    fresher access-recency line could then wrongly attribute to Location. The gate is now
     *    just "a new op-header-depth line started" (`^      [^ ]`, exactly 6 leading spaces then
     *    anything) - real per-op detail is always indented deeper than that in every real
     *    capture this repo has, so this can only end a block sooner or on time, never early.
     *    Which op the block actually belongs to still requires the exact location-op names,
     *    unchanged.
     *  - The bare-milliseconds branch had no upper bound (`[0-9]+ms`), so it would have matched
     *    ANY digit count, not just the sub-1-second values real captures actually show - an
     *    older access dumpsys happened to print as bare ms (nothing seen doing this, but nothing
     *    ruled it out either) would have been wrongly treated as "just now". Capped to 1-3
     *    digits (`[0-9][0-9]?[0-9]?ms`, i.e. under 1 second) with plain `?`, not a `{1,3}`
     *    interval - interval syntax is untested on the old toybox build #A1 already found one
     *    real crash on, `?` is already used elsewhere in this same command with no issue.
     */
    private suspend fun isLocationInUse(): Boolean {
        return try {
            val result = rootManager.executeCommand(
                "dumpsys appops | awk '/^    Package /{pkg=\$0; sub(/^    Package /,\"\",pkg); sub(/:\$/,\"\",pkg)} /^      [^ ]/{if (\$0 ~ /^      (COARSE_LOCATION|FINE_LOCATION|MONITOR_LOCATION|MONITOR_HIGH_POWER_LOCATION) \\(/){inloc=1;sawblock=1}else{inloc=0}} inloc && pkg!=\"android\" && /Running start at/{print; found=1} inloc && pkg!=\"android\" && /Access:/ && \$0 ~ /\\(-([0-9][0-9]?[0-9]?ms|[0-5]s[0-9]+ms)\\)/{print; found=1} END{if (found==0){if (sawblock) print \"NONE\"; else print \"NOBLOCKS\"}}'"
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
