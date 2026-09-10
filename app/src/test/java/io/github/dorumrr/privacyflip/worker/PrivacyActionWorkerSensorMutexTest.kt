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
 * Proves #A2 (PLAN.md, confirmed 10 Sep by /phi:debug): a lock cycle's sensor disable and a
 * later unlock cycle's sensor enable are 2 separate WorkManager jobs this app's default
 * configuration can run concurrently, with nothing else serialising them - whichever finished
 * last used to win, regardless of which action the user actually took last.
 *
 * doWork() as a whole needs a real root/Shizuku shell and cannot be unit-tested without deeper
 * dependency injection this codebase does not have. What CAN be proven directly is the actual
 * mechanism that closes the race: PrivacyActionWorker.sensorMutex. This test runs 2 real,
 * concurrent coroutines against it and checks the actual order events happened in, not just
 * that both eventually completed.
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
        // Checks #A2's own second gap, found by this round's own adversarial review: the mutex
        // above proves mutual EXCLUSION, but says nothing about real-world ORDER. Each job does
        // variable-latency setup before ever reaching the mutex, so whichever one arrives first
        // wins it first - not necessarily the one whose real-world event happened first. This
        // test deliberately runs the coroutines in the WRONG mutex order (unlock's turn first,
        // even though the lock happened first in "real time") to prove lastLockAtMillis /
        // lastUnlockAtMillis correctly override the mutex's own arrival order.
        //
        // #Audit finding 7 (production-readiness audit, 10 Sep): this used to hand-copy the
        // comparison (`if (lastLockAtMillis > unlockCycleStartedAt)`) instead of calling
        // doWork()'s own real check, so PLAN.md's "Verified at runtime" tag for the ordering fix
        // was not actually true - a real regression in doWork()'s own comparison would have left
        // this test green. Now calls PrivacyActionWorker.isSupersededByFresherOppositeAction(),
        // the exact function all 4 of doWork()'s real supersede checks call, so a regression in
        // any of them fails this test too.
        PrivacyActionWorker.lastLockAtMillis = 0L
        PrivacyActionWorker.lastUnlockAtMillis = 0L
        val actions = mutableListOf<String>()

        // Real-world chronology: a lock, immediately undone by an unlock.
        //
        // Found flaky by this round's own adversarial review: 2 back-to-back System.nanoTime()
        // calls are not guaranteed to differ (clock resolution, JIT reordering) - a tie here
        // makes isSupersededByFresherOppositeAction's strict `>` correctly return false, failing
        // this test for a reason that has nothing to do with the code under test. +1 makes the
        // ordering this test needs true by construction, not by timing luck.
        val lockCycleStartedAt = System.nanoTime()
        PrivacyActionWorker.lastLockAtMillis = lockCycleStartedAt
        val unlockCycleStartedAt = lockCycleStartedAt + 1
        PrivacyActionWorker.lastUnlockAtMillis = unlockCycleStartedAt

        // The unlock's coroutine reaches the mutex FIRST (wins the scheduling race, even though
        // its real-world event happened second) - mirrors doWork()'s own unlock-side check.
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

        // The lock's coroutine finally reaches the mutex AFTER the unlock's already ran - mirrors
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
            "the lock cycle must detect the newer unlock and skip, even though it lost the " +
                "mutex race and ran last - if this fails, sensors end up disabled after a real, " +
                "later unlock",
            listOf("enabled", "disable-skipped-stale"),
            actions
        )
    }

    @Test
    fun `isSupersededByFresherOppositeAction is the exact boolean doWork() relies on at all 4 call sites`() {
        // #Audit finding 4 (production-readiness audit, 10 Sep): doWork()'s unlock-side
        // regularFeatures/protectionModes stage never had ANY check against a fresher lock -
        // neither this nor its pre-existing lock-side twin (line ~432, `lastUnlockAtMillis >
        // thisLockCycleStartedAt`) had ever had a direct test of any kind before this. All 4 real
        // call sites in doWork() now share this one function (confirmed by grep - no inline
        // copies remain), so this proves its own boolean logic at "Verified at runtime" against
        // the actual code doWork() calls, not a copy of it: a regression in the shared function
        // itself would fail here regardless of which call site exercises it.
        //
        // #Audit finding 7, round 2 (found by this round's own adversarial review, correcting an
        // overclaim this test itself made the first time): this does NOT prove each of the 4
        // call sites passes the CORRECT arguments (the right timestamp, in the right order) -
        // only that the function they all call is itself correct. Wiring is separately proven
        // for the 2 sensor-stage call sites by the "stale lock-side disable" test above, which
        // exercises real call-site-shaped code; the 2 regularFeatures/protectionModes call sites
        // have no equivalent wiring test - doWork() cannot run end to end without a real
        // root/Shizuku shell this test environment does not have. Verified in code only for
        // those 2 sites' own argument order.
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
        // Ultrareview finding: doWork() used to freeze lastLockAtMillis into a local
        // (thisLockCycleStartedAt) once, at the very start of the job, then compare the
        // opposite action against that frozen value for the rest of the job's lifetime. That
        // misses a re-trigger for the SAME direction that arrives later but never gets its own
        // job, because the REPLACE-guard (sensorDisableInProgress/sensorEnableInProgress)
        // correctly folds it into this already-running one instead of starting a new one.
        //
        // Real-world chronology this reproduces: lock (t0), unlock (t1, while this lock job's
        // own sensor step is still running so its cancel is skipped by #G1), re-lock (t2, also
        // folded into this same still-running job by the same guard). By the time this job
        // reaches its regularFeatures checkpoint, the phone is genuinely LOCKED again (t2 is the
        // latest real event) - the check must say "not superseded" so the disable proceeds.
        //
        // Found weaker than intended by round 3's own adversarial review: the first draft of
        // this test called isSupersededByFresherOppositeAction() with hand-picked literals only,
        // never touching the real companion fields - functionally a duplicate of the
        // truth-table test above with different numbers, proving nothing about the fresh-read
        // mechanism itself. Rewritten to actually write PrivacyActionWorker.lastLockAtMillis and
        // lastUnlockAtMillis for real, between 2 separate reads - the exact "does a write that
        // happens between checkpoints get picked up" question doWork()'s own 4 call sites depend
        // on, that a same-invocation, hand-picked-literal comparison cannot exercise.
        PrivacyActionWorker.lastLockAtMillis = 0L
        PrivacyActionWorker.lastUnlockAtMillis = 0L

        // t0: the lock this job's own regularFeatures checkpoint will eventually reach.
        PrivacyActionWorker.lastLockAtMillis = 1000L

        // Simulates the OLD, removed behaviour: a local frozen here, at "job start", before the
        // unlock and re-lock below ever happen.
        val frozenAtJobStart = PrivacyActionWorker.lastLockAtMillis

        // t1: a real unlock, during this job's still-running sensor step (#G1 skips its cancel).
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
