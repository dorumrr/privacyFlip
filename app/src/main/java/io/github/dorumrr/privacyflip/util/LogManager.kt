package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.util.Log

/**
 * Tag-prefixing wrapper around android.util.Log, so every log line this app emits is findable
 * under one logcat filter regardless of which class wrote it.
 *
 * Logs are NOT written to a file here. The user-facing log file, the one the log viewer screen
 * reads and shares, belongs to [DebugLogHelper] and is gated behind the user's own
 * "Debug Logs" preference.
 */
class LogManager private constructor(context: Context) {

    companion object : SingletonHolder<LogManager, Context>({ context ->
        LogManager(context.applicationContext)
    }) {
        private const val LOG_PREFIX = "privacyFlip-"

        private fun prefixTag(tag: String): String {
            return if (tag.startsWith(LOG_PREFIX)) tag else "$LOG_PREFIX$tag"
        }
    }

    fun log(level: String, tag: String, message: String) {
        val prefixedTag = prefixTag(tag)

        when (level) {
            "D" -> Log.d(prefixedTag, message)
            "I" -> Log.i(prefixedTag, message)
            "W" -> Log.w(prefixedTag, message)
            "E" -> Log.e(prefixedTag, message)
            else -> Log.d(prefixedTag, message)
        }
    }

    fun d(tag: String, message: String) = log("D", tag, message)
    fun i(tag: String, message: String) = log("I", tag, message)
    fun w(tag: String, message: String) = log("W", tag, message)
    fun e(tag: String, message: String) = log("E", tag, message)
}
