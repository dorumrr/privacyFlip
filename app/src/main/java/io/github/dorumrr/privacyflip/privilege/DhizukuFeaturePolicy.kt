package io.github.dorumrr.privacyflip.privilege

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.location.LocationManager
import android.media.AudioManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.UserManager
import android.util.Log
import com.rosan.dhizuku.api.Dhizuku
import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * The privacy features expressed as Device Owner calls.
 *
 * Dhizuku shares Device Owner rights, not shell rights, so every `svc` and `settings put` command
 * the toggles build is denied. A Device Owner may do far less, and four of the nine features have
 * no Device Owner route at all.
 */
internal object DhizukuFeaturePolicy {

    private const val TAG = "privacyFlip-DhizukuFeaturePolicy"

    /**
     * Restrictions FORBID a feature rather than switching it off: while one is set the user cannot
     * re-enable it from Settings at all, and it outlives this process. Every restriction this app
     * can set is listed here so [releaseAll] can lift the lot.
     */
    private val RESTRICTIONS: Map<PrivacyFeature, String> = mapOf(
        PrivacyFeature.BLUETOOTH to UserManager.DISALLOW_BLUETOOTH,
        PrivacyFeature.MICROPHONE to UserManager.DISALLOW_UNMUTE_MICROPHONE,
        PrivacyFeature.NFC to UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO
    )

    /** Real on/off switches, with none of the restriction lock-out problem. */
    private val DIRECT = setOf(PrivacyFeature.LOCATION, PrivacyFeature.CAMERA)

    private fun minSdkFor(feature: PrivacyFeature): Int = when (feature) {
        PrivacyFeature.LOCATION -> Build.VERSION_CODES.R
        PrivacyFeature.BLUETOOTH -> Build.VERSION_CODES.P
        PrivacyFeature.NFC -> Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        else -> Build.VERSION_CODES.O
    }

    fun supports(feature: PrivacyFeature): Boolean =
        (feature in DIRECT || feature in RESTRICTIONS) && Build.VERSION.SDK_INT >= minSdkFor(feature)

    /**
     * Why a feature is impossible on this backend, not merely failing. Reads as the tail of
     * "Failed to disable X: ...", so it never repeats the feature name.
     */
    fun unsupportedReason(feature: PrivacyFeature): String = when (feature) {
        PrivacyFeature.WIFI, PrivacyFeature.MOBILE_DATA ->
            "Dhizuku grants Device Owner rights only, and a Device Owner may block changes to this but cannot turn it off. Use Root or Shizuku instead."
        PrivacyFeature.AIRPLANE_MODE ->
            "Dhizuku grants Device Owner rights only, and a Device Owner may block Airplane Mode but cannot turn it on. Use Root or Shizuku instead."
        PrivacyFeature.BATTERY_SAVER ->
            "Dhizuku grants Device Owner rights only, and no Device Owner holds DEVICE_POWER. Use Root or Shizuku instead."
        else ->
            "Dhizuku needs Android API ${minSdkFor(feature)} for this, and this device is API ${Build.VERSION.SDK_INT}"
    }

    @Volatile
    private var routed: DevicePolicyManager? = null

