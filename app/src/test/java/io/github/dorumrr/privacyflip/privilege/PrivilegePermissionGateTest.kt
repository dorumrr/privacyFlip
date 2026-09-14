package io.github.dorumrr.privacyflip.privilege

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
        var preCheckWhenNotGranted: PermissionPreCheck = PermissionPreCheck.AskTheUser,
        val failOnStart: Boolean = false,
        /** Set to have the fake answer the moment the dialog is "shown". */
        val answerImmediatelyWith: Boolean? = null
    ) {
        var reads = 0
        var requestsStarted = 0
        var gate: PrivilegePermissionGate? = null

        /** Test-controlled clock, so an aged grant does not need real time to age. */
        var now = 1_000L

        fun buildGate(timeoutMs: Long = 200L, grantValidForMs: Long = 5_000L): PrivilegePermissionGate {
            val built = PrivilegePermissionGate(
                tag = "FakeBackend",
                logger = { null },
                isBackendAvailable = { available },
                readPermissionFromBackend = { reads++; permission },
                // A real helper reports "already granted" once it has granted, which is what
                // makes a second request cheap rather than a second dialog.
                preCheck = {
                    if (permission) PermissionPreCheck.AlreadyGranted else preCheckWhenNotGranted
                },
                startRequest = {
                    requestsStarted++
                    if (failOnStart) throw IllegalStateException("backend refused to start the request")
                    answerImmediatelyWith?.let {
                        permission = it
                        gate?.deliverResult(it)
                    }
                },
                timeoutMs = timeoutMs,
                grantValidForMs = grantValidForMs,
                nowMillis = { now }
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
        val backend = FakeBackend(permission = true)
        val gate = backend.buildGate()

        assertTrue(gate.request())
        assertEquals("no dialog should have been shown", 0, backend.requestsStarted)
    }

    @Test
    fun `a backend that cannot ask says no without showing a dialog`() = runBlocking {
        // Shizuku's real cases: too old a version, or the user ticked don't-ask-again.
        val backend = FakeBackend(preCheckWhenNotGranted = PermissionPreCheck.CannotAsk)
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

        // NOT proven here: two answers landing at once from genuinely different threads. No
        // deterministic test in this suite can stage that. The slot is an AtomicReference taken
        // with getAndSet, so only one caller can ever win it; treat the thread race as read.
    }

    @Test
    fun `a grant withdrawn in the backend is noticed once the remembered yes ages out`() = runBlocking {
        val backend = FakeBackend(permission = true)
        val gate = backend.buildGate(grantValidForMs = 5_000L)

        assertTrue(gate.isGranted())
        assertEquals("the first call asks the backend", 1, backend.reads)

        // Still fresh: answered from memory, the backend is left alone.
        backend.now += 4_000L
        assertTrue(gate.isGranted())
        assertEquals(1, backend.reads)

        // The user revokes authorisation in the helper's own app. The binder stays alive, so
        // nothing tells this app - only the re-read can.
        backend.permission = false
        backend.now += 2_000L

        assertFalse("a withdrawn grant must not keep being reported as granted", gate.isGranted())
        assertEquals("the backend must have been asked again", 2, backend.reads)
    }

    @Test
    fun `a backend that dies while the user is deciding fails the request at once`() = runBlocking {
        // The fake never answers on its own, so the request is genuinely parked when the binder
        // dies, which is the only moment forget() can strand it.
        val backend = FakeBackend(answerImmediatelyWith = null)
        val gate = backend.buildGate(timeoutMs = 3_000L)

        val request = async { gate.request() }
        yield()
        assertEquals("the request must be parked before the backend dies", 1, backend.requestsStarted)

        val started = System.currentTimeMillis()
        gate.forget()
        val granted = request.await()
        val waited = System.currentTimeMillis() - started

        assertFalse("a backend that died cannot have granted anything", granted)
        assertTrue(
            "it must answer the moment the backend dies, not sit out the timeout (waited ${waited}ms)",
            waited < 1_000
        )
    }

    @Test
    fun `an answer arriving after the backend died does not resume the request twice`() = runBlocking {
        val backend = FakeBackend(answerImmediatelyWith = null)
        val gate = backend.buildGate(timeoutMs = 3_000L)

        val request = async { gate.request() }
        yield()

        val started = System.currentTimeMillis()
        gate.forget()
        // Shizuku's listener is permanent, so the user's answer can still land after the binder
        // died. Resuming the same continuation a second time throws, which would surface here.
        gate.deliverResult(true)

        val granted = request.await()
        val waited = System.currentTimeMillis() - started

        assertFalse("the death already answered this request, and that answer stands", granted)
        assertTrue("and it must not have waited out the timeout (waited ${waited}ms)", waited < 1_000)
    }

    @Test
    fun `a second request while one is still waiting does not steal its answer`() = runBlocking {
        // The fake never answers on its own, so the first request is genuinely parked when the
        // second arrives - which is the only moment the single waiting slot can be stolen.
        val backend = FakeBackend(answerImmediatelyWith = null)
        val gate = backend.buildGate(timeoutMs = 300L)

        val first = async { gate.request() }
        yield()
        assertEquals("the first request must be waiting by now", 1, backend.requestsStarted)

        val second = async { gate.request() }
        yield()
        assertEquals(
            "the second must wait its turn, not open a second dialog over the first",
            1,
            backend.requestsStarted
        )

        // The user answers the dialog the FIRST request opened.
        backend.permission = true
        gate.deliverResult(true)

        assertTrue("the request that was waiting must get the answer", first.await())
        assertTrue("and the one behind it must not be stranded", second.await())
    }
}
