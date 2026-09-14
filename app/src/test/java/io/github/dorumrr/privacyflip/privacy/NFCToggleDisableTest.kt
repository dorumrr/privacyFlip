package io.github.dorumrr.privacyflip.privacy

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.privilege.CommandResult
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.PreferenceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Drives the REAL NFCToggle.disable(), not a decision function lifted out of it. The defect this
 * covers lived at the call site: the retry was gated on what the disable ATTEMPT reported, so once
 * the base class began reading the state back, an app fast enough to re-enable NFC made the
 * attempt report failure and switched the retry off in the one case it exists for. The log then
 * still said "NFC successfully disabled (it stayed off)".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NFCToggleDisableTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowLog.clear()
        // A stored preference outlives a test, so the auto-retry switch is put back to its default
        // or one test silently changes what another is exercising.
        PreferenceManager.getInstance(context).samsungNfcAutoRetry = false
    }

    @After
    fun tearDown() {
        // PreferenceManager is a process singleton and Robolectric does not reset it, so without
        // this the switch set below leaks into every LATER TEST CLASS: anything that reads it
        // would then be exercising a path nobody chose, and its green would mean nothing.
        PreferenceManager.getInstance(context).samsungNfcAutoRetry = false
    }

    /** Stands in for the privileged shell. Status reads walk the list; the last entry repeats. */
    private class FakeNfc(
        context: Context,
        private val statusOutputs: List<String>,
        private val actionSucceeds: Boolean = true
    ) : NFCToggle(RootManager.getInstance(Unit), context) {

        var statusReads = 0
        var actionRuns = 0

        override suspend fun runCommands(commands: List<String>): CommandResult {
            if (commands.first().startsWith("dumpsys nfc")) {
                val out = statusOutputs[minOf(statusReads, statusOutputs.lastIndex)]
                statusReads++
                return CommandResult.success(listOf(out))
            }
            actionRuns++
            return if (actionSucceeds) CommandResult.success() else CommandResult.failure("shell said no")
        }
    }

    private fun loggedStayedOff(): Boolean =
        ShadowLog.getLogs().any { it.msg?.contains("stayed off") == true }

    @Test
    fun `NFC that reads as ON reaches the retry path even though the attempt reported failure`() = runBlocking {
        // The read-back inside the base class sees ENABLED, so the attempt reports FAILURE. Under
        // the old rule that switched the retry off entirely. Auto-retry is off by default here, so
        // reaching the retry path shows up as the message that offers to turn it on.
        val toggle = FakeNfc(context, statusOutputs = listOf("mState=on"))

        val result = toggle.disable()

        assertFalse("NFC is still on, so this is not a success", result.success)
        assertTrue(
            "it must have reached the override path, was: ${result.message}",
            result.message?.contains("NFC Auto-Retry") == true
        )
        assertFalse("and it must never claim NFC stayed off", loggedStayedOff())
    }

    @Test
    fun `when the retry runs out it names both causes instead of asserting one`() = runBlocking {
        // With auto-retry ON and NFC obstinately reading as on, the retry exhausts. Root revoked
        // mid-retry looks identical from here to a wallet app turning NFC back on, so sending the
        // user after their payment cards would be guessing.
        PreferenceManager.getInstance(context).samsungNfcAutoRetry = true
        val toggle = FakeNfc(context, statusOutputs = listOf("mState=on"))

        val result = toggle.disable()

        assertFalse(result.success)
        val message = result.message ?: ""
        assertTrue("it must mention the wallet possibility, was: $message", message.contains("wallet app"))
        assertTrue("and the privilege possibility, was: $message", message.contains("privileged shell"))
        assertTrue("and report what the last attempt actually said", message.contains("Last attempt reported"))
        assertTrue("the retry must really have run", toggle.actionRuns > 1)
    }

    @Test
    fun `NFC that reads as OFF is reported as disabled`() = runBlocking {
        val toggle = FakeNfc(context, statusOutputs = listOf("mState=off"))

        val result = toggle.disable()

        assertTrue(result.success)
        assertEquals("NFC disabled", result.message)
        assertTrue("this is the one case where 'stayed off' is true", loggedStayedOff())
    }

    @Test
    fun `a state that cannot be read is never reported as having stayed off`() = runBlocking {
        // dumpsys answered with something this parser does not recognise, so nothing is known.
        // Claiming it stayed off would be telling the user something nobody observed.
        val toggle = FakeNfc(context, statusOutputs = listOf("mState=somethingelse"))

        val result = toggle.disable()

        assertFalse("nothing observed NFC to be off", loggedStayedOff())
        assertTrue(
            "and the result must say it could not be confirmed, was: ${result.message}",
            result.message?.contains("could not be read back") == true
        )
    }

    @Test
    fun `NFC that settles off only after the base class gave up is still reported as disabled`() = runBlocking {
        // The base class reads twice in 150ms and sees it still on, so it reports failure. The
        // later read, 500ms on, sees it off. The fresher read is the true one.
        val toggle = FakeNfc(
            context,
            statusOutputs = listOf("mState=on", "mState=on", "mState=off")
        )

        val result = toggle.disable()

        assertTrue("the fresher read says off, so the stale failure must not stand", result.success)
        assertEquals("NFC disabled", result.message)
    }
}
