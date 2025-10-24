package io.github.dorumrr.privacyflip.privilege

import android.content.Context
import android.os.Build
import io.github.dorumrr.privacyflip.util.LogManager
import rikka.sui.Sui

object SuiDetector {

    private const val TAG = "SuiDetector"
    private var logManager: LogManager? = null

    fun detectAndInitialize(context: Context): Boolean {
        if (logManager == null) {
            logManager = LogManager.getInstance(context)
        }

        // Sui requires Android 6.0+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }

        try {
            val packageName = context.packageName
            return Sui.init(packageName)
        } catch (e: Exception) {
            logManager?.e(TAG, "Error detecting Sui: ${e.message}")
            return false
        }
    }

    fun isSuiAvailable(): Boolean {
        return try {
            Sui.isSui()
        } catch (e: Exception) {
            false
        }
    }
}

