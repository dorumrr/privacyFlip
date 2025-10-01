package io.github.dorumrr.privacyflip.worker

import android.app.ActivityManager
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

            val isServiceRunning = isServiceRunning(PrivacyMonitorService::class.java.name)

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

    private fun isServiceRunning(serviceName: String): Boolean {
        return try {
            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            
            runningServices.any { serviceInfo ->
                serviceInfo.service.className == serviceName
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is running", e)
            false
        }
    }
}