    /**
     * A DevicePolicyManager whose calls run as the Device Owner.
     *
     * The private field swap is the only way in: DevicePolicyManager has no public constructor, so
     * its binder has to be replaced with the one Dhizuku wraps. This mutates the process-wide
     * instance, which is safe only because this app uses DevicePolicyManager for nothing else.
     */
    private fun ownerDpm(context: Context): DevicePolicyManager? {
        routed?.let { return it }
        return synchronized(this) {
            routed ?: try {
                // The blocklist reports a blocked field as ABSENT, so without this the swap below
                // fails with "No field mService" and every feature looks broken.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    HiddenApiBypass.addHiddenApiExemptions("Landroid/app/admin/")
                }
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val field = DevicePolicyManager::class.java.getDeclaredField("mService")
                field.isAccessible = true
                val service = field.get(dpm) as IInterface
                val wrapped = Dhizuku.binderWrapper(service.asBinder())
                val stub = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
                val asInterface = stub.getMethod("asInterface", IBinder::class.java)
                field.set(dpm, asInterface.invoke(null, wrapped))
                routed = dpm
                dpm
            } catch (e: Exception) {
                Log.e(TAG, "Cannot route DevicePolicyManager through Dhizuku: ${e.message}")
                null
            }
        }
    }

    fun apply(context: Context, feature: PrivacyFeature, enable: Boolean): CommandResult {
        if (!supports(feature)) return CommandResult.failure(unsupportedReason(feature))

        val dpm = ownerDpm(context)
            ?: return CommandResult.failure("Dhizuku could not provide Device Owner access")
        val admin = Dhizuku.getOwnerComponent()
            ?: return CommandResult.failure("Dhizuku reported no Device Owner component")

        return try {
            when (feature) {
                PrivacyFeature.LOCATION -> dpm.setLocationEnabled(admin, enable)
                PrivacyFeature.CAMERA -> dpm.setCameraDisabled(admin, !enable)
                else -> {
                    val restriction = RESTRICTIONS.getValue(feature)
                    if (enable) dpm.clearUserRestriction(admin, restriction)
                    else dpm.addUserRestriction(admin, restriction)
                }
            }
            Log.i(TAG, "${feature.displayName} ${if (enable) "enabled" else "disabled"} as Device Owner")
            CommandResult.success()
        } catch (e: Exception) {
            CommandResult.failure("Device Owner call failed for ${feature.displayName}: ${e.message}")
        }
    }

    /**
     * What the feature's state really is on this backend.
     *
     * The toggles read state with shell commands, which Dhizuku denies, so without this a disable
     * that genuinely worked reads back as unchanged and gets reported to the user as a failure.
     *
     * A set restriction is checked FIRST and is decisive: while `no_bluetooth` is in force the
     * adapter setting can still read "on" even though Bluetooth is forbidden, and reporting that
     * as ENABLED would tell the user something untrue.
     *
     * @return null when this backend has no answer, so the caller should use its own commands
     */
    fun readState(context: Context, feature: PrivacyFeature): FeatureState? {
        if (!supports(feature)) return null
        return try {
            RESTRICTIONS[feature]?.let { restriction ->
                val users = context.getSystemService(Context.USER_SERVICE) as UserManager
                if (users.hasUserRestriction(restriction)) return FeatureState.DISABLED
            }
            when (feature) {
                PrivacyFeature.LOCATION -> {
                    val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    if (locations.isLocationEnabled) FeatureState.ENABLED else FeatureState.DISABLED
                }
                PrivacyFeature.CAMERA -> {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    if (dpm.getCameraDisabled(null)) FeatureState.DISABLED else FeatureState.ENABLED
                }
                PrivacyFeature.NFC -> when (val nfc = NfcAdapter.getDefaultAdapter(context)) {
                    null -> FeatureState.UNAVAILABLE
                    else -> if (nfc.isEnabled) FeatureState.ENABLED else FeatureState.DISABLED
                }
                PrivacyFeature.MICROPHONE -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (audio.isMicrophoneMute) FeatureState.DISABLED else FeatureState.ENABLED
                }
                // No permission-free read exists, and the restriction above already answered the
                // only case this backend creates.
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${feature.displayName} as Device Owner: ${e.message}")
            null
        }
    }

    /**
     * Lifts every restriction this app can set.
     *
     * A restriction outlives the process, so a crash, a force-stop or an uninstall between lock and
     * unlock would otherwise leave the user unable to turn Bluetooth, NFC or the microphone back on
     * from Settings at all. Called at startup, so the worst case is one lock cycle, not forever.
     */
    fun releaseAll(context: Context) {
        val dpm = ownerDpm(context) ?: return
        val admin = Dhizuku.getOwnerComponent() ?: return
        RESTRICTIONS.forEach { (feature, restriction) ->
            if (Build.VERSION.SDK_INT < minSdkFor(feature)) return@forEach
            try {
                dpm.clearUserRestriction(admin, restriction)
            } catch (e: Exception) {
                Log.w(TAG, "Could not lift $restriction: ${e.message}")
            }
        }
    }
}
