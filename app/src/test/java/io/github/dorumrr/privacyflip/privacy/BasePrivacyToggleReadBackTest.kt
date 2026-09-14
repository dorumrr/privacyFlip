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
 * A privileged command can exit 0 and change nothing. Proven on a real phone: with the keyguard
 * up, `cmd sensor_privacy enable 0 camera` returns exit 0 and leaves the camera allowed. The exit
 * code alone therefore cannot decide what the user is told, or the app reports "Camera disabled"
 * while the camera is still live.
 *
 * Robolectric only because the class under test logs; nothing here needs a real Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BasePrivacyToggleReadBackTest {

    private class FakeToggle(
        private val commandSucceeds: Boolean = true,
        /** What the status command reports, one entry per read. The last entry repeats. */
        private val reportedStates: List<FeatureState>
    ) : BasePrivacyToggle(RootManager.getInstance(Unit)) {

        var actionCommandsRun = 0
        var statusReads = 0

        override val feature = PrivacyFeature.CAMERA
        override val featureName = "Camera"
        override val enableCommands = listOf(CommandSet(ACTION_ENABLE))
        override val disableCommands = listOf(CommandSet(ACTION_DISABLE))
        override val statusCommands = listOf(CommandSet(STATUS))

        override suspend fun runCommands(commands: List<String>): CommandResult {
            if (commands.first() == STATUS) {
                val state = reportedStates[minOf(statusReads, reportedStates.lastIndex)]
                statusReads++
                return CommandResult.success(listOf(state.name))
            }
            actionCommandsRun++
            return if (commandSucceeds) {
                CommandResult.success()
            } else {
                CommandResult.failure("the shell refused it")
            }
        }

        // The fake's status command emits the state's own name, so this needs no real parser.
        override fun parseStatusOutput(output: String): FeatureState =
            FeatureState.values().firstOrNull { output.contains(it.name.lowercase()) }
                ?: FeatureState.UNKNOWN

        private companion object {
            const val ACTION_ENABLE = "fake enable"
            const val ACTION_DISABLE = "fake disable"
            const val STATUS = "fake status"
        }
    }

    @Test
    fun `a command the system accepted and then ignored is reported as a failure`() = runBlocking {
        // Exactly the keyguard-up sensor case: exit 0, state unchanged.
        val toggle = FakeToggle(commandSucceeds = true, reportedStates = listOf(FeatureState.ENABLED))

        val result = toggle.disable()

        assertFalse(
            "the command was accepted but the camera is still on, so this is not a success",
            result.success
        )
        assertTrue(
            "and the message must say so rather than claim it was disabled, was: ${result.message}",
            result.message?.contains("not disabled") == true
        )
    }

    @Test
    fun `a command that really changed the state is still reported as a success`() = runBlocking {
        val toggle = FakeToggle(commandSucceeds = true, reportedStates = listOf(FeatureState.DISABLED))

        val result = toggle.disable()

        assertTrue("a genuine success must not be spoiled by the check", result.success)
        assertEquals("Camera disabled", result.message)
        assertEquals("one read is enough when it already matches", 1, toggle.statusReads)
    }

    @Test
    fun `a state that cannot be read is reported as done but unconfirmed, never as a failure`() = runBlocking {
        // The status command itself failed. Calling that a failure would swap one lie for another.
        val toggle = FakeToggle(commandSucceeds = true, reportedStates = listOf(FeatureState.UNKNOWN))

        val result = toggle.disable()

        assertTrue("an unreadable state is not evidence of failure", result.success)
        assertTrue(
            "but the user must not be told it is confirmed, was: ${result.message}",
            result.message?.contains("could not be read back") == true
        )
    }

    @Test
    fun `a state that settles on the second read is not called a failure`() = runBlocking {
        val toggle = FakeToggle(
            commandSucceeds = true,
            reportedStates = listOf(FeatureState.ENABLED, FeatureState.DISABLED)
        )

        val result = toggle.disable()

        assertTrue("the state did arrive, just not on the first read", result.success)
        assertEquals("it must have looked twice", 2, toggle.statusReads)
    }

    @Test
    fun `a command that failed outright is not read back at all`() = runBlocking {
        val toggle = FakeToggle(commandSucceeds = false, reportedStates = listOf(FeatureState.DISABLED))

        val result = toggle.disable()

        assertFalse(result.success)
        assertEquals("a failed command needs no confirming read", 0, toggle.statusReads)
        assertEquals(1, toggle.actionCommandsRun)
    }
}
