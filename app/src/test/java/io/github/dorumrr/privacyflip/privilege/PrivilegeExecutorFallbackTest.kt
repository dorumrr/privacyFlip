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

    private class FakeExecutor(
        private val succeedOn: String? = null,
        /** A real backend THROWS on a dead binder; it does not return a tidy failure. */
        private val throwOn: String? = null,
        /** Most privileged commands print nothing at all when they work. */
        private val succeedsSilently: Boolean = false
    ) : PrivilegeExecutor {
        val attempted = mutableListOf<String>()

        override suspend fun initialize(context: Context) = Unit
        override suspend fun isAvailable(): Boolean = true
        override suspend fun isPermissionGranted(): Boolean = true
        override suspend fun requestPermission(): Boolean = true
        override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.ROOT
        override fun cleanup() = Unit

        override suspend fun executeCommand(command: String): CommandResult {
            attempted.add(command)
            if (command == throwOn) throw IllegalStateException("binder died on: $command")
            return if (command == succeedOn) {
                if (succeedsSilently) CommandResult.success() else CommandResult.success(listOf("ok: $command"))
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
    fun `when every command fails, every command's reason reaches the caller`() = runBlocking {
        val executor = FakeExecutor()

        val result = executor.executeWithFallbacks(listOf("first", "second", "third"))

        assertFalse(result.success)
        listOf("first", "second", "third").forEach {
            assertTrue("reason of $it missing, was: ${result.error}", result.error?.contains("failed: $it") == true)
        }
        assertEquals(listOf("first", "second", "third"), executor.attempted)
    }

    private class ScriptedExecutor(private val results: Map<String, CommandResult>) : PrivilegeExecutor {
        override suspend fun initialize(context: Context) = Unit
        override suspend fun isAvailable(): Boolean = true
        override suspend fun isPermissionGranted(): Boolean = true
        override suspend fun requestPermission(): Boolean = true
        override fun getPrivilegeMethod(): PrivilegeMethod = PrivilegeMethod.SHIZUKU
        override fun cleanup() = Unit
        override suspend fun executeCommand(command: String): CommandResult = results.getValue(command)
    }

    @Test
    fun `each failed command is named with its exit code and the first lines of its reason`() = runBlocking {
        val stackTrace = listOf("Exception while executing nfc shell command disable-nfc: ", "java.lang.SecurityException: denied") +
            (1..40).map { "\tat com.android.nfc.Frame$it(Frame.java:$it)" }
        val executor = ScriptedExecutor(
            mapOf(
                "cmd nfc disable-nfc" to CommandResult.fromProcess(255, stackTrace, emptyList()),
                "svc nfc disable" to CommandResult.fromProcess(1, emptyList(), emptyList())
            )
        )

        val error = executor.executeWithFallbacks(listOf("cmd nfc disable-nfc", "svc nfc disable")).error.orEmpty()

        assertTrue(error, error.contains("cmd nfc disable-nfc (exit 255)"))
        assertTrue(error, error.contains("SecurityException: denied"))
        assertTrue(error, error.contains("svc nfc disable (exit 1): no output"))
        assertTrue("a stack trace must not flood the log, was ${error.length} chars", error.length < 600)
    }

    @Test
    fun `each reason keeps its first 3 lines and at most 200 characters`() = runBlocking {
        val executor = ScriptedExecutor(
            mapOf(
                "four" to CommandResult.fromProcess(2, listOf("l1", "l2", "l3", "l4"), emptyList()),
                "long" to CommandResult.fromProcess(3, listOf("x".repeat(500)), emptyList())
            )
        )

        val error = executor.executeWithFallbacks(listOf("four", "long")).error.orEmpty()

        assertTrue(error, error.startsWith("four (exit 2): l1 / l2 / l3; "))
        assertFalse(error, error.contains("l4"))
        assertEquals("long (exit 3): " + "x".repeat(200), error.substringAfter("; "))
    }

    @Test
    fun `in a mixed list only the command that really ran carries an exit code`() = runBlocking {
        val executor = ScriptedExecutor(
            mapOf(
                "never ran" to CommandResult.failure("Shizuku service not available"),
                "ran" to CommandResult.fromProcess(2, emptyList(), listOf("bad argument"))
            )
        )

        val error = executor.executeWithFallbacks(listOf("never ran", "ran")).error

        assertEquals("never ran: Shizuku service not available; ran (exit 2): bad argument", error)
    }

    @Test
    fun `a failure that never ran a command shows no exit code`() = runBlocking {
        val executor = ScriptedExecutor(mapOf("svc wifi disable" to CommandResult.failure("Shizuku permission not granted")))

        val error = executor.executeWithFallbacks(listOf("svc wifi disable")).error.orEmpty()

        assertEquals("svc wifi disable: Shizuku permission not granted", error)
    }

    @Test
    fun `a command that succeeds with NO output still ends the chain`() = runBlocking {
        // Most privileged commands print nothing when they work. A body that read empty output as
        // failure would re-issue privileged commands after one had already succeeded, and then
        // report a failure for a toggle that actually worked.
        val executor = FakeExecutor(succeedOn = "first", succeedsSilently = true)

        val result = executor.executeWithFallbacks(listOf("first", "second"))

        assertTrue("no output is not a failure", result.success)
        assertTrue("and its output really is empty", result.output.isEmpty())
        assertEquals("the second command must never run", listOf("first"), executor.attempted)
    }

    @Test
    fun `a command that throws does not abort the chain - the next fallback still gets its turn`() = runBlocking {
        val executor = FakeExecutor(succeedOn = "second", throwOn = "first")

        val result = executor.executeWithFallbacks(listOf("first", "second"))

        assertTrue("the fallback would have worked and must be tried", result.success)
        assertEquals(listOf("ok: second"), result.output)
        assertEquals(listOf("first", "second"), executor.attempted)
    }

    @Test
    fun `a thrown command with no fallback left is reported, not allowed to escape`() = runBlocking {
        val executor = FakeExecutor(succeedOn = null, throwOn = "only")

        val result = executor.executeWithFallbacks(listOf("only"))

        assertFalse(result.success)
        assertTrue(
            "the reason must survive into the result, was: ${result.error}",
            result.error?.contains("binder died") == true
        )
        assertFalse("a thrown command produced no exit code, was: ${result.error}", result.error.orEmpty().contains("(exit"))
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
