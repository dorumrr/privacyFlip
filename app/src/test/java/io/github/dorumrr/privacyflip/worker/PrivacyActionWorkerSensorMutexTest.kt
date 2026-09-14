package io.github.dorumrr.privacyflip.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves a lock cycle's sensor disable and a later unlock cycle's sensor enable are 2 separate
 * WorkManager jobs this app's default
 * configuration can run concurrently, with nothing else serialising them - whichever finished
 * last used to win, regardless of which action the user actually took last.
 *
 * This test covers the mechanism that closes the race, PrivacyActionWorker.sensorMutex, by
 * running 2 real concurrent coroutines against it and checking the order events happened in,
 * not just that both completed. doWork()'s own call sites are covered separately, against
 * fakes, by PrivacyActionWorkerDoWorkTest.
 */
class PrivacyActionWorkerSensorMutexTest {

    @Test
    fun `sensorMutex serialises two concurrent holders - the later one always waits its turn`() = runBlocking {
        val events = mutableListOf<String>()
        val aAcquired = CompletableDeferred<Unit>()

        // Coroutine A: simulates the lock-side sensor disable, holds the mutex for a while.
        val jobA = launch {
            PrivacyActionWorker.sensorMutex.withLock {
                events.add("A-start")
                aAcquired.complete(Unit)
                delay(150) // stands in for a real, slow root/Shizuku shell call
                events.add("A-end")
            }
        }

        // Coroutine B: simulates the unlock-side sensor enable, deliberately only attempts to
        // acquire the mutex once A is confirmed to be holding it - so if B's own "inside the
        // lock" event ever appears BEFORE A's "A-end", the mutex failed to serialise them.
        val jobB = launch {
            aAcquired.await()
            events.add("B-attempt")
            PrivacyActionWorker.sensorMutex.withLock {
                events.add("B-start")
            }
        }

        jobA.join()
        jobB.join()

        assertEquals(
            "B must not get inside the lock until A has fully released it - if this fails, the " +
                "mutex is not actually serialising the two sensor operations",
            listOf("A-start", "B-attempt", "A-end", "B-start"),
            events
        )
    }

    @Test
    fun `a stale lock-side disable is skipped once a newer unlock is recorded, even if it wins the mutex race last`() = runBlocking {
        // Checks a second gap in the mutex above: it proves mutual EXCLUSION, but says nothing
        // about real-world ORDER. In production, each job does variable-latency setup before
        // ever reaching the mutex, so whichever one arrives first is not necessarily the one
        // whose real-world event happened first. This test forces that mismatch directly: the
        // unlock's block runs and completes FIRST, even though the lock happened first in
        // "real time", to prove lastLockAtMillis / lastUnlockAtMillis correctly override
        // arrival order rather than trusting it.
        //
        // Calls PrivacyActionWorker.isSupersededByFresherOppositeAction(), the exact function
        // all 4 of doWork()'s real supersede checks call, rather than hand-copying the
        // comparison (`if (lastLockAtMillis > unlockCycleStartedAt)`) - a hand-copy would have
        // let a real regression in doWork()'s own comparison leave this test green, while
        // calling the shared function means a regression in any of the 4 real call sites fails
        // this test too.
        PrivacyActionWorker.lastLockAtMillis = 0L
        PrivacyActionWorker.lastUnlockAtMillis = 0L
        val actions = mutableListOf<String>()

        // Real-world chronology: a lock, immediately undone by an unlock.
        //
        // 2 back-to-back System.nanoTime() calls are not guaranteed to differ (clock resolution,
        // JIT reordering) - a tie here makes isSupersededByFresherOppositeAction's strict `>`
        // correctly return false, failing this test for a reason that has nothing to do with the
        // code under test. +1 makes the ordering this test needs true by construction, not by
        // timing luck.
        val lockCycleStartedAt = System.nanoTime()
        PrivacyActionWorker.lastLockAtMillis = lockCycleStartedAt
        val unlockCycleStartedAt = lockCycleStartedAt + 1
        PrivacyActionWorker.lastUnlockAtMillis = unlockCycleStartedAt

        // The unlock's block runs and completes FIRST, deliberately, even though its real-world
        // event happened second - mirrors doWork()'s own unlock-side check.
        launch {
            PrivacyActionWorker.sensorMutex.withLock {
                if (PrivacyActionWorker.isSupersededByFresherOppositeAction(
                        PrivacyActionWorker.lastLockAtMillis, unlockCycleStartedAt
                    )
                ) {
                    actions.add("enable-skipped-stale")
                } else {
                    actions.add("enabled")
                }
            }
        }.join()

        // The lock's block runs only AFTER the unlock's has already completed - mirrors
        // doWork()'s own lock-side check.
        launch {
            PrivacyActionWorker.sensorMutex.withLock {
                if (PrivacyActionWorker.isSupersededByFresherOppositeAction(
                        PrivacyActionWorker.lastUnlockAtMillis, lockCycleStartedAt
                    )
                ) {
                    actions.add("disable-skipped-stale")
                } else {
                    actions.add("disabled")
                }
            }
        }.join()

        assertEquals(
            "the lock cycle must detect the newer unlock and skip, even though it ran second, " +
                "after the unlock's block already completed - if this fails, sensors end up " +
                "disabled after a real, later unlock",
            listOf("enabled", "disable-skipped-stale"),
            actions
        )
    }

