package io.github.dorumrr.privacyflip.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.data.PrivacyResult
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.util.PrivacyActionWork
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Drives the REAL doWork(), not decision functions lifted out of it.
 *
 * doWork() used to build all six of its helpers inside its own body, so nothing could stand in
 * for one and every decision it makes needed a real privileged shell to reach. Its call sites
 * were therefore read and reasoned about, never run: the ownership rule, both supersede guards
 * and what the user is actually told all rested on a reading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivacyActionWorkerDoWorkTest {

    private lateinit var context: Application
    private lateinit var prefs: PreferenceManager
    private lateinit var saved: Snapshot
    private var clock = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferenceManager.getInstance(context)
        ShadowLog.clear()
        saved = Snapshot.of(prefs)
        applyBaseline()
        PrivacyActionWorker.lastLockAtMillis = 0L
        PrivacyActionWorker.lastUnlockAtMillis = 0L
        clock = 0L
    }

    @After
    fun tearDown() {
        // PreferenceManager is a process singleton that Robolectric does not reset, so anything
        // this class leaves behind reaches later test CLASSES. Putting back what was read, rather
        // than this class's own baseline, keeps it a no-op whatever the defaults are.
        saved.restoreInto(prefs)
        PrivacyActionWorker.lastLockAtMillis = 0L
        PrivacyActionWorker.lastUnlockAtMillis = 0L
    }

    private data class Flags(
        val disableOnLock: Boolean,
        val enableOnUnlock: Boolean,
        val onlyIfUnused: Boolean,
        val onlyIfNotEnabled: Boolean,
        val onlyIfNotManual: Boolean,
        val enabledByApp: Boolean
    )

    private class Snapshot(
        val globalPrivacy: Boolean,
        val debugNotifications: Boolean,
        val lockDelay: Int,
        val unlockDelay: Int,
        val exemptApps: Set<String>,
        val perFeature: Map<PrivacyFeature, Flags>
    ) {
        fun restoreInto(prefs: PreferenceManager) {
            prefs.isGlobalPrivacyEnabled = globalPrivacy
            prefs.debugNotificationsEnabled = debugNotifications
            prefs.lockDelaySeconds = lockDelay
            prefs.unlockDelaySeconds = unlockDelay
            prefs.setExemptApps(exemptApps)
            perFeature.forEach { (feature, f) ->
                prefs.setFeatureDisableOnLock(feature, f.disableOnLock)
                prefs.setFeatureEnableOnUnlock(feature, f.enableOnUnlock)
                prefs.setFeatureOnlyIfUnused(feature, f.onlyIfUnused)
                prefs.setFeatureOnlyIfNotEnabled(feature, f.onlyIfNotEnabled)
                prefs.setFeatureOnlyIfNotManual(feature, f.onlyIfNotManual)
                prefs.setFeatureEnabledByApp(feature, f.enabledByApp)
            }
        }

        companion object {
            fun of(prefs: PreferenceManager) = Snapshot(
                globalPrivacy = prefs.isGlobalPrivacyEnabled,
                debugNotifications = prefs.debugNotificationsEnabled,
                lockDelay = prefs.lockDelaySeconds,
                unlockDelay = prefs.unlockDelaySeconds,
                exemptApps = prefs.getExemptApps(),
                perFeature = PrivacyFeature.values().associateWith {
                    Flags(
                        disableOnLock = prefs.getFeatureDisableOnLock(it),
                        enableOnUnlock = prefs.getFeatureEnableOnUnlock(it),
                        onlyIfUnused = prefs.getFeatureOnlyIfUnused(it),
                        onlyIfNotEnabled = prefs.getFeatureOnlyIfNotEnabled(it),
                        onlyIfNotManual = prefs.getFeatureOnlyIfNotManual(it),
                        enabledByApp = prefs.getFeatureEnabledByApp(it)
                    )
                }
            )
        }
    }

    /** Everything off, so each test turns on only what it is about. */
    private fun applyBaseline() {
        prefs.isGlobalPrivacyEnabled = true
        prefs.debugNotificationsEnabled = true
        prefs.lockDelaySeconds = 0
        prefs.unlockDelaySeconds = 0
        prefs.setExemptApps(emptySet())
        PrivacyFeature.values().forEach { feature ->
            prefs.setFeatureDisableOnLock(feature, false)
            prefs.setFeatureEnableOnUnlock(feature, false)
            prefs.setFeatureOnlyIfUnused(feature, false)
            prefs.setFeatureOnlyIfNotEnabled(feature, false)
            prefs.setFeatureOnlyIfNotManual(feature, false)
            prefs.setFeatureEnabledByApp(feature, false)
        }
    }

    /** Stands in for every fact doWork() reads from outside itself. */
    private class FakeWorker(
        context: Context,
        params: WorkerParameters
    ) : PrivacyActionWorker(context, params) {

        val states = mutableMapOf<PrivacyFeature, FeatureState>()
        val enabled = mutableListOf<PrivacyFeature>()
        val disabled = mutableListOf<PrivacyFeature>()
        var privileged = true
        var screenLocked = true
        var hotspot = false
        var featureInUse = false
        var exemptApp: String? = null
        var enableSucceeds = true
        var disableSucceeds = true
        var beforeEnable: (Set<PrivacyFeature>) -> Unit = {}
        var beforeDisable: (Set<PrivacyFeature>) -> Unit = {}

        override suspend fun confirmPrivilege(): Boolean = privileged

        override suspend fun getCurrentStatus(): Map<PrivacyFeature, FeatureState> = states.toMap()

        override suspend fun enableFeatures(features: Set<PrivacyFeature>): List<PrivacyResult> {
            beforeEnable(features)
            enabled += features
            return features.map { PrivacyResult(it, enableSucceeds) }
        }

        override suspend fun disableFeatures(features: Set<PrivacyFeature>): List<PrivacyResult> {
            beforeDisable(features)
            disabled += features
            return features.map { PrivacyResult(it, disableSucceeds) }
        }

        override suspend fun isFeatureInUse(feature: PrivacyFeature): Boolean = featureInUse

        override suspend fun isHotspotActive(): Boolean = hotspot

        override fun getFirstForegroundApp(exemptApps: Set<String>): String? = exemptApp

        override fun isScreenCurrentlyLocked(): Boolean = screenLocked
    }

    private fun worker(isLocking: Boolean, isDeviceLocked: Boolean = false): FakeWorker =
        TestListenableWorkerBuilder<PrivacyActionWorker>(context)
            .setInputData(
                workDataOf(
                    PrivacyActionWork.KEY_IS_LOCKING to isLocking,
                    PrivacyActionWork.KEY_IS_DEVICE_LOCKED to isDeviceLocked,
                    PrivacyActionWork.KEY_TRIGGER to "test",
                    PrivacyActionWork.KEY_REASON to "test"
                )
            )
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = FakeWorker(appContext, workerParameters)
            })
            .build() as FakeWorker

    private fun recordLock() {
        PrivacyActionWorker.lastLockAtMillis = ++clock
    }

    private fun recordUnlock() {
        PrivacyActionWorker.lastUnlockAtMillis = ++clock
    }

    /**
     * Every notification doWork() decided to send. NOT proof the user saw one: on a real device
     * POST_NOTIFICATIONS can be denied and the post is dropped without a word.
     *
     * Read from the notifier's own "sent" line rather than from NotificationManager, because
     * DebugNotificationHelper is a process singleton that caches whichever manager it first saw,
     * so the shadow of the CURRENT one can read empty whether or not anything fired.
     */
    private fun notifications(): List<String> =
        ShadowLog.getLogs()
            .mapNotNull { it.msg }
            .filter { it.startsWith("Debug notification sent: ") }
            .map { it.removePrefix("Debug notification sent: ") }

    /**
     * A negative assertion about notifications is worthless on its own: an empty list satisfies
     * "does not contain X" for reasons that have nothing to do with the code under test.
     */
    private fun notificationsSeen(): List<String> {
        val all = notifications()
        assertTrue("no notification was captured at all, so nothing may be concluded", all.isNotEmpty())
        return all
    }

    @Test
    fun `a protection mode that is on and owned by this app stays owned through a lock`() {
        // THE HEADLINE RULE, at the call site that applies it. Disowning here is what used to make
        // a failed unlock-disable permanent: the mode stayed on, the app blamed the user, and
        // every later unlock skipped it.
        //
        // Both halves run, because "it said nothing" is only evidence next to a case where it
        // does speak. The unowned half proves the message is reachable at all.
        prefs.setFeatureDisableOnLock(PrivacyFeature.AIRPLANE_MODE, true)
        prefs.setFeatureEnabledByApp(PrivacyFeature.AIRPLANE_MODE, true)
        recordLock()

        val owned = worker(isLocking = true)
        owned.states[PrivacyFeature.AIRPLANE_MODE] = FeatureState.ENABLED
        val ownedResult = runBlocking { owned.doWork() }

        assertEquals(ListenableWorker.Result.success(), ownedResult)
        assertTrue(
            "a mode this app failed to turn off is still this app's",
            prefs.getFeatureEnabledByApp(PrivacyFeature.AIRPLANE_MODE)
        )
        assertTrue(
            "and the user must not be told it was their own doing, was: ${notifications()}",
            notifications().none { it.contains("already enabled") }
        )
        assertTrue("nor may it be switched again", owned.enabled.isEmpty())

        // Same state, but this app never owned it. Now the message MUST appear.
        ShadowLog.clear()
        prefs.setFeatureEnabledByApp(PrivacyFeature.AIRPLANE_MODE, false)
        val unowned = worker(isLocking = true)
        unowned.states[PrivacyFeature.AIRPLANE_MODE] = FeatureState.ENABLED
        runBlocking { unowned.doWork() }

        assertTrue(
            "a mode this app never owned is the user's, and it must say so, was: ${notifications()}",
            notificationsSeen().any { it.contains("already enabled") }
        )
        assertFalse(
            "and it must not quietly claim it",
            prefs.getFeatureEnabledByApp(PrivacyFeature.AIRPLANE_MODE)
        )
    }

    @Test
    fun `a mode this app cannot turn back on stops being this app's`() {
        // The other half of the rule. The mode reads OFF, so nothing this app turned on is still
        // on, and the enable that follows fails - so nothing re-claims it either.
        prefs.setFeatureDisableOnLock(PrivacyFeature.AIRPLANE_MODE, true)
        prefs.setFeatureEnabledByApp(PrivacyFeature.AIRPLANE_MODE, true)
        recordLock()

        val worker = worker(isLocking = true)
        worker.states[PrivacyFeature.AIRPLANE_MODE] = FeatureState.DISABLED
        worker.enableSucceeds = false

        runBlocking { worker.doWork() }

        assertFalse(
            "ownership cannot outlive the state it describes",
            prefs.getFeatureEnabledByApp(PrivacyFeature.AIRPLANE_MODE)
        )
    }

    @Test
    fun `a re-lock during the unlock's enable step leaves protection modes alone`() {
        // THE MIRROR GUARD. Enabling WiFi takes a real shell round trip. A lock landing during it
        // must stop this stale unlock from switching a radio kill-switch OFF on a phone that is
        // locked right now.
        prefs.setFeatureEnableOnUnlock(PrivacyFeature.WIFI, true)
        prefs.setFeatureEnableOnUnlock(PrivacyFeature.AIRPLANE_MODE, true)
        prefs.setFeatureEnabledByApp(PrivacyFeature.AIRPLANE_MODE, true)
        recordUnlock()

        val worker = worker(isLocking = false)
        worker.beforeEnable = { recordLock() }

        runBlocking { worker.doWork() }

        assertTrue("WiFi was enabled, so the gap really opened", worker.enabled.contains(PrivacyFeature.WIFI))
        assertFalse(
            "Airplane Mode must not be turned off on a locked phone",
            worker.disabled.contains(PrivacyFeature.AIRPLANE_MODE)
        )
        assertTrue(
            "and the user must be told why, was: ${notifications()}",
            notifications().any { it.contains("Locked during the unlock action") }
        )
    }

    @Test
    fun `a sensor disable that fails is reported once, not twice`() {
        // processResults already reports the failure. The second notification called the same
        // event "skipped", with a cause nothing had determined, and it was attempted, not skipped.
        prefs.setFeatureDisableOnLock(PrivacyFeature.CAMERA, true)
        recordLock()

        val worker = worker(isLocking = true, isDeviceLocked = false)
        worker.states[PrivacyFeature.CAMERA] = FeatureState.ENABLED
        worker.disableSucceeds = false

        runBlocking { worker.doWork() }

        // The WHOLE run's messages, not the ones naming the camera. A duplicate worded without
        // the feature name is exactly the thing this guards against, and filtering would hide it.
        val said = notificationsSeen()
        assertEquals("one failed disable must produce one message, was: $said", 1, said.size)
        assertTrue(
            "and it must be the failure, not a skip, was: $said",
            said.single().contains("Failed to disable: Camera")
        )
        assertFalse(
            "nothing may call an attempted command skipped, was: $said",
            said.single().contains("Skipped")
        )
    }

    @Test
    fun `sensors are not attempted when the trigger says the device was already locked`() {
        // The other branch of the same block. The command cannot work with the keyguard up, so it
        // is skipped rather than spent, and this is the one case where "skipped" is the true word.
        prefs.setFeatureDisableOnLock(PrivacyFeature.CAMERA, true)
        recordLock()

        val worker = worker(isLocking = true, isDeviceLocked = true)
        worker.states[PrivacyFeature.CAMERA] = FeatureState.ENABLED

        runBlocking { worker.doWork() }

        assertTrue("no shell round trip may be spent", worker.disabled.isEmpty())
        val said = notificationsSeen()
        assertEquals("exactly one message, was: $said", 1, said.size)
        assertTrue("and it must say why, was: $said", said.single().contains("device already locked"))
    }

    @Test
    fun `an exempt app in the foreground stops a lock touching anything`() {
        prefs.setFeatureDisableOnLock(PrivacyFeature.WIFI, true)
        prefs.setFeatureDisableOnLock(PrivacyFeature.AIRPLANE_MODE, true)
        prefs.setExemptApps(setOf("com.example.maps"))
        recordLock()

        val worker = worker(isLocking = true)
        worker.exemptApp = "com.example.maps"

        runBlocking { worker.doWork() }

        assertTrue("nothing may be switched off", worker.disabled.isEmpty())
        assertTrue("nor switched on", worker.enabled.isEmpty())
        assertTrue(
            "and the user must be told which app, was: ${notifications()}",
            notificationsSeen().any { it.contains("com.example.maps") }
        )
    }

    @Test
    fun `an exempt app must not stop an unlock switching things back on`() {
        // The settings screen promises only that an exempt app stops PrivacyFlip DISABLING. The
        // app a user unlocks into is usually the exempt one, so blocking here left the radios and
        // the sensors off with nothing saying why.
        prefs.setFeatureEnableOnUnlock(PrivacyFeature.WIFI, true)
        prefs.setFeatureEnableOnUnlock(PrivacyFeature.BLUETOOTH, true)
        prefs.setExemptApps(setOf("com.example.maps"))
        recordUnlock()

        val worker = worker(isLocking = false)
        worker.exemptApp = "com.example.maps"

        runBlocking { worker.doWork() }

        assertTrue("WiFi must come back", worker.enabled.contains(PrivacyFeature.WIFI))
        assertTrue("and Bluetooth with it", worker.enabled.contains(PrivacyFeature.BLUETOOTH))
    }

    @Test
    fun `a mode whose state could not be read is never claimed, so a later unlock leaves it alone`() {
        // An enable reports success when its own read-back is unreadable. Claiming on that would
        // hand the app a mode the USER had already switched on, and switch it off at unlock.
        val mode = PrivacyFeature.AIRPLANE_MODE
        prefs.setFeatureDisableOnLock(mode, true)
        prefs.setFeatureEnableOnUnlock(mode, true)
        prefs.setFeatureOnlyIfNotManual(mode, true)

        recordLock()
        val lock = worker(isLocking = true)
        lock.states[mode] = FeatureState.ERROR
        runBlocking { lock.doWork() }

        assertTrue("it must still try, since nothing said the mode was on", lock.enabled.contains(mode))
        assertFalse(
            "but an unreadable state is not evidence this app turned it on",
            prefs.getFeatureEnabledByApp(mode)
        )

        // The half that matters to the user: their own Airplane Mode survives the next unlock.
        ShadowLog.clear()
        recordUnlock()
        val unlock = worker(isLocking = false)
        runBlocking { unlock.doWork() }

        assertTrue("a setting this app never claimed is not its to undo", unlock.disabled.isEmpty())
        assertTrue(
            "and it must say so, was: ${notifications()}",
            notificationsSeen().any { it.contains("manually set") }
        )
    }

    @Test
    fun `Only if unused keeps a feature that is in use, and the switch off ignores it`() {
        // The user-facing "Only if unused" switch. With it OFF the feature goes down regardless,
        // which is what proves the ON case is the switch working and not the fake refusing.
        prefs.setFeatureDisableOnLock(PrivacyFeature.WIFI, true)
        prefs.setFeatureOnlyIfUnused(PrivacyFeature.WIFI, true)
        recordLock()

        val guarded = worker(isLocking = true)
        guarded.featureInUse = true
        runBlocking { guarded.doWork() }

        assertTrue("WiFi is in use, so it must be left alone", guarded.disabled.isEmpty())
        assertTrue(
            "and the user must be told why, was: ${notifications()}",
            notificationsSeen().any { it.contains("in use/connected") }
        )

        ShadowLog.clear()
        prefs.setFeatureOnlyIfUnused(PrivacyFeature.WIFI, false)
        val unguarded = worker(isLocking = true)
        unguarded.featureInUse = true
        runBlocking { unguarded.doWork() }

        assertTrue(
            "with the switch off, being in use must not save it",
            unguarded.disabled.contains(PrivacyFeature.WIFI)
        )
    }

    @Test
    fun `an active hotspot keeps WiFi and Mobile Data on through a lock`() {
        prefs.setFeatureDisableOnLock(PrivacyFeature.WIFI, true)
        prefs.setFeatureDisableOnLock(PrivacyFeature.MOBILE_DATA, true)
        recordLock()

        val worker = worker(isLocking = true)
        worker.hotspot = true

        runBlocking { worker.doWork() }

        assertTrue(
            "cutting either leaves the hotspot broadcasting with nothing to share",
            worker.disabled.isEmpty()
        )
        assertTrue(
            "and the user must be told why, was: ${notifications()}",
            notificationsSeen().any { it.contains("hotspot is active") }
        )
    }

    @Test
    fun `Airplane Mode this app failed to turn off is still turned off at the next unlock`() {
        // THE WHOLE DEFECT, end to end, over four real doWork() runs. Nothing but the stored
        // preferences carries state between them, which is what the user's phone does too.
        val mode = PrivacyFeature.AIRPLANE_MODE
        prefs.setFeatureDisableOnLock(mode, true)
        prefs.setFeatureEnableOnUnlock(mode, true)
        prefs.setFeatureOnlyIfNotManual(mode, true)

        // 1. Lock. Airplane Mode is off, so this app turns it on and owns it.
        recordLock()
        val lock1 = worker(isLocking = true)
        lock1.states[mode] = FeatureState.DISABLED
        runBlocking { lock1.doWork() }
        assertTrue("this app turned it on", lock1.enabled.contains(mode))
        assertTrue("so this app owns it", prefs.getFeatureEnabledByApp(mode))

        // 2. Unlock, and the disable FAILS. Airplane Mode is still on and still this app's.
        recordUnlock()
        val unlock1 = worker(isLocking = false)
        unlock1.disableSucceeds = false
        runBlocking { unlock1.doWork() }
        assertTrue("a failed disable must not hand ownership away", prefs.getFeatureEnabledByApp(mode))

        // 3. Lock again. Airplane Mode reads ON, and who turned it on is not knowable from here.
        recordLock()
        val lock2 = worker(isLocking = true)
        lock2.states[mode] = FeatureState.ENABLED
        runBlocking { lock2.doWork() }
        assertTrue("seeing it on must not make it the user's", prefs.getFeatureEnabledByApp(mode))

        // 4. Unlock again. This is the run that was unreachable before: the app must still try.
        recordUnlock()
        val unlock2 = worker(isLocking = false)
        runBlocking { unlock2.doWork() }
        assertTrue(
            "Airplane Mode must be turned off, not skipped as manually set",
            unlock2.disabled.contains(mode)
        )
        assertFalse("and it is nobody's now", prefs.getFeatureEnabledByApp(mode))
    }

    @Test
    fun `an unlock during the lock's disable step stops protection modes being switched on`() {
        // The lock-side twin of the mirror guard. Disabling WiFi takes a real shell round trip,
        // and putting a phone into Airplane Mode after the user picked it up is the harm.
        prefs.setFeatureDisableOnLock(PrivacyFeature.WIFI, true)
        prefs.setFeatureDisableOnLock(PrivacyFeature.AIRPLANE_MODE, true)
        recordLock()

        val worker = worker(isLocking = true)
        worker.states[PrivacyFeature.AIRPLANE_MODE] = FeatureState.DISABLED
        worker.beforeDisable = { recordUnlock() }

        runBlocking { worker.doWork() }

        assertTrue("WiFi was disabled, so the gap really opened", worker.disabled.contains(PrivacyFeature.WIFI))
        assertFalse(
            "Airplane Mode must not be switched on after the user picked the phone up",
            worker.enabled.contains(PrivacyFeature.AIRPLANE_MODE)
        )
        assertTrue(
            "and the user must be told why, was: ${notifications()}",
            notifications().any { it.contains("Unlocked during the lock action") }
        )
    }

    @Test
    fun `a lock already overtaken by an unlock touches nothing at either stage`() {
        // Covers this direction's 2 remaining checkpoints at once: the sensor stage and the
        // regular-features stage each refuse, and each says so in its own words.
        prefs.setFeatureDisableOnLock(PrivacyFeature.CAMERA, true)
        prefs.setFeatureDisableOnLock(PrivacyFeature.WIFI, true)
        recordLock()
        recordUnlock()

        val worker = worker(isLocking = true)

        val result = runBlocking { worker.doWork() }

        assertEquals(
            "a superseded run must END, not ask WorkManager to run it again",
            ListenableWorker.Result.success(),
            result
        )
        assertTrue("a stale lock may switch nothing off", worker.disabled.isEmpty())
        val said = notifications()
        assertTrue("the sensor stage must refuse, was: $said", said.any { it.contains("unlocked since this lock cycle started") })
        assertTrue("and so must the regular stage, was: $said", said.any { it.contains("Unlocked during lock cycle") })
    }

    @Test
    fun `an unlock already overtaken by a lock touches nothing at either stage`() {
        // The mirror of the test above, for the other direction's 2 checkpoints.
        prefs.setFeatureEnableOnUnlock(PrivacyFeature.CAMERA, true)
        prefs.setFeatureEnableOnUnlock(PrivacyFeature.WIFI, true)
        recordUnlock()
        recordLock()

        val worker = worker(isLocking = false)

        val result = runBlocking { worker.doWork() }

        assertEquals(
            "a superseded run must END, not ask WorkManager to run it again",
            ListenableWorker.Result.success(),
            result
        )
        assertTrue("a stale unlock may switch nothing on", worker.enabled.isEmpty())
        val said = notifications()
        assertTrue("the sensor stage must refuse, was: $said", said.any { it.contains("locked since this unlock cycle started") })
        assertTrue("and so must the regular stage, was: $said", said.any { it.contains("Locked during unlock cycle") })
    }

    @Test
    fun `without confirmed privilege nothing is touched`() {
        prefs.setFeatureDisableOnLock(PrivacyFeature.WIFI, true)
        prefs.setFeatureDisableOnLock(PrivacyFeature.AIRPLANE_MODE, true)
        recordLock()

        val worker = worker(isLocking = true)
        worker.privileged = false

        val result = runBlocking { worker.doWork() }

        assertEquals(
            "an unconfirmed privilege is a failure, never a quiet success",
            ListenableWorker.Result.failure(),
            result
        )
        assertTrue("nothing may be switched on a guess", worker.enabled.isEmpty())
        assertTrue("nor switched off", worker.disabled.isEmpty())
        assertTrue(
            "and the user must be told, was: ${notifications()}",
            notifications().any { it.contains("permission not granted") }
        )
    }
}
