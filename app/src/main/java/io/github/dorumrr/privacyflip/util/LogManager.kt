package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class LogManager private constructor(private val context: Context) {

    companion object : SingletonHolder<LogManager, Context>({ context ->
        LogManager(context.applicationContext)
    }) {
        private const val TAG = "LogManager"
    }

    private val logFile = File(context.filesDir, Constants.Logging.LOG_FILE_NAME)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logFileRotator = LogFileRotator()

    private var isProcessing = false

    init {
        try {
            if (!logFile.exists()) {
                logFile.createNewFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create log file", e)
        }

        startLogProcessor()
    }

    fun log(level: String, tag: String, message: String) {
        try {
            when (level) {
                "D" -> Log.d(tag, message)
                "I" -> Log.i(tag, message)
                "W" -> Log.w(tag, message)
                "E" -> Log.e(tag, message)
                else -> Log.d(tag, message)
            }

            val timestamp = dateFormat.format(Date())
            val logEntry = "$timestamp $level/$tag: $message"
            logQueue.offer(logEntry)

            if (!isProcessing) {
                processLogQueue()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue log entry", e)
        }
    }

    fun d(tag: String, message: String) = log("D", tag, message)
    fun i(tag: String, message: String) = log("I", tag, message)
    fun w(tag: String, message: String) = log("W", tag, message)
    fun e(tag: String, message: String) = log("E", tag, message)

    suspend fun getLogs(): String = withContext(Dispatchers.IO) {
        try {
            if (logFile.exists() && logFile.length() > 0) {
                logFile.readText()
            } else {
                "No logs available yet.\n\nLogs will appear here as you use the app."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log file", e)
            "Error reading logs: ${e.message}"
        }
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        try {
            synchronized(logFile) {
                logQueue.clear()
                logFile.delete()
                logFile.createNewFile()
            }
            Log.i(TAG, "Log file cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log file", e)
        }
    }

    fun getLogFileSizeKB(): Int {
        return try {
            (logFile.length() / Constants.Logging.BYTES_PER_KB).toInt()
        } catch (e: Exception) {
            0
        }
    }

    private fun startLogProcessor() {
        scope.launch {
            while (true) {
                delay(Constants.UI.LOG_PROCESSING_INTERVAL_MS)
                if (logQueue.isNotEmpty()) {
                    processLogQueue()
                }
            }
        }
    }

    private fun processLogQueue() {
        if (isProcessing) return
        
        scope.launch {
            isProcessing = true
            try {
                val entries = mutableListOf<String>()
                
                while (logQueue.isNotEmpty() && entries.size < Constants.Logging.MAX_BATCH_SIZE) {
                    logQueue.poll()?.let { entries.add(it) }
                }
                
                if (entries.isNotEmpty()) {
                    writeToFile(entries)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing log queue", e)
            } finally {
                isProcessing = false
            }
        }
    }

    private suspend fun writeToFile(entries: List<String>) = withContext(Dispatchers.IO) {
        try {
            synchronized(logFile) {
                if (logFileRotator.needsRotation(logFile)) {
                    logFileRotator.rotateLogFile(logFile)
                }

                logFile.appendText(entries.joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    fun cleanup() {
        scope.cancel()
    }
}
