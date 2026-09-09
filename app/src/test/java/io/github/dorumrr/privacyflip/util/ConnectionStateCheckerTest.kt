package io.github.dorumrr.privacyflip.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for ConnectionStateChecker.parseLocationInUseOutput - the Kotlin-side half of the #20
 * location-in-use check. The other half (the awk command that produces this output from a real
 * `dumpsys appops` dump) only runs through a real shell and was verified live on a device, not
 * here; these tests pin down what this app does with what that command reports.
 */
class ConnectionStateCheckerTest {

    /** The classification formula this app used immediately before this fix: treat anything not
     * containing "NONE" as in use. Its awk command never produced a "NOBLOCKS" string, so this is
     * not a claim that formula once misread a real device's output - it is what would have
     * happened had that formula been paired with today's awk command. That is exactly why a
     * distinct marker was worth adding rather than reusing "NONE" for both cases: the two need
     * different handling in the log even though both mean "don't report in use". */
    private fun previousFormula(output: String): Boolean =
        output.isNotBlank() && !output.contains("NONE")

    @Test
    fun `NOBLOCKS is not the substring NONE, so the previous formula would have misread it as in use`() {
        assertEquals(true, previousFormula("NOBLOCKS"))
    }

    @Test
    fun `NOBLOCKS is treated as not in use, not as a false positive`() {
        assertEquals(false, ConnectionStateChecker.parseLocationInUseOutput("NOBLOCKS"))
    }

    @Test
    fun `NONE is treated as not in use`() {
        assertEquals(false, ConnectionStateChecker.parseLocationInUseOutput("NONE"))
    }

    @Test
    fun `a genuine Running start line is treated as in use`() {
        val output = "          Running start at: +5s"
        assertEquals(true, ConnectionStateChecker.parseLocationInUseOutput(output))
    }

    @Test
    fun `blank output is treated as not in use, never a guessed positive`() {
        assertEquals(false, ConnectionStateChecker.parseLocationInUseOutput(""))
    }
}
