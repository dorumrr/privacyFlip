package io.github.dorumrr.privacyflip.util

import android.util.Log
import java.io.File

class LogFileRotator {
    
    companion object {
        private const val TAG = "LogFileRotator"
        private val MAX_LOG_SIZE = Constants.Logging.MAX_LOG_SIZE_KB * Constants.Logging.BYTES_PER_KB
        private val ROTATION_KEEP_SIZE = (MAX_LOG_SIZE * Constants.Logging.LOG_ROTATION_KEEP_RATIO).toInt()
    }

    fun needsRotation(logFile: File): Boolean {
        return logFile.exists() && logFile.length() > MAX_LOG_SIZE
    }

    fun rotateLogFile(logFile: File) {
        try {
            if (!logFile.exists()) return
            
            val content = logFile.readText()
            val lines = content.lines()
            
            val keepLines = (lines.size * Constants.Logging.LOG_ROTATION_KEEP_RATIO).toInt()
            val newContent = lines.takeLast(keepLines).joinToString("\n")
            
            logFile.writeText(newContent + "\n")
            
            Log.i(TAG, "Log file rotated: kept $keepLines lines")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
            try {
                logFile.writeText("")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to truncate log file", e2)
            }
        }
    }
}
