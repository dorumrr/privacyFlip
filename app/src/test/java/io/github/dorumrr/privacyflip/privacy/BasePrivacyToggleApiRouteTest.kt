package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.CommandSet
import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.privilege.CommandResult
import io.github.dorumrr.privacyflip.root.RootManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A backend that is not a shell can answer through its own API instead. Two ways that route can
 * defeat the read-back verification, which is the whole reason this app stopped trusting exit
 * codes:
 *
 *  - answering with a non-answer (UNKNOWN, ERROR, UNAVAILABLE) rather than null skips the status
 *    commands entirely, and an unreadable state is reported to the user as a success;
 *  - answering a WRITE with a failure rather than null stops the shell commands ever running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BasePrivacyToggleApiRouteTest {

    private class Toggle(
        private val apiWrite: CommandResult?,
        private val apiRead: FeatureState?,
        private val shellState: String,
        private val shellReadWorks: Boolean = true
    ) : BasePrivacyToggle(RootManager.getInstance(Unit)) {

        var shellWrites = 0
        var shellReads = 0

        override val feature = PrivacyFeature.NFC
        override val featureName = "NFC"
        override val enableCommands = listOf(CommandSet("svc nfc enable"))
        override val disableCommands = listOf(CommandSet("svc nfc disable"))
        override val statusCommands = listOf(CommandSet("dumpsys nfc"))

        override suspend fun runFeatureAction(enable: Boolean): CommandResult? = apiWrite
        override suspend fun readFeatureState(): FeatureState? = apiRead

        override suspend fun runCommands(commands: List<String>): CommandResult {
            if (commands.first().startsWith("dumpsys")) {
                shellReads++
                return if (shellReadWorks) CommandResult.success(listOf(shellState))
                else CommandResult.failure("Dhizuku denies this command")
            }
            shellWrites++
            return CommandResult.success()
        }

        override fun parseStatusOutput(output: String): FeatureState = when {
            output.contains("absent") -> FeatureState.UNAVAILABLE
            output.contains("off") -> FeatureState.DISABLED
            output.contains("on") -> FeatureState.ENABLED
            else -> FeatureState.UNKNOWN
        }
    }

    @Test
    fun `a non-answer from the API read must not stand in for a status read`() {
        // THE DEFECT. UNAVAILABLE is not a state, it is the absence of one. Taken as an answer it
        // skips the shell read, and the read-back then calls the change unconfirmed and SUCCESSFUL
        // for hardware that may not even be there.
        listOf(FeatureState.UNAVAILABLE, FeatureState.UNKNOWN, FeatureState.ERROR).forEach { answer ->
            val toggle = Toggle(apiWrite = null, apiRead = answer, shellState = "mState=off")

            val result = runBlocking { toggle.disable() }

            assertTrue("$answer must not stop the shell being read", toggle.shellReads > 0)
            assertTrue("and the real state must win, was: ${result.message}", result.success)
            assertEquals("NFC disabled", result.message)
        }
    }

    @Test
    fun `a definite answer from the API read is used, and the shell is left alone`() {
        // The other half: this is what proves the test above is about non-answers, not about the
        // route being ignored entirely.
        val toggle = Toggle(apiWrite = null, apiRead = FeatureState.DISABLED, shellState = "mState=on")

        val result = runBlocking { toggle.disable() }

        assertEquals("a definite answer needs no shell read", 0, toggle.shellReads)
        assertTrue(result.success)
    }

    @Test
    fun `a backend whose shell cannot read says so, and never claims the state`() {
        // The case a backend like Dhizuku really produces: no API answer AND no usable shell. The
        // honest outcome is to say the state could not be read, never to report a state.
        val toggle = Toggle(apiWrite = null, apiRead = null, shellState = "", shellReadWorks = false)

        val result = runBlocking { toggle.disable() }

        assertTrue("it must have tried the shell", toggle.shellReads > 0)
        assertTrue(
            "and must admit it could not verify, was: ${result.message}",
            result.message?.contains("could not be read back") == true
        )
    }

    @Test
    fun `a feature that is not on this device is a failure, not an unconfirmed success`() {
        // UNAVAILABLE is a definite "not here". Reporting it as an unconfirmed success told the
        // user a radio had been disabled when the hardware does not exist.
        val toggle = Toggle(apiWrite = null, apiRead = null, shellState = "mState=absent")

        val result = runBlocking { toggle.disable() }

        assertFalse("nothing was disabled, so this is not a success", result.success)
        assertTrue(
            "and it must say the feature is not there, was: ${result.message}",
            result.message?.contains("not available on this device") == true
        )
    }

    @Test
    fun `a change made through the backend API never names shell commands that did not run`() {
        val toggle = Toggle(apiWrite = CommandResult.success(), apiRead = FeatureState.DISABLED, shellState = "")

        val result = runBlocking { toggle.disable() }

        assertEquals("no shell command ran", 0, toggle.shellWrites)
        assertEquals("so none may be reported as attempted", null, result.commandUsed)
    }

    @Test
    fun `a write route that gave no answer must fall back to the shell commands`() {
        val toggle = Toggle(apiWrite = null, apiRead = null, shellState = "mState=off")

        runBlocking { toggle.disable() }

        assertEquals("the shell must have been used", 1, toggle.shellWrites)
    }

    @Test
    fun `a write route that really failed does NOT fall back, and says so`() {
        // A backend that answered "I tried and it failed" must not have its answer overwritten by
        // a shell command that cannot work there either. Only a null means "no answer".
        val toggle = Toggle(
            apiWrite = CommandResult.failure("Dhizuku cannot switch this"),
            apiRead = null,
            shellState = "mState=off"
        )

        val result = runBlocking { toggle.disable() }

        assertEquals("a real failure is not a fallback trigger", 0, toggle.shellWrites)
        assertFalse(result.success)
        assertTrue(
            "and the reason must reach the user, was: ${result.message}",
            result.message?.contains("Dhizuku cannot switch this") == true
        )
    }
}
