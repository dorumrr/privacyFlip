package io.github.dorumrr.privacyflip.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dorumrr.privacyflip.util.PreferenceManager

/**
 * Handles the widget's tap-to-toggle action only.
 *
 * Kept separate from PrivacyFlipWidget (the AppWidgetProvider) and marked
 * android:exported="false" in the manifest, so only this app's own widget
 * tap (a PendingIntent it built itself) can reach it. PrivacyFlipWidget
 * itself stays exported, because Android needs to reach it to redraw the
 * widget, but redrawing carries no risk the way toggling protection does.
 */
class PrivacyFlipWidgetToggleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PrivacyFlipWidgetToggle"
        const val ACTION_TOGGLE_PRIVACY = "io.github.dorumrr.privacyflip.TOGGLE_PRIVACY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE_PRIVACY) {
            return
        }

        Log.d(TAG, "Widget toggle privacy action received")

        try {
            val preferenceManager = PreferenceManager.getInstance(context)

            // Toggle global privacy state
            val currentState = preferenceManager.isGlobalPrivacyEnabled
            val newState = !currentState
            preferenceManager.isGlobalPrivacyEnabled = newState

            Log.d(TAG, "Global privacy toggled from widget: $currentState -> $newState")

            // Update all widgets to reflect new state
            PrivacyFlipWidget.updateAllWidgets(context)

        } catch (e: Exception) {
            Log.e(TAG, "Error toggling privacy from widget", e)
        }
    }
}
