package io.github.dorumrr.privacyflip.util

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Locks missed while the process was gone leave no line of their own, so the next start says why it ended. */
object ProcessExitTrace {

    private const val TAG = "privacyFlip-ProcessExit"

    // A service restart inside the same process must not repeat an exit it already reported.
    @Volatile
    private var reportedExitAt = 0L

    fun logLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val exit = try {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return
        synchronized(this) {
            if (exit.timestamp == reportedExitAt) return
            reportedExitAt = exit.timestamp
        }
        DualLogger(context, TAG).i("Last time Privacy Flip stopped: ${describe(exit)}")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(exit: ApplicationExitInfo): String {
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))
        val detail = exit.description?.takeIf { it.isNotBlank() }?.let { ", \"$it\"" }.orEmpty()
        return "$at, ${reasonName(exit.reason)} (reason ${exit.reason}, status ${exit.status}$detail), " +
            "while ${importanceName(exit.importance)}"
    }

    @android.annotation.SuppressLint("InlinedApi") // Compile-time constants: a newer reason code just never arrives on older Android.
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "start-up failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped on request (Force stop, or swiped away from Recents)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "its Android user or profile was stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        ApplicationExitInfo.REASON_OTHER -> "other system reason"
        ApplicationExitInfo.REASON_FREEZER -> "killed while frozen"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        else -> "unknown"
    }

    private fun importanceName(importance: Int): String = when {
        importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "in the foreground"
        importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "a foreground service"
        importance <= RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "visible or perceptible"
        importance <= RunningAppProcessInfo.IMPORTANCE_SERVICE -> "a background service"
        importance <= RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "the top app with the screen off"
        importance < RunningAppProcessInfo.IMPORTANCE_CACHED -> "a heavy background app"
        importance < RunningAppProcessInfo.IMPORTANCE_GONE -> "cached"
        else -> "already gone"
    }
}
