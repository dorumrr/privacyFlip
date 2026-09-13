package io.github.dorumrr.privacyflip.privilege

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission dance shared by the Shizuku and Dhizuku executors, driven against a fake
 * backend. Neither real helper can be reached from a JVM test - both talk to a binder - so this
 * covers the part that CAN be proven here: the caching rules, the timeout, and handing the
 * user's answer back to whoever is waiting.
 *
 * What this cannot prove, and what a real phone with the helper installed still has to:
 * that each executor's own SDK calls and listener wiring still reach the real helper.
 */
class PrivilegePermissionGateTest {

    /** Stands in for Shizuku or Dhizuku, with no binder involved. */
    private class FakeBackend(
        var available: Boolean = true,
        var permission: Boolean = false,
        var preCheck: PermissionPreCheck = PermissionPreCheck.AskTheUser,
        val failOnStart: Boolean = false,
        /** Set to have the fake answer the moment the dialog is "shown". */
        val answerImmediatelyWith: Boolean? = null
    ) {
        var reads = 0
        var requestsStarted = 0
        var gate: PrivilegePermissionGate? = null

        fun buildGate(timeoutMs: Long = 200L): PrivilegePermissionGate {
            val built = PrivilegePermissionGate(
                tag = "FakeBackend",
                logger = { null },
                isBackendAvailable = { available },
                readPermissionFromBackend = { reads++; permission },
                preCheck = { preCheck },
                startRequest = {
                    requestsStarted++
                    if (failOnStart) throw IllegalStateException("backend refused to start the request")
                    answerImmediatelyWith?.let { gate?.deliverResult(it) }
                },
                timeoutMs = timeoutMs
            )
            gate = built
            return built
        }
    }

    @Test
    fun `an unreachable backend is never treated as granted`() = runBlocking {
        val backend = FakeBackend(available = false, permission = true)
        val gate = backend.buildGate()

        assertFalse("a backend we cannot reach must not report granted", gate.isGranted())
        assertEquals("the backend must not even be asked", 0, backend.reads)
    }

    @Test
    fun `a granted answer is cached, so the backend is asked once, not on every call`() = runBlocking {
        val backend = FakeBackend(permission = true)
        val gate = backend.buildGate()

        assertTrue(gate.isGranted())
        assertTrue(gate.isGranted())
        assertTrue(gate.isGranted())

        assertEquals("a known 'yes' should be answered from cache", 1, backend.reads)
    }

    @Test
    fun `a remembered no is thrown away, so a restarted helper is not refused forever`() = runBlocking {
        val backend = FakeBackend(permission = false)
        val gate = backend.buildGate()

        assertFalse(gate.isGranted())
        assertEquals(1, backend.reads)

        // The user has now granted permission in the helper, which the app cannot be told about.
        backend.permission = true

        assertTrue("a cached 'no' must be re-checked, not trusted", gate.isGranted())
        assertEquals("the backend must be asked again", 2, backend.reads)
    }

    @Test
    fun `the user's answer reaches the waiting request`() = runBlocking {
        val backend = FakeBackend(answerImmediatelyWith = true)
        val gate = backend.buildGate()

        assertTrue("the granted answer must come back to the caller", gate.request())
        assertEquals(1, backend.requestsStarted)
        assertEquals("and be remembered", true, gate.cachedAnswer)
    }

    @Test
    fun `a denial reaches the waiting request too`() = runBlocking {
        val backend = FakeBackend(answerImmediatelyWith = false)
        val gate = backend.buildGate()

        assertFalse(gate.request())
        assertEquals(false, gate.cachedAnswer)
    }

    @Test
    fun `no answer at all gives up instead of waiting forever`() = runBlocking {
        val backend = FakeBackend(answerImmediatelyWith = null) // the user never responds
        val gate = backend.buildGate(timeoutMs = 100L)

        val started = System.currentTimeMillis()
        assertFalse("a request nobody answers must not report granted", gate.request())
        val waited = System.currentTimeMillis() - started

        assertTrue("it must actually have waited", waited >= 100)
        assertTrue("but not forever", waited < 5_000)
    }

    @Test
    fun `already granted skips the dialog entirely`() = runBlocking {
        val backend = FakeBackend(preCheck = PermissionPreCheck.AlreadyGranted)
        val gate = backend.buildGate()

        assertTrue(gate.request())
        assertEquals("no dialog should have been shown", 0, backend.requestsStarted)
    }

    @Test
    fun `a backend that cannot ask says no without showing a dialog`() = runBlocking {
        // Shizuku's real cases: too old a version, or the user ticked don't-ask-again.
        val backend = FakeBackend(preCheck = PermissionPreCheck.CannotAsk)
        val gate = backend.buildGate()

        assertFalse(gate.request())
        assertEquals("no dialog should have been shown", 0, backend.requestsStarted)
        assertNull("and nothing should be remembered from a question never asked", gate.cachedAnswer)
    }

    @Test
    fun `a backend that throws while starting the request fails instead of hanging`() = runBlocking {
        val backend = FakeBackend(failOnStart = true)
        val gate = backend.buildGate(timeoutMs = 5_000L)

        val started = System.currentTimeMillis()
        assertFalse(gate.request())
        val waited = System.currentTimeMillis() - started

        assertTrue("it must fail immediately, not sit out the timeout", waited < 1_000)
    }

    @Test
    fun `an answer arriving with nothing waiting updates the cache instead of throwing`() = runBlocking {
        val backend = FakeBackend(answerImmediatelyWith = true)
        val gate = backend.buildGate()

        assertTrue(gate.request())

        // Shizuku's listener is permanent, so an answer can arrive when no request is in flight.
        gate.deliverResult(false)

        assertEquals("the later answer still updates what we know", false, gate.cachedAnswer)

        // NOT proven here: that the waiting slot is cleared BEFORE it is resumed. That ordering
        // only matters when two answers land at once from different threads, which no
        // deterministic test in this suite can stage - deliberately breaking the order leaves
        // every test in this file green. The ordering is kept by construction and by comment in
        // deliverResult(); treat it as read, not as tested.
    }
}
