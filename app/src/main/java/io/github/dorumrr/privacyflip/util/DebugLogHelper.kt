package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper class for logging debug information to a file.
 * Logs are stored in a rotating file with a maximum size of 500KB.
 * When debug logging is enabled, all privacy actions and events are logged.
 */
class DebugLogHelper private constructor(private val context: Context) {

    companion object : SingletonHolder<DebugLogHelper, Context>({ context ->
        DebugLogHelper(context.applicationContext)
    }) {
        private const val TAG = "privacyFlip-DebugLogHelper"
        private const val LOG_FILE_NAME = "privacy_flip_debug.log"
        private const val MAX_LOG_SIZE_BYTES = 500 * 1024 // 500KB
        private const val TRIM_TO_SIZE_BYTES = 400 * 1024 // Trim to 400KB when exceeded
    }

    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager.getInstance(context)
    }

    private val logFile: File by lazy {
        File(context.filesDir, LOG_FILE_NAME)
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Log a debug message if debug logging is enabled.
     */
    fun log(tag: String, message: String) {
        if (!preferenceManager.debugLogsEnabled) {
            return
        }

        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "[$timestamp] [$tag] $message\n"

            synchronized(this) {
                // Check if we need to trim the log file
                if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
                    trimLogFile()
                }

                // Append to log file
                FileOutputStream(logFile, true).use { fos ->
                    fos.write(logLine.toByteArray(Charsets.UTF_8))
                }
            }

            Log.d(TAG, "Logged: $tag - $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log: ${e.message}", e)
        }
    }

    /**
     * Log an info-level message.
     */
    fun i(tag: String, message: String) = log(tag, "I: $message")

    /**
     * Log a debug-level message.
     */
    fun d(tag: String, message: String) = log(tag, "D: $message")

    /**
     * Log a warning-level message.
     */
    fun w(tag: String, message: String) = log(tag, "W: $message")

    /**
     * Log an error-level message.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log(tag, "E: $msg")
    }

    /**
     * Trim the log file by removing the oldest entries.
     */
    private fun trimLogFile() {
        try {
            if (!logFile.exists()) return

            val content = logFile.readText()
            val lines = content.lines()
            
            // Find how many lines to keep (roughly keep last 80% of content)
            val targetSize = TRIM_TO_SIZE_BYTES
            var currentSize = 0
            var startIndex = lines.size

            for (i in lines.indices.reversed()) {
                val lineSize = lines[i].toByteArray().size + 1 // +1 for newline
                if (currentSize + lineSize > targetSize) {
                    startIndex = i + 1
                    break
                }
                currentSize += lineSize
            }

            // Write trimmed content with a marker
            val trimmedLines = lines.subList(startIndex, lines.size)
            val trimmedContent = "--- Log trimmed at ${dateFormat.format(Date())} ---\n" +
                    trimmedLines.joinToString("\n")

            logFile.writeText(trimmedContent)
            Log.d(TAG, "Log file trimmed from ${lines.size} to ${trimmedLines.size} lines")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trim log file: ${e.message}", e)
        }
    }

    /**
     * Get the log file for sharing.
     */
    fun getLogFileForShare(): File = logFile

    /**
     * Check if log file exists and has content.
     */
    fun hasLogs(): Boolean = logFile.exists() && logFile.length() > 0

    /**
     * Get the size of the log file in a human-readable format.
     */
    fun getLogSizeFormatted(): String {
        if (!logFile.exists()) return "0 KB"
        val sizeKb = logFile.length() / 1024.0
        return String.format(Locale.US, "%.1f KB", sizeKb)
    }

    /**
     * Create an intent to share the log file.
     */
    fun createShareIntent(): Intent? {
        if (!hasLogs()) {
            Log.w(TAG, "No logs to share")
            return null
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )

            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Privacy Flip Debug Logs")
                putExtra(Intent.EXTRA_TEXT, "Privacy Flip debug log file (${getLogSizeFormatted()})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create share intent: ${e.message}", e)
            null
        }
    }

    /**
     * Clear all logs.
     */
    fun clearLogs() {
        try {
            if (logFile.exists()) {
                logFile.delete()
            }
            Log.d(TAG, "Logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs: ${e.message}", e)
        }
    }

    /**
     * Write a separator line to mark a new session.
     */
    fun logSessionStart() {
        if (!preferenceManager.debugLogsEnabled) return
        
        val separator = "=" .repeat(60)
        log("SESSION", separator)
        log("SESSION", "Privacy Flip Debug Session Started")
        log("SESSION", "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        log("SESSION", "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        log("SESSION", separator)
    }
}
