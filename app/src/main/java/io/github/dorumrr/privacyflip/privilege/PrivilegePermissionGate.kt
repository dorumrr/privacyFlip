package io.github.dorumrr.privacyflip.privilege

import android.os.SystemClock
import io.github.dorumrr.privacyflip.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
 * that are easy to get subtly wrong and were maintained twice: take the waiting slot atomically,
 * so a duplicate answer cannot resume a continuation twice, and throw away a cached "no"
 * whenever the backend is available again (so a helper that was restarted is not remembered as
 * refused forever).
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
    private val startRequest: (requestId: Int) -> Unit,
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

        // Shizuku quotes this back as its request code, so it starts where the old fixed code was.
        private const val FIRST_REQUEST_ID = 1001
    }

    @Volatile
    private var cached: Boolean? = null

    @Volatile
    private var cachedAtMillis: Long = 0L

    // Serialises request() so two callers cannot both own the single continuation slot below.
    private val requestMutex = Mutex()

    /** The request currently parked, and the id the backend was asked to quote back. */
    private class Waiting(val id: Int, val continuation: Continuation<Boolean>)

    // Atomic, not volatile: deliverResult() and forget() both take this slot from threads the
    // caller does not control, and a continuation resumed twice throws.
    private val waiting = AtomicReference<Waiting?>(null)

    // Every request carries its own id. Without one, an answer to a dialog that already timed out
    // was handed to whichever request happened to be parked when it finally arrived.
    private val nextRequestId = AtomicInteger(FIRST_REQUEST_ID)

    /** Removes and returns the parked request, but only if [id] is the one it is waiting for. */
    private fun takeWaiting(id: Int): Continuation<Boolean>? {
        val current = waiting.get() ?: return null
        if (current.id != id) return null
        return if (waiting.compareAndSet(current, null)) current.continuation else null
    }

    /** Clears the slot only if this request still owns it. */
    private fun clearWaiting(id: Int) {
        val current = waiting.get() ?: return
        if (current.id == id) waiting.compareAndSet(current, null)
    }

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
                    // A remembered "yes" must not outlive a refusal to even ask: the grant it came
                    // from is the most likely thing to have just been revoked.
                    cached = null
                    cachedAtMillis = 0L
                    return@withLock false
                }
                PermissionPreCheck.AskTheUser -> Unit
            }

            log?.d(tag, "requestPermission() - about to show the permission dialog...")

            val requestId = nextRequestId.getAndIncrement()

            val granted = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    waiting.set(Waiting(requestId, cont))

                    try {
                        startRequest(requestId)
                        log?.d(tag, "requestPermission() - request $requestId started, waiting for the user...")
                    } catch (e: Exception) {
                        log?.e(tag, "requestPermission() - exception starting the request: ${e.message}")
                        // Taken rather than cleared: a backend can answer synchronously inside
                        // startRequest() and then throw, and that answer already resumed this.
                        takeWaiting(requestId)?.resume(false)
                        return@suspendCancellableCoroutine
                    }

                    cont.invokeOnCancellation {
                        log?.d(tag, "requestPermission() - permission request $requestId cancelled")
                        clearWaiting(requestId)
                    }
                }
            } ?: false

            log?.d(tag, "requestPermission() - result: $granted")
            rememberAnswer(granted)
            log?.d(tag, "========== requestPermission() END - returning $granted ==========")
            granted

        } catch (e: CancellationException) {
            // The caller's scope went away; nobody was refused. Recording a "no" here reports a
            // refusal the user never gave, and swallowing it keeps a dead scope alive.
            waiting.set(null)
            throw e
        } catch (e: Exception) {
            log?.e(tag, "requestPermission() - ERROR: ${e.message}")
            waiting.set(null)
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
    fun deliverResult(requestId: Int, granted: Boolean) {
        val log = logger()
        rememberAnswer(granted)

        val parked = takeWaiting(requestId)
        if (parked == null) {
            log?.w(tag, "permission result for request $requestId has nothing waiting - cache updated only")
            return
        }
        parked.resume(granted)
        log?.d(tag, "permission result delivered to request $requestId: $granted")
    }

    /** Forgets the remembered answer, and fails any request the dead backend left waiting. */
    fun forget() {
        cached = null
        cachedAtMillis = 0L
        // Nothing can answer a parked request now, so fail it here rather than leave it to sit
        // out the whole timeout and report the same refusal 30 seconds later. Whatever is parked,
        // regardless of its id: the backend it belonged to is gone.
        waiting.getAndSet(null)?.continuation?.resume(false)
    }
}
