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

    // Runs each command in order like the real executor: the first that exits 0 wins.
    private class ChainFakeNfc(
        context: Context,
        private val svcWorks: Boolean = false,
        private val dumpsysReadable: Boolean = false,
        startOn: Boolean = true
    ) : NFCToggle(RootManager.getInstance(Unit), context) {
        var radioOn = startOn
        val globalSettings = mutableMapOf<String, String>()

        private fun run(command: String): CommandResult {
            return when {
                command.startsWith("svc nfc") && !svcWorks -> CommandResult.failure("svc: no nfc here")
                command == "svc nfc disable" -> { radioOn = false; CommandResult.success() }
                command == "svc nfc enable" -> { radioOn = true; CommandResult.success() }
                command.startsWith("settings put global ") -> {
                    val (key, value) = command.removePrefix("settings put global ").split(" ")
                    globalSettings[key] = value
                    CommandResult.success()
                }
                command.startsWith("settings get global ") ->
                    CommandResult.success(listOf(globalSettings[command.removePrefix("settings get global ")] ?: "null"))
                command.startsWith("dumpsys nfc") && dumpsysReadable ->
                    CommandResult.success(listOf(if (radioOn) "mState=on" else "mState=off"))
                command.startsWith("dumpsys nfc") -> CommandResult.failure("grep found no mState line")
                else -> CommandResult.failure("unknown command")
            }
        }

        override suspend fun runCommands(commands: List<String>): CommandResult {
            var last = CommandResult.failure("no commands")
            for (command in commands) {
                last = run(command)
                if (last.success) return last
            }
            return last
        }

        fun parse(output: String) = parseStatusOutput(output)
    }

    @Test
    fun `a disable the NFC service never received is not reported as done`() = runBlocking {
        val toggle = ChainFakeNfc(context)

        val result = toggle.disable()

        assertTrue("the radio never changed", toggle.radioOn)
        assertFalse("so the disable must not be reported as a success, was: ${result.message}", result.success)
    }

    @Test
    fun `an enable the NFC service never received is not reported as done`() = runBlocking {
        val toggle = ChainFakeNfc(context)

        val result = toggle.enable()

        assertFalse("nothing reached the NFC service, was: ${result.message}", result.success)
    }

    @Test
    fun `a disable that reaches the NFC service is reported as done`() = runBlocking {
        val toggle = ChainFakeNfc(context, svcWorks = true, dumpsysReadable = true)

        val result = toggle.disable()

        assertFalse("the working path must still switch the radio", toggle.radioOn)
        assertTrue(result.success)
    }

    @Test
    fun `an enable that reaches the NFC service is reported as done`() = runBlocking {
        val toggle = ChainFakeNfc(context, svcWorks = true, dumpsysReadable = true, startOn = false)

        val result = toggle.enable()

        assertTrue("the working path must still switch the radio on", toggle.radioOn)
        assertTrue(result.success)
    }

    @Test
    fun `an enable that still reads off is not reported as done`() = runBlocking {
        val toggle = FakeNfc(context, statusOutputs = listOf("mState=off"))

        val result = toggle.enable()

        assertFalse("NFC reads off, was: ${result.message}", result.success)
    }

    @Test
    fun `a failed disable with NFC still on keeps the real error and does not only blame an app`() = runBlocking {
        val toggle = ChainFakeNfc(context, svcWorks = false, dumpsysReadable = true)

        val result = toggle.disable()

        assertFalse(result.success)
        assertTrue("the attempt's own error must be kept, was: ${result.message}", result.message?.contains("unknown command") == true)
        assertTrue("and the shell must be named as a possible cause, was: ${result.message}", result.message?.contains("privileged shell") == true)
    }

    @Test
    fun `NFC turning back on after the disable is not reported as disabled`() = runBlocking {
        val toggle = FakeNfc(context, statusOutputs = listOf("mState=off", "mState=turning on"))

        val result = toggle.disable()

        assertFalse("NFC is coming back on, was: ${result.message}", result.success)
    }

    @Test
    fun `an enable seen turning on is confirmed, not reported as unreadable`() = runBlocking {
        val toggle = FakeNfc(context, statusOutputs = listOf("mState=turning on"))

        val result = toggle.enable()

        assertTrue(result.success)
        assertFalse("the state was read, was: ${result.message}", result.message?.contains("could not be read back") == true)
    }

    @Test
    fun `a bare digit is not read as an NFC state`() {
        val toggle = ChainFakeNfc(context)

        assertEquals(io.github.dorumrr.privacyflip.data.FeatureState.UNKNOWN, toggle.parse("1"))
        assertEquals(io.github.dorumrr.privacyflip.data.FeatureState.UNKNOWN, toggle.parse("0"))
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

    // Android 15+ as a Samsung S25+ on Android 16 behaved through Shizuku: svc nfc is refused or
    // silently ignored, "cmd nfc disable" does not exist, and disable-nfc/enable-nfc do the switch.
    private class Android15FakeNfc(
        context: Context,
        private val svcSilentlyIgnored: Boolean = false,
        startOn: Boolean = true
    ) : NFCToggle(RootManager.getInstance(Unit), context) {
        var radioOn = startOn
        var savedOn = startOn
        val ran = mutableListOf<String>()

        private fun run(command: String): CommandResult = when {
            command.startsWith("dumpsys nfc") -> CommandResult.success(listOf(if (radioOn) "mState=on" else "mState=off"))
            command.startsWith("svc nfc") ->
                if (svcSilentlyIgnored) CommandResult.success() else CommandResult.failure("SecurityException", 1)
            command == "cmd nfc disable-nfc '[persist]'" -> { radioOn = false; savedOn = false; CommandResult.success() }
            command == "cmd nfc disable-nfc" -> { radioOn = false; CommandResult.success() }
            command == "cmd nfc enable-nfc" -> { radioOn = true; savedOn = true; CommandResult.success() }
            else -> CommandResult.failure(
                "java.lang.SecurityException: Uid 2000 does not have access to ${command.removePrefix("cmd nfc ")} nfc command (or such command doesn't exist)",
                255
            )
        }

        override suspend fun runCommands(commands: List<String>): CommandResult {
            var last = CommandResult.failure("no commands")
            for (command in commands) {
                ran += command
                last = run(command)
                if (last.success) return last
            }
            return last
        }
    }

    @Test
    @Config(sdk = [35])
    fun `on Android 15 and newer a disable refused by svc still switches NFC off and keeps it off`() = runBlocking {
        val toggle = Android15FakeNfc(context)

        val result = toggle.disable()

        assertFalse("NFC must really be off, was: ${result.message}", toggle.radioOn)
        assertFalse("and stay off after a reboot, as svc nfc disable left it", toggle.savedOn)
        assertTrue(result.success)
    }

    @Test
    @Config(sdk = [35])
    fun `on Android 15 and newer a svc that exits 0 and does nothing does not stop the disable`() = runBlocking {
        val toggle = Android15FakeNfc(context, svcSilentlyIgnored = true)

        val result = toggle.disable()

        assertFalse("NFC must really be off, was: ${result.message} after ${toggle.ran}", toggle.radioOn)
        assertTrue(result.success)
    }

    @Test
    @Config(sdk = [35])
    fun `on Android 15 and newer an enable switches NFC on`() = runBlocking {
        val toggle = Android15FakeNfc(context, startOn = false)

        val result = toggle.enable()

        assertTrue("NFC must really be on, was: ${result.message}", toggle.radioOn)
        assertTrue(result.success)
    }

    @Test
    fun `a disable result always names the commands it tried`() = runBlocking {
        val stillOn = FakeNfc(context, statusOutputs = listOf("mState=on"))
        assertTrue("auto-retry off", stillOn.disable().commandUsed?.contains("svc nfc disable") == true)

        PreferenceManager.getInstance(context).samsungNfcAutoRetry = true
        val exhausted = FakeNfc(context, statusOutputs = listOf("mState=on"))
        assertTrue("retry exhausted", exhausted.disable().commandUsed?.contains("svc nfc disable") == true)

        val settledOff = FakeNfc(context, statusOutputs = listOf("mState=on", "mState=on", "mState=off"))
        PreferenceManager.getInstance(context).samsungNfcAutoRetry = false
        assertTrue("settled off", settledOff.disable().commandUsed?.contains("svc nfc disable") == true)
    }

    @Test
    @Config(sdk = [35])
    fun `on Android 15 and newer an svc enable that exits 0 and does nothing does not stop the enable`() = runBlocking {
        val toggle = Android15FakeNfc(context, svcSilentlyIgnored = true, startOn = false)

        val result = toggle.enable()

        assertTrue("NFC must really be on, was: ${result.message} after ${toggle.ran}", toggle.radioOn)
        assertTrue(result.success)
    }

    @Test
    @Config(sdk = [34])
    fun `Android 14 and older keep svc first and the old cmd fallback`() = runBlocking {
        val toggle = Android15FakeNfc(context)

        toggle.disable()

        assertEquals(listOf("svc nfc disable", "cmd nfc disable"), toggle.ran.take(2))
    }
}
