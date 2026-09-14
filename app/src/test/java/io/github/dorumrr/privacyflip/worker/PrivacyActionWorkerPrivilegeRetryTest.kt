package io.github.dorumrr.privacyflip.worker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privilege check runs a real command through the privileged shell, so it can answer "no" for
 * a reason that clears a moment later: a process WorkManager has only just started, a shell not
 * yet up, a busy su daemon.
 *
 * Believing that single "no" abandoned the entire lock with no retry anywhere in the worker, and
 * raised a notification saying permission was not granted when it was. Nothing was disabled, and
 * the reason given was untrue.
 */
class PrivacyActionWorkerPrivilegeRetryTest {

    /** Answers in order; the last answer repeats for every look after that. */
    private class FakeCheck(private val answers: List<Boolean>) {
        var calls = 0

        fun probe(): Boolean {
            val answer = answers[minOf(calls, answers.lastIndex)]
            calls++
            return answer
        }
    }

    @Test
    fun `a no that clears on the next look does not abandon the lock`() = runBlocking {
        val check = FakeCheck(listOf(false, true))

        val granted = PrivacyActionWorker.privilegeIsGranted(gapMs = 1L) { check.probe() }

        assertTrue("the second look said yes, so the lock must go ahead", granted)
        assertEquals("it must have looked more than once", 2, check.calls)
    }

    @Test
    fun `a real no is still a no once every look agrees`() = runBlocking {
        val check = FakeCheck(listOf(false))

        val granted = PrivacyActionWorker.privilegeIsGranted(gapMs = 1L) { check.probe() }

        assertFalse("permission genuinely is not granted, so this must stay false", granted)
        assertEquals("but it must not have given up after the first look", 3, check.calls)
    }

    @Test
    fun `a yes on the LAST look still counts`() = runBlocking {
        // Without this, an implementation that looks three times but only honours the first two
        // answers is green, and the user is refused on a lock where the last look said granted.
        val check = FakeCheck(listOf(false, false, true))

        val granted = PrivacyActionWorker.privilegeIsGranted(gapMs = 1L) { check.probe() }

        assertTrue("the final look said yes, and that must not be thrown away", granted)
        assertEquals(3, check.calls)
    }

    @Test
    fun `the gap between looks is real, not skipped`() = runBlocking {
        // The gap IS the fix: it gives a shell that was not ready a moment to become ready.
        // Without this, an implementation that ignores gapMs fires all three probes at a daemon
        // that has not started, and every look fails for the same reason as the first.
        val check = FakeCheck(listOf(false))

        val started = System.currentTimeMillis()
        PrivacyActionWorker.privilegeIsGranted(gapMs = 120L) { check.probe() }
        val waited = System.currentTimeMillis() - started

        assertEquals(3, check.calls)
        assertTrue("three looks means two gaps, so at least 240ms, waited ${waited}ms", waited >= 240)
    }

    @Test
    fun `a yes on the first look costs nothing extra`() = runBlocking {
        val check = FakeCheck(listOf(true))

        val granted = PrivacyActionWorker.privilegeIsGranted(gapMs = 1L) { check.probe() }

        assertTrue(granted)
        assertEquals("the normal path must not pay for the retry", 1, check.calls)
    }
}
