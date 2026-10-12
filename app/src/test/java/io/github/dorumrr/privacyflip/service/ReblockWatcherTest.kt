package io.github.dorumrr.privacyflip.service

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.initWorkManagerWithoutRealWork
import io.github.dorumrr.privacyflip.util.Constants
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.util.PrivacyActionWork
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReblockWatcherTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = PreferenceManager.getInstance(context)
    private val runs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var watcher: ReblockWatcher? = null

    @Before
    fun lockedWithWifiTurnedOff() {
        initWorkManagerWithoutRealWork(context, onRun = { params ->
            runs += params.inputData.getString(PrivacyActionWork.KEY_TRIGGER).orEmpty()
        })
        prefs.blockWhileLocked = true
        prefs.removeFeaturesOffSinceUnlock(PrivacyFeature.values().toList())
        prefs.addFeaturesOffSinceUnlock(listOf(PrivacyFeature.WIFI))
        PrivacyActionWorker.lastUnlockAtMillis = 1L
        PrivacyActionWorker.lastLockAtMillis = 2L
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(true)
    }

    @After
    fun stopWatching() {
        watcher?.stop()
        prefs.blockWhileLocked = false
        prefs.removeFeaturesOffSinceUnlock(PrivacyFeature.values().toList())
        PrivacyActionWorker.lastLockAtMillis = 0L
        PrivacyActionWorker.lastUnlockAtMillis = 0L
    }

    private fun startWatcher() = ReblockWatcher(context, mergeWindowMs = 50).also { it.start(); watcher = it }

    private fun wifiTurnedOn() {
        context.sendBroadcast(
            Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED)
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    // WorkManager starts a job queued behind another only from the main looper, so the wait must drive it.
    private fun awaitRuns(atLeast: Int): Int {
        val end = System.currentTimeMillis() + 3_000
        while (runs.size < atLeast && System.currentTimeMillis() < end) idleFor(20)
        idleFor(300)
        return runs.size
    }

    private fun idleFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `Wi-Fi turned back on while locked queues the lock job as a reblock`() {
        startWatcher()

        wifiTurnedOn()

        assertEquals(1, awaitRuns(1))
        assertEquals(PrivacyActionWork.TRIGGER_REBLOCK, runs.single())
    }

    @Test
    fun `with Block off nothing is watched`() {
        prefs.blockWhileLocked = false
        startWatcher()

        wifiTurnedOn()

        assertEquals(0, awaitRuns(1))
    }

    @Test
    fun `switching Block on starts the watch without a service restart`() {
        prefs.blockWhileLocked = false
        startWatcher()
        prefs.blockWhileLocked = true

        wifiTurnedOn()

        assertEquals(1, awaitRuns(1))
    }

    @Test
    fun `switching Block off before the run starts queues nothing`() {
        ReblockWatcher(context, mergeWindowMs = 500).also { it.start(); watcher = it }

        wifiTurnedOn()
        prefs.blockWhileLocked = false

        assertEquals(0, awaitRuns(1))
    }

    @Test
    fun `turn-ons close together merge into one run`() {
        ReblockWatcher(context, mergeWindowMs = 500).also { it.start(); watcher = it }

        wifiTurnedOn()
        wifiTurnedOn()
        wifiTurnedOn()

        assertEquals(1, awaitRuns(2))
    }

    @Test
    fun `one lock runs at most five reblocks, and an unlock resets the count`() {
        startWatcher()
        repeat(7) {
            wifiTurnedOn()
            awaitRuns(it + 1)
        }
        assertEquals(ReblockWatcher.MAX_RUNS_PER_LOCK, runs.size)

        PrivacyActionWorker.lastUnlockAtMillis = 3L
        PrivacyActionWorker.lastLockAtMillis = 4L
        wifiTurnedOn()

        assertEquals(ReblockWatcher.MAX_RUNS_PER_LOCK + 1, awaitRuns(ReblockWatcher.MAX_RUNS_PER_LOCK + 1))
    }

    @Test
    fun `a lock job already waiting takes the turn-on, and nothing more is queued`() {
        val waiting = androidx.work.OneTimeWorkRequestBuilder<PrivacyActionWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS)
            .build()
        val workManager = androidx.work.WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(Constants.Work.NAME_LOCK, androidx.work.ExistingWorkPolicy.REPLACE, waiting).result.get()
        startWatcher()

        wifiTurnedOn()
        idleFor(500)

        assertEquals(1, workManager.getWorkInfosForUniqueWork(Constants.Work.NAME_LOCK).get().size)
    }

    @Test
    fun `Bluetooth turned on is read from its setting`() {
        prefs.addFeaturesOffSinceUnlock(listOf(PrivacyFeature.BLUETOOTH))
        startWatcher()

        Settings.Global.putInt(context.contentResolver, Settings.Global.BLUETOOTH_ON, 1)
        context.contentResolver.notifyChange(Uri.withAppendedPath(Settings.Global.CONTENT_URI, Settings.Global.BLUETOOTH_ON), null)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, awaitRuns(1))
    }

    @Test
    fun `settings mapping names only a feature turned on, or a protection mode turned off`() {
        assertEquals(PrivacyFeature.BLUETOOTH, ReblockWatcher.undoneBySetting("bluetooth_on", 1))
        assertNull(ReblockWatcher.undoneBySetting("bluetooth_on", 0))
        assertEquals(PrivacyFeature.MOBILE_DATA, ReblockWatcher.undoneBySetting("mobile_data", 1))
        assertEquals(PrivacyFeature.MOBILE_DATA, ReblockWatcher.undoneBySetting("mobile_data3", 1))
        assertNull(ReblockWatcher.undoneBySetting("mobile_data", 0))
        assertNull(ReblockWatcher.undoneBySetting("mobile_data_always_on", 1))
        assertNull(ReblockWatcher.undoneBySetting("wifi_on", 1))
    }

    @Test
    fun `broadcast mapping follows the direction the lock cares about`() {
        fun map(intent: Intent, location: Boolean = false, powerSave: Boolean = true) =
            ReblockWatcher.undoneByBroadcast(intent, { location }, { powerSave })

        assertEquals(PrivacyFeature.WIFI, map(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLING)))
        assertNull(map(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED)))
        assertEquals(PrivacyFeature.NFC, map(Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED).putExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_ON)))
        assertNull(map(Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED).putExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_TURNING_OFF)))
        assertEquals(PrivacyFeature.LOCATION, map(Intent(android.location.LocationManager.PROVIDERS_CHANGED_ACTION), location = true))
        assertNull(map(Intent(android.location.LocationManager.PROVIDERS_CHANGED_ACTION), location = false))
        assertEquals(PrivacyFeature.AIRPLANE_MODE, map(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false)))
        assertNull(map(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", true)))
        assertEquals(PrivacyFeature.BATTERY_SAVER, map(Intent(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), powerSave = false))
        assertNull(map(Intent(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), powerSave = true))
    }

    @Test
    fun `only a feature this lock turned off, while the lock is in force, is blocked again`() {
        fun decide(
            feature: PrivacyFeature = PrivacyFeature.WIFI,
            locked: Boolean = true,
            lastLock: Long = 2,
            lastUnlock: Long = 1,
            off: Set<PrivacyFeature> = setOf(PrivacyFeature.WIFI),
            owned: Boolean = false,
            runs: Int = 0
        ) = ReblockWatcher.shouldReblock(feature, locked, lastLock, lastUnlock, off, owned, runs)

        assertTrue(decide())
        assertFalse("phone unlocked", decide(locked = false))
        assertFalse("an unlock is newer than the lock", decide(lastLock = 1, lastUnlock = 2))
        assertFalse("this lock did not turn it off, for example an exempt app", decide(off = emptySet()))
        assertFalse("cap reached", decide(runs = ReblockWatcher.MAX_RUNS_PER_LOCK))
        assertTrue("a protection mode this app turned on", decide(feature = PrivacyFeature.AIRPLANE_MODE, off = emptySet(), owned = true))
        assertFalse("a protection mode the user set", decide(feature = PrivacyFeature.AIRPLANE_MODE, owned = false))
    }
}
