package io.github.dorumrr.privacyflip.privilege

import io.github.dorumrr.privacyflip.util.LogManager
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    @Volatile
    private var cached: Boolean? = null

    private var continuation: Continuation<Boolean>? = null

    /** The last known answer, without asking the backend. Null means "not known". */
    val cachedAnswer: Boolean?
        get() = cached

    suspend fun isGranted(): Boolean {
        val log = logger()
        log?.d(tag, "isPermissionGranted() called - cached value: $cached")

        if (!isBackendAvailable()) {
            log?.w(tag, "isPermissionGranted() - backend is not available")
            cached = false
            return false
        }

        // A remembered "no" is thrown away whenever the backend is reachable again: the helper
        // may have been stopped and restarted, and permission re-granted, since that "no".
        if (cached == false) {
            log?.d(tag, "isPermissionGranted() - backend available but cache is false, re-checking")
            cached = null
        }

        cached?.let {
            log?.d(tag, "isPermissionGranted() returning cached value: $it")
            return it
        }

        return try {
            val granted = readPermissionFromBackend()
            log?.d(tag, "isPermissionGranted() - backend reports: $granted")
            cached = granted
            granted
        } catch (e: Exception) {
            log?.e(tag, "isPermissionGranted() - exception: ${e.message}")
            cached = false
            false
        }
    }

    suspend fun request(): Boolean {
        val log = logger()
        return try {
            log?.d(tag, "========== requestPermission() START ==========")
            log?.d(tag, "requestPermission() - current cached value: $cached")

            when (preCheck()) {
                PermissionPreCheck.AlreadyGranted -> {
                    log?.d(tag, "requestPermission() - already granted, updating cache")
                    cached = true
                    return true
                }
                PermissionPreCheck.CannotAsk -> {
                    log?.w(tag, "requestPermission() - backend cannot ask for permission right now")
                    return false
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
            cached = granted
            log?.d(tag, "========== requestPermission() END - returning $granted ==========")
            granted

        } catch (e: Exception) {
            log?.e(tag, "requestPermission() - ERROR: ${e.message}")
            continuation = null
            cached = false
            false
        }
    }

    /**
     * Called by the backend's own listener when the user answers. Safe to call when nothing is
     * waiting: an answer can arrive out of band, and it still updates what we know.
     */
    fun deliverResult(granted: Boolean) {
        val log = logger()
        cached = granted

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
        continuation = null
    }
}
