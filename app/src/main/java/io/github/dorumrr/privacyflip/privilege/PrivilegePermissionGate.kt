package io.github.dorumrr.privacyflip.privilege

import android.os.SystemClock
import io.github.dorumrr.privacyflip.util.LogManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * What a backend's own pre-checks decided, before any permission dialog is shown.
 */
sealed interface PermissionPreCheck {
    /** Already granted - no dialog needed. */
    object AlreadyGranted : PermissionPreCheck

    /** This backend cannot ask right now (too old a version, user said don't-ask-again). */
    object CannotAsk : PermissionPreCheck

    /** Nothing in the way: show the dialog. */
    object AskTheUser : PermissionPreCheck
}

/**
 * The permission dance every privileged backend performs, in one place: caching the answer,
 * waiting for the user with a timeout, and handing the result back to whoever was waiting.
 *
 * Shizuku and Dhizuku each wrote this out in full, about 110 lines apiece, including two rules
 * that are easy to get subtly wrong and were maintained twice: clear the waiting slot BEFORE
 * resuming it (so a duplicate answer cannot resume a continuation twice), and throw away a
 * cached "no" whenever the backend is available again (so a helper that was restarted is not
 * remembered as refused forever).
 *
 * Everything that differs between the backends stays with the backend, as hooks: which SDK call
 * reads the permission, what pre-checks that SDK has, and how the request is started. How each
 * one RECEIVES its answer differs too (Shizuku registers one permanent listener and matches a
 * request code; Dhizuku passes a fresh callback per request), so neither is moved here - both
 * simply call [deliverResult] from wherever their own answer arrives.
 *
 * @param timeoutMs how long to wait for the user. Injectable so a test does not wait 30 seconds.
 */
class PrivilegePermissionGate(
    private val tag: String,
    private val logger: () -> LogManager?,
    private val isBackendAvailable: suspend () -> Boolean,
    private val readPermissionFromBackend: suspend () -> Boolean,
    private val preCheck: suspend () -> PermissionPreCheck,
    private val startRequest: () -> Unit,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val grantValidForMs: Long = DEFAULT_GRANT_VALID_MS,
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }
) {

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        // A grant can be withdrawn in the backend's own app without the binder dying, so a
        // remembered "yes" is re-read once it is this old. Short enough that a revoked grant is
        // noticed quickly, long enough that the per-command check is not a binder call each time.
        const val DEFAULT_GRANT_VALID_MS = 5_000L
    }

    @Volatile
    private var cached: Boolean? = null

    @Volatile
    private var cachedAtMillis: Long = 0L

    // Serialises request() so two callers cannot both own the single continuation slot below.
    private val requestMutex = Mutex()

    // Volatile because the two ends are on different threads: request() writes this from the
    // caller's dispatcher, while deliverResult() reads and clears it on whichever thread the
    // backend delivers its answer on (the main thread, for both Shizuku's listener and
    // Dhizuku's callback). Without it, deliverResult can read a stale null, decide nothing is
    // waiting, and leave the request to time out and report refused after the user allowed it.
    @Volatile
    private var continuation: Continuation<Boolean>? = null

    /** The last known answer, without asking the backend. Null means "not known". */
    val cachedAnswer: Boolean?
        get() = cached

    suspend fun isGranted(): Boolean {
        val log = logger()
        log?.d(tag, "isPermissionGranted() called - cached value: $cached")

        if (!isBackendAvailable()) {
            log?.w(tag, "isPermissionGranted() - backend is not available")
            rememberAnswer(false)
            return false
        }

        // A remembered "no" is thrown away whenever the backend is reachable again: the helper
        // may have been stopped and restarted, and permission re-granted, since that "no".
        if (cached == false) {
            log?.d(tag, "isPermissionGranted() - backend available but cache is false, re-checking")
            cached = null
        }

        if (cached == true && nowMillis() - cachedAtMillis >= grantValidForMs) {
            log?.d(tag, "isPermissionGranted() - remembered grant has aged out, re-checking")
            cached = null
        }

        cached?.let {
            log?.d(tag, "isPermissionGranted() returning cached value: $it")
            return it
        }

        return try {
            val granted = readPermissionFromBackend()
            log?.d(tag, "isPermissionGranted() - backend reports: $granted")
            rememberAnswer(granted)
            granted
        } catch (e: Exception) {
            log?.e(tag, "isPermissionGranted() - exception: ${e.message}")
            rememberAnswer(false)
            false
        }
    }

    suspend fun request(): Boolean = requestMutex.withLock {
        val log = logger()
        return@withLock try {
            log?.d(tag, "========== requestPermission() START ==========")
            log?.d(tag, "requestPermission() - current cached value: $cached")

            when (preCheck()) {
                PermissionPreCheck.AlreadyGranted -> {
                    log?.d(tag, "requestPermission() - already granted, updating cache")
                    rememberAnswer(true)
                    return@withLock true
                }
                PermissionPreCheck.CannotAsk -> {
                    log?.w(tag, "requestPermission() - backend cannot ask for permission right now")
                    return@withLock false
                }
                PermissionPreCheck.AskTheUser -> Unit
            }

            log?.d(tag, "requestPermission() - about to show the permission dialog...")

            val granted = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    continuation = cont

                    try {
                        startRequest()
                        log?.d(tag, "requestPermission() - request started, waiting for the user...")
                    } catch (e: Exception) {
                        log?.e(tag, "requestPermission() - exception starting the request: ${e.message}")
                        continuation = null
                        cont.resume(false)
                        return@suspendCancellableCoroutine
                    }

                    cont.invokeOnCancellation {
                        log?.d(tag, "requestPermission() - permission request cancelled")
                        continuation = null
                    }
                }
            } ?: false

            log?.d(tag, "requestPermission() - result: $granted")
            rememberAnswer(granted)
            log?.d(tag, "========== requestPermission() END - returning $granted ==========")
            granted

        } catch (e: Exception) {
            log?.e(tag, "requestPermission() - ERROR: ${e.message}")
            continuation = null
            rememberAnswer(false)
            false
        }
    }

    private fun rememberAnswer(granted: Boolean) {
        cached = granted
        cachedAtMillis = nowMillis()
    }

    /**
     * Called by the backend's own listener when the user answers. Safe to call when nothing is
     * waiting: an answer can arrive out of band, and it still updates what we know.
     */
    fun deliverResult(granted: Boolean) {
        val log = logger()
        rememberAnswer(granted)

        // Cleared BEFORE resuming: a duplicate answer must not resume the same continuation
        // twice, which throws.
        val waiting = continuation
        if (waiting == null) {
            log?.w(tag, "permission result arrived with nothing waiting - cache updated only")
            return
        }
        continuation = null
        waiting.resume(granted)
        log?.d(tag, "permission result delivered to the waiting request: $granted")
    }

    /** Forgets the remembered answer, for teardown. */
    fun forget() {
        cached = null
        cachedAtMillis = 0L
        continuation = null
    }
}
