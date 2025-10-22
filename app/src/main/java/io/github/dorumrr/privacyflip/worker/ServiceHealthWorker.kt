package io.github.dorumrr.privacyflip.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.dorumrr.privacyflip.service.PrivacyMonitorService
import io.github.dorumrr.privacyflip.util.PreferenceManager

class ServiceHealthWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ServiceHealthWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Checking service health...")

            val preferenceManager = PreferenceManager.getInstance(applicationContext)
            // Always ensure background service is enabled
            preferenceManager.backgroundServiceEnabled = true

            val isServiceRunning = isServiceRunning()

            if (!isServiceRunning) {
                Log.w(TAG, "PrivacyMonitorService not running, attempting restart...")
                PrivacyMonitorService.start(applicationContext)
                Log.i(TAG, "Service restart attempted")
            } else {
                Log.d(TAG, "PrivacyMonitorService is running normally")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during service health check", e)
            Result.retry()
        }
    }

    private fun isServiceRunning(): Boolean {
        return try {
            // Use the service's own running state tracker
            PrivacyMonitorService.isRunning()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is running", e)
            false
        }
    }
}
