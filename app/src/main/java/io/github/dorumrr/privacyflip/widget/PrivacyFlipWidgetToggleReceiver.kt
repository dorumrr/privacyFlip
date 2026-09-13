package io.github.dorumrr.privacyflip.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
            PrivacyFlipWidget.toggleGlobalPrivacy(context, TAG)
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling privacy from widget", e)
        }
    }
}
