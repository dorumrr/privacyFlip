package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * executeWithFallbacks() used to be copy-pasted, byte for byte, into all three executors
 * (Root, Shizuku, Dhizuku). It now lives once as a default method on this interface, and this
 * test covers that shared body.
 *
 * It does NOT cover every privileged command: ConnectionStateChecker calls
 * rootManager.executeCommand() directly for its WiFi, hotspot and location checks, and that
 * single-command path never enters executeWithFallbacks().
 *
 * The fake below implements only executeCommand(), which is the point: it inherits
 * executeWithFallbacks() from the interface, so what is tested here is the shared body itself.
 */
class PrivilegeExecutorFallbackTest {

    private class FakeExecutor(private val succeedOn: String? = null) : PrivilegeExecutor {
        val attempted = mutableListOf<String>()

        override suspend fun initialize(context: Context) = Unit
        override suspend fun isAvailable(): Boolean = true
        override suspend fun isPermissionGranted(): Boolean = true
        override suspend fun requestPermission(): Boolean = true
        override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.ROOT
        override fun cleanup() = Unit

        override suspend fun executeCommand(command: String): CommandResult {
            attempted.add(command)
            return if (command == succeedOn) {
                CommandResult.success(listOf("ok: $command"))
            } else {
                CommandResult.failure("failed: $command")
            }
        }
    }

    @Test
    fun `the first command that succeeds wins and the rest are never attempted`() = runBlocking {
        val executor = FakeExecutor(succeedOn = "second")

        val result = executor.executeWithFallbacks(listOf("first", "second", "third"))

        assertTrue("a succeeding command must be reported as success", result.success)
        assertEquals(listOf("ok: second"), result.output)
        assertEquals(
            "stops at the first success - 'third' must never run",
            listOf("first", "second"),
            executor.attempted
        )
    }

    @Test
    fun `when every command fails the LAST failure is returned, not the first`() = runBlocking {
        val executor = FakeExecutor(succeedOn = null)

        val result = executor.executeWithFallbacks(listOf("first", "second", "third"))

        assertFalse(result.success)
        assertEquals("the caller sees why the last attempt failed", "failed: third", result.error)
        assertEquals(listOf("first", "second", "third"), executor.attempted)
    }

    @Test
    fun `an empty command list fails instead of reporting a success it never had`() = runBlocking {
        val executor = FakeExecutor(succeedOn = null)

        val result = executor.executeWithFallbacks(emptyList())

        assertFalse("no commands run means no success to report", result.success)
        assertEquals("No commands provided", result.error)
        assertTrue("nothing should have been attempted", executor.attempted.isEmpty())
    }
}