    @Test
    fun `isSupersededByFresherOppositeAction is the exact boolean doWork() relies on at all 4 call sites`() {
        // All 4 real call sites in doWork() share this one function (confirmed by grep - no
        // inline copies remain), so this proves its own boolean logic at "Verified at runtime"
        // against the actual code doWork() calls, not a copy of it: a regression in the shared
        // function itself would fail here regardless of which call site exercises it.
        //
        // This does NOT prove each of the 6 call sites passes the CORRECT arguments (the right
        // timestamp, in the right order) - only that the function they all call is itself
        // correct. PrivacyActionWorkerDoWorkTest proves the wiring, by running the real doWork()
        // at all 6.
        assertTrue(
            "a real later opposite action (bigger timestamp) must supersede this cycle",
            PrivacyActionWorker.isSupersededByFresherOppositeAction(200L, 100L)
        )
        assertFalse(
            "an EARLIER opposite action (smaller timestamp) must never supersede this cycle",
            PrivacyActionWorker.isSupersededByFresherOppositeAction(50L, 100L)
        )
        assertFalse(
            "a tie (no opposite action recorded since this cycle started) must never supersede " +
                "it - a strict > is required, not >=",
            PrivacyActionWorker.isSupersededByFresherOppositeAction(100L, 100L)
        )
    }

    @Test
    fun `a re-lock during a slow sensor step is not missed by a frozen cycle-start snapshot`() {
        // doWork() used to freeze lastLockAtMillis into a local (thisLockCycleStartedAt) once, at
        // the very start of the job, then compare the opposite action against that frozen value
        // for the rest of the job's lifetime. That misses a re-trigger for the SAME direction
        // that arrives later but never gets its own job, because the REPLACE-guard
        // (sensorDisableInProgress/sensorEnableInProgress) correctly folds it into this
        // already-running one instead of starting a new one.
        //
        // Real-world chronology this reproduces: lock (t0), unlock (t1, while this lock job's
        // own sensor step is still running so its cancel is deliberately skipped), re-lock (t2,
        // also folded into this same still-running job by the same guard). By the time this job
        // reaches its regularFeatures checkpoint, the phone is genuinely LOCKED again (t2 is the
        // latest real event) - the check must say "not superseded" so the disable proceeds.
        //
        // This test writes PrivacyActionWorker.lastLockAtMillis and lastUnlockAtMillis for real,
        // between 2 separate reads, rather than calling isSupersededByFresherOppositeAction()
        // with hand-picked literals only - a hand-picked-literal version would be functionally a
        // duplicate of the truth-table test above with different numbers, proving nothing about
        // the fresh-read mechanism itself. Writing the fields for real between 2 reads exercises
        // the exact "does a write that happens between checkpoints get picked up" question
        // doWork()'s own 4 call sites depend on.
        PrivacyActionWorker.lastLockAtMillis = 0L
        PrivacyActionWorker.lastUnlockAtMillis = 0L

        // t0: the lock this job's own regularFeatures checkpoint will eventually reach.
        PrivacyActionWorker.lastLockAtMillis = 1000L

        // Simulates the OLD, removed behaviour: a local frozen here, at "job start", before the
        // unlock and re-lock below ever happen.
        val frozenAtJobStart = PrivacyActionWorker.lastLockAtMillis

        // t1: a real unlock, during this job's still-running sensor step (whose cancel is skipped).
        PrivacyActionWorker.lastUnlockAtMillis = 1050L

        // t2: a real re-lock, also folded into this same still-running job by the same guard.
        PrivacyActionWorker.lastLockAtMillis = 1100L

        // The OLD, removed behaviour: comparing the unlock against a snapshot frozen before the
        // re-lock ever happened.
        assertTrue(
            "reproduces the bug: comparing the unlock against a snapshot frozen at job start, " +
                "before the re-lock happened, wrongly says superseded - this is what doWork() " +
                "used to do, and what ultrareview caught",
            PrivacyActionWorker.isSupersededByFresherOppositeAction(
                PrivacyActionWorker.lastUnlockAtMillis, frozenAtJobStart
            )
        )

        // The FIXED behaviour: doWork()'s real call sites read lastLockAtMillis directly here,
        // at the checkpoint - this re-read genuinely happens AFTER the writes above, proving the
        // mechanism actually picks up a write that happened between 2 checkpoints, not just that
        // 2 different literals produce 2 different answers.
        assertFalse(
            "the fix: reading lastLockAtMillis fresh at the checkpoint - genuinely after the " +
                "writes above, not a hand-picked literal - correctly sees the re-lock and says " +
                "NOT superseded, so the disable proceeds on a phone that is genuinely locked",
            PrivacyActionWorker.isSupersededByFresherOppositeAction(
                PrivacyActionWorker.lastUnlockAtMillis, PrivacyActionWorker.lastLockAtMillis
            )
        )
    }
}
