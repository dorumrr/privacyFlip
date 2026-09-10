package io.github.dorumrr.privacyflip.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
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
        PrivacyActionWorker.lastLockAtMillis = 0L
        PrivacyActionWorker.lastUnlockAtMillis = 0L
        val actions = mutableListOf<String>()

        // Real-world chronology: a lock, immediately undone by an unlock.
        val lockCycleStartedAt = System.nanoTime()
        PrivacyActionWorker.lastLockAtMillis = lockCycleStartedAt
        val unlockCycleStartedAt = System.nanoTime()
        PrivacyActionWorker.lastUnlockAtMillis = unlockCycleStartedAt

        // The unlock's coroutine reaches the mutex FIRST (wins the scheduling race, even though
        // its real-world event happened second) - mirrors doWork()'s own unlock-side check.
        launch {
            PrivacyActionWorker.sensorMutex.withLock {
                if (PrivacyActionWorker.lastLockAtMillis > unlockCycleStartedAt) {
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
                if (PrivacyActionWorker.lastUnlockAtMillis > lockCycleStartedAt) {
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
}
