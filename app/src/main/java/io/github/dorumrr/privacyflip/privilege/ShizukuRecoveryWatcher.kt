package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dorumrr.privacyflip.service.PrivacyMonitorService
import io.github.dorumrr.privacyflip.util.PreferenceManager
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notices Shizuku coming back, for the whole life of the process.
 *
 * Privilege detection BUILDS a Shizuku executor to test it and throws it away when Shizuku is not
 * answering, and that executor's own listeners go with it. Those listeners used to survive only
 * because nothing cleaned them up, and it was that accident which noticed Shizuku returning.
 * Cleaning them up properly left nothing watching at all, so this owns that one job instead.
 *
 * Registered once and deliberately never removed: the whole point is to outlive every executor.
 */
object ShizukuRecoveryWatcher {

    private const val TAG = "privacyFlip-ShizukuRecoveryWatcher"
    const val STATUS_CHANGED_ACTION = "io.github.dorumrr.privacyflip.SHIZUKU_STATUS_CHANGED"

    @Volatile
    private var appContext: Context? = null

    private val watching = AtomicBoolean(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { onShizukuReturned() }

    // Injectable so a test can count registrations without a real Shizuku on the other end.
    internal var registrar: (Shizuku.OnBinderReceivedListener) -> Unit = {
        Shizuku.addBinderReceivedListener(it)
    }

    /**
     * Arms the watcher. Safe to call from every executor build: only the first call registers,
     * even when several threads arrive together.
     */
    fun ensureWatching(context: Context) {
        appContext = context.applicationContext
        if (!watching.compareAndSet(false, true)) return
        try {
            registrar(binderReceivedListener)
            Log.i(TAG, "Watching for Shizuku to come back")
        } catch (e: Exception) {
            // Left disarmed rather than silently believed to be armed, so a later call can retry.
            watching.set(false)
            Log.w(TAG, "Could not watch for Shizuku returning: ${e.message}")
        }
    }

    private fun onShizukuReturned() {
        val ctx = appContext ?: return
        Log.i(TAG, "Shizuku is back")
        try {
            // The service re-detects privilege on its own start, so it does not need asking here
            // whether permission came back with it.
            if (PreferenceManager.getInstance(ctx).isGlobalPrivacyEnabled) {
                PrivacyMonitorService.start(ctx)
            }
            ctx.sendBroadcast(Intent(STATUS_CHANGED_ACTION))
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Shizuku returning: ${e.message}")
        }
    }

    /** Test hook: drops the registration state so each test starts disarmed. */
    internal fun resetForTest() {
        watching.set(false)
        appContext = null
    }
}
