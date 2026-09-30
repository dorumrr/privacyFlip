package io.github.dorumrr.privacyflip.privacy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.data.CommandSet
import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.privilege.CommandResult
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.ConnectionStateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A lock job cancelled by an unlock must stop, not carry on and report every feature it did not
 * reach as "failed". Only a cancellation that reaches the worker ends the run as cancelled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CancellationPropagationTest {

    private class CancelledShellToggle : BasePrivacyToggle(RootManager.getInstance(Unit)) {
        var commandsRun = 0
        override val feature = PrivacyFeature.WIFI
        override val featureName = "WiFi"
        override val enableCommands = listOf(CommandSet("fake enable"))
        override val disableCommands = listOf(CommandSet("fake disable"))
        override val statusCommands = listOf(CommandSet("fake status"))

        override suspend fun runFeatureAction(enable: Boolean): CommandResult? = null

        override suspend fun runCommands(commands: List<String>): CommandResult {
            commandsRun++
            throw CancellationException("Job was cancelled")
        }

        override fun parseStatusOutput(output: String): FeatureState = FeatureState.UNKNOWN
    }

    private fun returnedInsteadOfCancelling(block: suspend () -> Unit): Boolean {
        var returned = false
        try {
            runBlocking {
                block()
                returned = true
            }
        } catch (expected: CancellationException) {
        }
        return returned
    }

    @Test
    fun `a switch cancelled mid-command is not turned into a failed result`() {
        val toggle = CancelledShellToggle()
        assertFalse(
            "disable() must pass the cancellation on, not report WiFi as failed",
            returnedInsteadOfCancelling { toggle.disable() }
        )
    }

    @Test
    fun `a status read cancelled mid-command is not turned into an error state`() {
        val toggle = CancelledShellToggle()
        assertFalse(
            "getCurrentState() must pass the cancellation on, not report ERROR",
            returnedInsteadOfCancelling { toggle.getCurrentState() }
        )
    }

    @Test
    fun `a cancelled hotspot probe does not answer not active`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val checker = ConnectionStateChecker(context, RootManager.getInstance(Unit))
        assertFalse(
            "the probe must pass the cancellation on, not answer false and let the lock go on",
            returnedInsteadOfCancelling {
                kotlin.coroutines.coroutineContext.job.cancel()
                checker.isHotspotActive()
            }
        )
    }

    @Test
    fun `a cancelled in-use probe does not answer not in use`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val checker = ConnectionStateChecker(context, RootManager.getInstance(Unit))
        listOf(PrivacyFeature.WIFI, PrivacyFeature.LOCATION).forEach { feature ->
            assertFalse(
                "${feature.displayName}: the probe must pass the cancellation on",
                returnedInsteadOfCancelling {
                    kotlin.coroutines.coroutineContext.job.cancel()
                    checker.isFeatureInUse(feature)
                }
            )
        }
    }
}
