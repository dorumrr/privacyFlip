package io.github.dorumrr.privacyflip.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Android's own shell commands often print why they failed on stdout (the NFC service prints its
 * exception there and exits -1), so a failure read from stderr alone arrives with no reason.
 */
class CommandResultTest {

    @Test
    fun `a failed command that explains itself on stdout keeps that explanation`() {
        val result = CommandResult.fromProcess(
            exitCode = 255,
            outputLines = listOf("Exception while executing nfc shell command disable-nfc: ", "java.lang.SecurityException: denied"),
            errorLines = emptyList()
        )

        assertEquals(
            "Exception while executing nfc shell command disable-nfc: \njava.lang.SecurityException: denied",
            result.error
        )
    }

    @Test
    fun `stderr wins over stdout when a failed command has both`() {
        val result = CommandResult.fromProcess(1, listOf("partial output"), listOf("the real reason"))

        assertEquals("the real reason", result.error)
    }

    @Test
    fun `a command that worked has no error, whatever it printed on stderr`() {
        assertNull(CommandResult.fromProcess(0, listOf("ok"), listOf("a warning")).error)
    }

    @Test
    fun `libsu's merged stream list is reported once, not twice`() {
        val merged = listOf("line one", "line two")

        assertEquals("line one\nline two", CommandResult.fromProcess(1, merged, merged).error)
    }

    @Test
    fun `a failure that never ran a command has no exit code`() {
        assertNull(CommandResult.failure("Shizuku permission not granted").exitCode)
    }
}
