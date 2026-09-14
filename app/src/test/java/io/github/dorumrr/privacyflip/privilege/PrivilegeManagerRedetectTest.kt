package io.github.dorumrr.privacyflip.privilege

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * initialize() used to re-detect the privilege method on EVERY lock and EVERY unlock, building a
 * fresh executor each time and dropping the previous one without calling cleanup().
 *
 * ShizukuExecutor.cleanup() removes three binder listeners and cancels its scope, so every cycle
 * left another set registered. One Shizuku restart then fired all of them at once: a permission
 * request and a PrivacyMonitorService restart per leaked listener. Detection also built executors
 * for methods it then rejected and dropped those uncleaned too.
 *
 * Keeping a working executor is what removes the churn. It also makes it safe to clear the
 * executor when detection finds nothing, because detection now only runs once the one we had has
 * already stopped being available.
 */
class PrivilegeManagerRedetectTest {

    @Test
    fun `a working executor is kept, so no new one is built`() {
        assertFalse(
            "re-detecting while the current executor still works is what leaked one per lock cycle",
            PrivilegeManager.shouldRedetect(hasExecutor = true, currentStillAvailable = true)
        )
    }

    @Test
    fun `an executor that stopped being available is replaced`() {
        assertTrue(
            "root revoked, or the helper stopped: the app has to find that out",
            PrivilegeManager.shouldRedetect(hasExecutor = true, currentStillAvailable = false)
        )
    }

    @Test
    fun `with no executor at all, detection must run`() {
        assertTrue(
            "first run of the process, or after everything was torn down",
            PrivilegeManager.shouldRedetect(hasExecutor = false, currentStillAvailable = false)
        )
    }

    @Test
    fun `no executor beats a stale availability reading`() {
        // Guards the order of the two conditions: with nothing held, what the last reading said
        // cannot matter.
        assertTrue(
            PrivilegeManager.shouldRedetect(hasExecutor = false, currentStillAvailable = true)
        )
    }
}
