package io.github.dorumrr.privacyflip.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import io.github.dorumrr.privacyflip.MainActivity
import io.github.dorumrr.privacyflip.R
import io.github.dorumrr.privacyflip.util.PreferenceManager

/**
 * Draws the home screen widget and keeps it updated. Exported, because
 * Android itself must be able to reach it to redraw the widget - that is
 * safe, since redrawing changes nothing.
 *
 * The actual toggle action lives in PrivacyFlipWidgetToggleReceiver instead,
 * a separate, non-exported receiver, so no other app can trigger the toggle.
 */
class PrivacyFlipWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "PrivacyFlipWidget"

        /**
         * Update all widgets to reflect current privacy state.
         * Can be called from anywhere (Tile, MainViewModel, etc.)
         */
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, PrivacyFlipWidget::class.java)
            )
            if (widgetIds.isNotEmpty()) {
                Log.d(TAG, "Updating ${widgetIds.size} widgets")
                val intent = Intent(context, PrivacyFlipWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "Updating PrivacyFlip widgets: ${appWidgetIds.size}")

        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val preferenceManager = PreferenceManager.getInstance(context)
        val isEnabled = preferenceManager.isGlobalPrivacyEnabled

        Log.d(TAG, "Updating widget $appWidgetId - isEnabled: $isEnabled")

        val views = RemoteViews(context.packageName, R.layout.privacy_flip_widget)

        // Set click listener for toggle - targets the separate, non-exported
        // toggle receiver. This PendingIntent is built by this app, so Android
        // runs it as this app when the launcher fires it on tap, even though
        // the receiver itself is closed to everyone else.
        val toggleIntent = Intent(context, PrivacyFlipWidgetToggleReceiver::class.java).apply {
            action = PrivacyFlipWidgetToggleReceiver.ACTION_TOGGLE_PRIVACY
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_container, togglePendingIntent)

        // Set click listener on icon to open app
        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_icon, openAppPendingIntent)

        // Update visual state based on privacy enabled status
        if (isEnabled) {
            views.setInt(R.id.widget_container, "setBackgroundResource", R.drawable.widget_background_active)
            views.setTextViewText(R.id.widget_text, context.getString(R.string.widget_privacy_on))
        } else {
            views.setInt(R.id.widget_container, "setBackgroundResource", R.drawable.widget_background_inactive)
            views.setTextViewText(R.id.widget_text, context.getString(R.string.widget_privacy_off))
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
