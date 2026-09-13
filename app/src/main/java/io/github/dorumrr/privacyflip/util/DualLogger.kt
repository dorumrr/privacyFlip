package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.util.Log

/**
 * Writes one message to both log sinks: logcat, and [DebugLogHelper]'s user-facing file (which
 * applies its own "Debug Logs" preference gate).
 *
 * Three classes used to hand-write this pair at every log call, so a change to how the two
 * sinks are kept in step - a severity filter, redaction before anything reaches the file - had
 * to be repeated in each of them, and a message could drift between sinks in one file only.
 */
class DualLogger(context: Context, private val tag: String) {

    private val debugLogger: DebugLogHelper by lazy { DebugLogHelper.getInstance(context) }

    fun i(message: String) {
        Log.i(tag, message)
        debugLogger.i(tag, message)
    }

    fun w(message: String) {
        Log.w(tag, message)
        debugLogger.w(tag, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        debugLogger.e(tag, message, throwable)
    }
}
