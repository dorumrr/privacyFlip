package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

class BatteryOptimizationManager(private val context: Context) {

    companion object {
        private const val TAG = "BatteryOptimizationManager"
    }

    private val powerManager: PowerManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get PowerManager", e)
                null
            }
        } else {
            null
        }
    }
    
    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val result = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                Log.d(TAG, "Battery optimization status: ${if (result) "IGNORED" else "OPTIMIZED"}")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check battery optimization status", e)
                false
            }
        } else {
            true
        }
    }
    
    fun createBatteryOptimizationIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create battery optimization intent", e)
                createBatteryOptimizationSettingsIntent()
            }
        } else {
            null
        }
    }
    
    fun createBatteryOptimizationSettingsIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create battery optimization settings intent", e)
                null
            }
        } else {
            null
        }
    }
    
    fun isBatteryOptimizationSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }
    

    fun logBatteryOptimizationStatus() {
        val status = when {
            !isBatteryOptimizationSupported() -> "NOT_SUPPORTED"
            isIgnoringBatteryOptimizations() -> "WHITELISTED"
            else -> "OPTIMIZED"
        }
        Log.i(TAG, "Battery optimization status: $status (API ${Build.VERSION.SDK_INT})")
    }
}
