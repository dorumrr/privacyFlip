package io.github.dorumrr.privacyflip.privilege

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicInteger

/**
 * Privilege detection builds a Shizuku executor to test it and discards it when Shizuku is not
 * answering, taking that executor's listeners with it. Nothing was then left to notice Shizuku
 * coming back, so protection stayed off until the next screen event or the 15-minute health check.
 *
 * This watcher owns that one job for the life of the process, so it is armed from every executor
 * build and must register exactly once however often that happens, including from several threads
 * at the same moment: detection can run from the worker and from the UI together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ShizukuRecoveryWatcherTest {

    private lateinit var context: Application
    private val realRegistrar = ShizukuRecoveryWatcher.registrar

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShizukuRecoveryWatcher.resetForTest()
    }

    @After
    fun tearDown() {
        ShizukuRecoveryWatcher.registrar = realRegistrar
        ShizukuRecoveryWatcher.resetForTest()
    }

    @Test
    fun `arming it repeatedly still registers exactly one listener`() {
        val registrations = AtomicInteger()
        ShizukuRecoveryWatcher.registrar = { registrations.incrementAndGet() }

        repeat(5) { ShizukuRecoveryWatcher.ensureWatching(context) }

        assertEquals("one listener, however many executors ask for it", 1, registrations.get())
    }

    @Test
    fun `sixteen threads arming at once still register exactly one listener`() {
        // Detection can run from the lock worker and from the UI at the same moment, so this is
        // the real arrangement, not a hypothetical one.
        val registrations = AtomicInteger()
        ShizukuRecoveryWatcher.registrar = { registrations.incrementAndGet() }

        val threads = (1..16).map { Thread { ShizukuRecoveryWatcher.ensureWatching(context) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("exactly one winner", 1, registrations.get())
    }

    @Test
    fun `a registration that throws leaves it disarmed, so a later attempt retries`() {
        // Shizuku's provider may not be ready the first time. Marking it armed anyway would mean
        // nothing ever watches, which is the failure this whole class exists to remove.
        val attempts = AtomicInteger()
        ShizukuRecoveryWatcher.registrar = {
            if (attempts.incrementAndGet() == 1) throw IllegalStateException("Shizuku not ready yet")
        }

        ShizukuRecoveryWatcher.ensureWatching(context)
        ShizukuRecoveryWatcher.ensureWatching(context)

        assertEquals("the second attempt must actually be made", 2, attempts.get())
    }

    @Test
    fun `once it succeeds it does not register again`() {
        val registrations = AtomicInteger()
        var failFirst = true
        ShizukuRecoveryWatcher.registrar = {
            if (failFirst) { failFirst = false; throw IllegalStateException("not ready") }
            registrations.incrementAndGet()
        }

        ShizukuRecoveryWatcher.ensureWatching(context)
        ShizukuRecoveryWatcher.ensureWatching(context)
        ShizukuRecoveryWatcher.ensureWatching(context)

        assertEquals("the retry succeeded, and nothing after it registered again", 1, registrations.get())
    }

    @Test
    fun `the listener it registers is the one it holds`() {
        // Guards against registering a fresh lambda each time, which would make removal or
        // identity comparison impossible if it is ever needed.
        val seen = mutableListOf<Shizuku.OnBinderReceivedListener>()
        ShizukuRecoveryWatcher.registrar = { seen.add(it) }

        ShizukuRecoveryWatcher.ensureWatching(context)
        ShizukuRecoveryWatcher.resetForTest()
        ShizukuRecoveryWatcher.ensureWatching(context)

        assertEquals(2, seen.size)
        assertEquals("the same listener instance both times", seen[0], seen[1])
    }
}
