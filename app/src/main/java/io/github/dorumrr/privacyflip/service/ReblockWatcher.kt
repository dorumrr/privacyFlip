package io.github.dorumrr.privacyflip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.location.LocationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.util.Constants
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.util.PrivacyActionWork
import io.github.dorumrr.privacyflip.util.isScreenCurrentlyLocked
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Block": while the phone is locked, a feature the lock turned off that someone turns back on
 * queues the lock job again. Every signal is only a nudge; the lock job reads the real state.
 */
class ReblockWatcher(
    private val context: Context,
    private val mergeWindowMs: Long = MERGE_WINDOW_MS
) {

    companion object {
        private const val TAG = "privacyFlip-ReblockWatcher"
        internal const val MERGE_WINDOW_MS = 2_000L
        internal const val MAX_RUNS_PER_LOCK = 5
        private val MOBILE_DATA_KEY = Regex("mobile_data\\d*")

        // Bluetooth's broadcast needs BLUETOOTH_CONNECT and mobile data has none, so both are read from settings.
        internal fun undoneBySetting(key: String, value: Int): PrivacyFeature? = when {
            key == Settings.Global.BLUETOOTH_ON && value != 0 -> PrivacyFeature.BLUETOOTH
            MOBILE_DATA_KEY.matches(key) && value != 0 -> PrivacyFeature.MOBILE_DATA
            else -> null
        }

        internal fun undoneByBroadcast(
            intent: Intent,
            locationOn: () -> Boolean,
            powerSaveOn: () -> Boolean
        ): PrivacyFeature? = when (intent.action) {
            WifiManager.WIFI_STATE_CHANGED_ACTION ->
                PrivacyFeature.WIFI.takeIf {
                    intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN) in
                        setOf(WifiManager.WIFI_STATE_ENABLING, WifiManager.WIFI_STATE_ENABLED)
                }
            NfcAdapter.ACTION_ADAPTER_STATE_CHANGED ->
                PrivacyFeature.NFC.takeIf {
                    intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF) in
                        setOf(NfcAdapter.STATE_TURNING_ON, NfcAdapter.STATE_ON)
                }
            LocationManager.PROVIDERS_CHANGED_ACTION -> PrivacyFeature.LOCATION.takeIf { locationOn() }
            Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                PrivacyFeature.AIRPLANE_MODE.takeIf { !intent.getBooleanExtra("state", true) }
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> PrivacyFeature.BATTERY_SAVER.takeIf { !powerSaveOn() }
            else -> null
        }

        // A protection mode counts only while this app owns it, or the user's own choice would be fought.
        internal fun shouldReblock(
            feature: PrivacyFeature,
            screenLocked: Boolean,
            lastLockAtMillis: Long,
            lastUnlockAtMillis: Long,
            offSinceUnlock: Set<PrivacyFeature>,
            ownedByApp: Boolean,
            runsThisLock: Int
        ): Boolean {
            if (!screenLocked || lastLockAtMillis <= lastUnlockAtMillis || runsThisLock >= MAX_RUNS_PER_LOCK) return false
            return if (feature in PrivacyFeature.getSystemModeFeatures()) ownedByApp else feature in offSinceUnlock
        }
    }

    private val preferenceManager = PreferenceManager.getInstance(context)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    // Touched only on the scheduler thread.
    private val pending = mutableMapOf<PrivacyFeature, Long>()
    private var runsThisLock = 0
    private var countedSinceUnlockAt = Long.MIN_VALUE

    // Touched only on the main thread.
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            undoneByBroadcast(
                intent,
                locationOn = {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    lm != null && LocationManagerCompat.isLocationEnabled(lm)
                },
                powerSaveOn = {
                    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
                }
            )?.let(::nudge)
        }
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val key = uri?.lastPathSegment ?: return
            val value = try {
                Settings.Global.getInt(context.contentResolver, key, 0)
            } catch (e: Exception) {
                return
            }
            undoneBySetting(key, value)?.let(::nudge)
        }
    }

    private val settingListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Constants.Preferences.KEY_BLOCK_WHILE_LOCKED) sync()
    }

    fun start() {
        preferenceManager.registerChangeListener(settingListener)
        sync()
    }

    fun stop() {
        preferenceManager.unregisterChangeListener(settingListener)
        unregister()
        scheduler.shutdownNow()
    }

    private fun sync() {
        if (preferenceManager.blockWhileLocked) register() else unregister()
    }

    private fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        try {
            context.registerReceiver(receiver, filter)
            context.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
            registered = true
            Log.i(TAG, "Block is on - watching for features changed back while locked")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start watching", e)
            unregister()
        }
    }

    private fun unregister() {
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        context.contentResolver.unregisterContentObserver(observer)
        if (registered) Log.i(TAG, "Block is off - no longer watching")
        registered = false
    }

    private fun nudge(feature: PrivacyFeature) {
        try {
            scheduler.execute {
                // The first change opens the window; the rest join the run it schedules.
                if (pending.isEmpty()) scheduler.schedule(::fire, mergeWindowMs, TimeUnit.MILLISECONDS)
                pending.getOrPut(feature) { SystemClock.elapsedRealtime() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Stopped: the service is going away.
        }
    }

    private fun fire() {
        val firstSeenAt = pending.toMap()
        val features = firstSeenAt.keys
        pending.clear()
        if (!preferenceManager.blockWhileLocked) return
        val lastLock = PrivacyActionWorker.lastLockAtMillis
        val lastUnlock = PrivacyActionWorker.lastUnlockAtMillis
        if (lastUnlock != countedSinceUnlockAt) {
            countedSinceUnlockAt = lastUnlock
            runsThisLock = 0
        }
        val locked = isScreenCurrentlyLocked(context, TAG)
        val offSinceUnlock = preferenceManager.featuresOffSinceUnlock
        val due = features.filter {
            shouldReblock(it, locked, lastLock, lastUnlock, offSinceUnlock, preferenceManager.getFeatureEnabledByApp(it), runsThisLock)
        }
        if (due.isEmpty()) return
        if (lockJobWaiting()) {
            Log.d(TAG, "A lock job is already waiting; it reads the state when it runs")
            return
        }
        runsThisLock++
        Log.i(TAG, "Changed back while locked: ${due.map { it.displayName }} - queuing the lock job again ($runsThisLock of $MAX_RUNS_PER_LOCK)")
        PrivacyActionWork.enqueue(
            context,
            isLocking = true,
            isDeviceLocked = true,
            trigger = PrivacyActionWork.TRIGGER_REBLOCK,
            reason = "Changed back while locked: ${due.joinToString { it.displayName }}",
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            foundOnAt = firstSeenAt.filterKeys { it in due }
        )
    }

    // An unreadable queue counts as waiting: a missed reblock is safer than one queued after stop().
    private fun lockJobWaiting(): Boolean = try {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(Constants.Work.NAME_LOCK).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    } catch (e: Exception) {
        true
    }
}
