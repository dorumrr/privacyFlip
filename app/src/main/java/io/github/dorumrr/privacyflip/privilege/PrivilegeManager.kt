package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.os.Build
import io.github.dorumrr.privacyflip.util.LogManager
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker
import io.github.dorumrr.privacyflip.util.SingletonHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PrivilegeManager private constructor(private val context: Context) {

    companion object : SingletonHolder<PrivilegeManager, Context>({ context ->
        PrivilegeManager(context.applicationContext)
    }) {
        private const val TAG = "privacyFlip-PrivilegeManager"

        // Detection is skipped while the executor we already have still works. It used to run on
        // every lock AND every unlock, building a fresh executor each time.
        internal fun shouldRedetect(hasExecutor: Boolean, currentStillAvailable: Boolean): Boolean =
            !hasExecutor || !currentStillAvailable
    }

    private val logManager = LogManager.getInstance(context)
    // Volatile: written on a worker thread by initialize(), read from the main thread by
    // getCurrentMethod() and by every command path.
    @Volatile
    private var currentExecutor: PrivilegeExecutor? = null

    @Volatile
    private var currentMethod: PrivilegeMethod = PrivilegeMethod.NONE

    // Serialises the whole check-and-swap. The fields being volatile buys visibility, not
    // atomicity: two overlapping calls could otherwise drop an executor without cleaning it up, or
    // clean the same one up twice.
    private val detectionMutex = Mutex()

    suspend fun initialize(): PrivilegeMethod = withContext(Dispatchers.IO) {
        detectionMutex.withLock {
            val existing = currentExecutor
            val stillAvailable = existing != null && isStillAvailable(existing)

            // Outside the early return below, and before it: Dhizuku's blocks outlive the process
            // and cannot be lifted from Settings, so the sweep has to run on every initialize,
            // not only on the ones that re-detect.
            releaseStaleDhizukuBlocks()

            if (!shouldRedetect(existing != null, stillAvailable)) {
                return@withLock currentMethod
            }

            currentMethod = detectBestPrivilegeMethod()
            currentMethod
        }
    }

    // A cancelled caller must never read as "the backend went away". Swallowed here, it reported
    // no privilege and then tore down a working, permission-granted executor.
    private suspend fun isStillAvailable(executor: PrivilegeExecutor): Boolean =
        try {
            executor.isAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logManager.d(TAG, "Availability check failed: ${e.message}")
            false
        }

    private fun releaseStaleDhizukuBlocks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val lockIsInFlight = PrivacyActionWorker.lastLockAtMillis > PrivacyActionWorker.lastUnlockAtMillis
            DhizukuFeaturePolicy.releaseStaleBlocks(context, lockIsInFlight)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: a third-party API that asserts throws an Error, and a
            // sweep that is only a safety net must never take the whole app down with it.
            logManager.d(TAG, "Dhizuku stale-block sweep skipped: ${e.message}")
        }
    }

    /**
     * Takes ownership of [next] and tears down whatever it replaces.
     *
     * ShizukuExecutor.cleanup() removes three binder listeners and cancels its scope. Without this
     * every lock cycle left another set registered, so one Shizuku restart fired all of them.
     */
    private fun adopt(next: PrivilegeExecutor) {
        val previous = currentExecutor
        currentExecutor = next
        if (previous !== next) discard(previous)
    }

    /** Tears down an executor this detection built and did not keep. */
    private fun discard(executor: PrivilegeExecutor?) {
        if (executor == null) return
        try {
            executor.cleanup()
        } catch (e: Exception) {
            logManager.e(TAG, "Error cleaning up a discarded executor: ${e.message}")
        }
    }
    
    private suspend fun detectBestPrivilegeMethod(): PrivilegeMethod {
        // Priority: Sui > Root > Dhizuku > Shizuku
        // Each candidate is declared OUTSIDE its try, so a throw from initialize() or
        // isAvailable() can still clean up the half-built executor. Without that, a flaky binder
        // leaked three listeners per attempt, which is the very leak this is meant to close.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var suiExecutor: PrivilegeExecutor? = null
            try {
                if (SuiDetector.detectAndInitialize(context)) {
                    logManager.d(TAG, "Sui detected and initialized successfully")
                    suiExecutor = createShizukuExecutor()
                    suiExecutor.initialize(context)
                    if (suiExecutor.isAvailable()) {
                        adopt(suiExecutor)
                        return PrivilegeMethod.SUI
                    }
                    // Detected but unusable. Fall through to the other backends rather than claim
                    // SUI and fail every command with no fallback left.
                    logManager.d(TAG, "Sui detected but its executor is not available")
                    discard(suiExecutor)
                }
            } catch (e: CancellationException) {
                discard(suiExecutor)
                throw e
            } catch (e: Exception) {
                logManager.d(TAG, "Sui detection failed: ${e.message}")
                discard(suiExecutor)
            }
        }

        var rootExecutor: PrivilegeExecutor? = null
        try {
            rootExecutor = RootExecutor()
            rootExecutor.initialize(context)

            if (rootExecutor.isAvailable()) {
                logManager.d(TAG, "Root detected and available")
                adopt(rootExecutor)
                return PrivilegeMethod.ROOT
            }
            logManager.d(TAG, "Root not available (su binary not found)")
            discard(rootExecutor)
        } catch (e: CancellationException) {
            discard(rootExecutor)
            throw e
        } catch (e: Exception) {
            logManager.e(TAG, "Root detection failed: ${e.message}")
            discard(rootExecutor)
        }

        // Try Dhizuku (Device Owner)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var dhizukuExecutor: PrivilegeExecutor? = null
            try {
                dhizukuExecutor = DhizukuExecutor()
                dhizukuExecutor.initialize(context)

                if (dhizukuExecutor.isAvailable()) {
                    logManager.d(TAG, "Dhizuku detected and available")
                    adopt(dhizukuExecutor)
                    return PrivilegeMethod.DHIZUKU
                }
                logManager.d(TAG, "Dhizuku not available (service not running)")
                discard(dhizukuExecutor)
            } catch (e: CancellationException) {
                discard(dhizukuExecutor)
                throw e
            } catch (e: Exception) {
                logManager.d(TAG, "Dhizuku detection failed: ${e.message}")
                discard(dhizukuExecutor)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var shizukuExecutor: PrivilegeExecutor? = null
            try {
                shizukuExecutor = createShizukuExecutor()
                shizukuExecutor.initialize(context)

                if (shizukuExecutor.isAvailable()) {
                    logManager.d(TAG, "Shizuku detected and available")
                    adopt(shizukuExecutor)
                    return PrivilegeMethod.SHIZUKU
                }
                logManager.d(TAG, "Shizuku not available (binder not responding)")
                discard(shizukuExecutor)
            } catch (e: CancellationException) {
                discard(shizukuExecutor)
                throw e
            } catch (e: Exception) {
                logManager.d(TAG, "Shizuku detection failed: ${e.message}")
                discard(shizukuExecutor)
            }
        }

        logManager.w(TAG, "No privilege method available")
        // Safe to drop here only because detection now runs solely when the executor we had has
        // already stopped being available. Otherwise the answers disagree: the method reads NONE
        // while isPermissionGranted() still answers true from the executor just rejected.
        val stale = currentExecutor
        currentExecutor = null
        discard(stale)
        return PrivilegeMethod.NONE
    }
    
    private fun createShizukuExecutor(): PrivilegeExecutor {
        return ShizukuExecutor()
    }

    fun getCurrentMethod(): PrivilegeMethod = currentMethod

    suspend fun isPrivilegeAvailable(): Boolean {
        return currentExecutor?.isAvailable() ?: false
    }

    suspend fun isPermissionGranted(): Boolean {
        val granted = currentExecutor?.isPermissionGranted() ?: false
        android.util.Log.d(TAG, "PrivilegeManager.isPermissionGranted() - currentMethod: $currentMethod, result: $granted")
        return granted
    }

    suspend fun requestPermission(): Boolean {
        android.util.Log.d(TAG, "PrivilegeManager.requestPermission() - currentMethod: $currentMethod, calling executor...")
        val granted = currentExecutor?.requestPermission() ?: false
        android.util.Log.d(TAG, "PrivilegeManager.requestPermission() - executor returned: $granted")
        return granted
    }

    suspend fun executeCommand(command: String): CommandResult {
        val executor = currentExecutor
        if (executor == null) {
            return CommandResult.failure("No privilege executor available")
        }

        return executor.executeCommand(command)
    }

    suspend fun executeWithFallbacks(commands: List<String>): CommandResult {
        val executor = currentExecutor
        if (executor == null) {
            return CommandResult.failure("No privilege executor available")
        }

        return executor.executeWithFallbacks(commands)
    }

    suspend fun setFeatureState(
        feature: io.github.dorumrr.privacyflip.data.PrivacyFeature,
        enable: Boolean
    ): CommandResult? = currentExecutor?.setFeatureState(feature, enable)

    fun supportsFeature(feature: io.github.dorumrr.privacyflip.data.PrivacyFeature): Boolean =
        currentExecutor?.supportsFeature(feature) ?: true

    suspend fun readFeatureState(
        feature: io.github.dorumrr.privacyflip.data.PrivacyFeature
    ): io.github.dorumrr.privacyflip.data.FeatureState? = currentExecutor?.readFeatureState(feature)

}

