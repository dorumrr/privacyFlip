package io.github.dorumrr.privacyflip.tile

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A quick-settings tile is created and destroyed by the system repeatedly. Its coroutine scope
 * was never cancelled, so work launched by a tap could still be running - and still touching
 * qsTile - after the system had torn that instance down.
 *
 * Cancelling belongs in onDestroy(), NOT in onStopListening(): that fires every time the
 * Quick Settings panel closes while the same instance lives on, and a cancelled scope stays
 * cancelled, which would leave the tile unable to ever update again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BaseTileServiceScopeTest {

    private class TestTileService : BaseTileService() {
        override val tag = "privacyFlip-TestTile"
        override val serviceName = "Test Tile"

        // serviceScope is protected, so only a subclass can report on it.
        val scopeIsActive: Boolean get() = serviceScope.isActive

        val reachedSuspensionPoint = CompletableDeferred<Unit>()
        val releaseSuspension = CompletableDeferred<Unit>()

        @Volatile
        var resumedAfterSuspension = false

        override suspend fun executeAction() {
            reachedSuspensionPoint.complete(Unit)
            releaseSuspension.await()
            resumedAfterSuspension = true
        }

        override suspend fun updateTileStateInternal() = Unit
    }

    @Test
    fun `work launched before onDestroy does not resume after it`() {
        val controller = Robolectric.buildService(TestTileService::class.java).create()
        val service = controller.get()

        service.onClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "the coroutine must actually be suspended before teardown, or this test proves nothing",
            service.reachedSuspensionPoint.isCompleted
        )

        destroyTolerantly(service)

        assertFalse("onDestroy() must leave the scope cancelled", service.scopeIsActive)

        // What the suspended coroutine was waiting on now arrives. On a cancelled scope it must
        // never continue; without cancellation it would run on and touch a destroyed instance.
        service.releaseSuspension.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "a coroutine launched before onDestroy() resumed after the service was destroyed",
            service.resumedAfterSuspension
        )

        // The flag above is not enough on its own. CancellationException IS an Exception, so a
        // cancelled coroutine still lands in onClick()'s generic catch unless that catch lets
        // cancellation through - and that catch touches qsTile, on the instance just destroyed.
        // The error log is what proves whether it ran.
        assertFalse(
            "cancellation was handled as a tile error, which touches qsTile on a destroyed tile",
            ShadowLog.getLogs().any { it.msg?.contains("Error executing tile action") == true }
        )
    }

    /**
     * Robolectric 4.14.1's ShadowTileService does not extend ShadowService, so the framework's
     * own TileService.onDestroy() throws ClassCastException under test. That happens inside the
     * super call, after this app's cancellation has already run, so the behaviour under test is
     * still exercised - only the framework teardown cannot complete here.
     */
    private fun destroyTolerantly(service: BaseTileService) {
        try {
            service.onDestroy()
        } catch (expectedUnderRobolectric: ClassCastException) {
            // see above
        }
    }

    @Test
    fun `work launched before onStopListening still resumes, because the instance lives on`() {
        val controller = Robolectric.buildService(TestTileService::class.java).create()
        val service = controller.get()

        service.onClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.reachedSuspensionPoint.isCompleted)

        // Closing the Quick Settings panel must NOT kill the scope: the same instance is reused
        // the next time the panel opens.
        service.onStopListening()
        service.releaseSuspension.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "closing the panel must not cancel in-flight work - the tile instance is still alive",
            service.resumedAfterSuspension
        )
    }
}
