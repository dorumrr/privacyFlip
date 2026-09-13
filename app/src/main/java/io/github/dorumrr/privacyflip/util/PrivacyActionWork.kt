package io.github.dorumrr.privacyflip.util

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker

/**
 * The one place that defines how a privacy action is handed to WorkManager: the input-data
 * contract, which unique name a direction uses, and the REPLACE policy.
 *
 * Four call sites across three classes used to hand-write this, and the worker read the same
 * four key strings back from its own copies of them, so a key renamed in one place and not the
 * others would have compiled and then silently delivered a default value at runtime.
 */
object PrivacyActionWork {

    const val KEY_IS_LOCKING = "is_locking"
    const val KEY_IS_DEVICE_LOCKED = "is_device_locked"
    const val KEY_TRIGGER = "trigger"
    const val KEY_REASON = "reason"

    /**
     * Lock work and unlock work each have one unique name, so a newer action of the same
     * direction REPLACEs the older one rather than running alongside it.
     */
    fun workName(isLocking: Boolean): String =
        if (isLocking) Constants.Work.NAME_LOCK else Constants.Work.NAME_UNLOCK

    fun buildRequest(
        isLocking: Boolean,
        isDeviceLocked: Boolean,
        trigger: String,
        reason: String
    ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
        .setInputData(
            workDataOf(
                KEY_IS_LOCKING to isLocking,
                KEY_IS_DEVICE_LOCKED to isDeviceLocked,
                KEY_TRIGGER to trigger,
                KEY_REASON to reason
            )
        )
        .build()

    /**
     * @return the unique work name used, so the caller can log which one it replaced.
     */
    fun enqueue(
        context: Context,
        isLocking: Boolean,
        isDeviceLocked: Boolean,
        trigger: String,
        reason: String
    ): String {
        val name = workName(isLocking)
        WorkManager.getInstance(context).enqueueUniqueWork(
            name,
            ExistingWorkPolicy.REPLACE,
            buildRequest(isLocking, isDeviceLocked, trigger, reason)
        )
        return name
    }
}
