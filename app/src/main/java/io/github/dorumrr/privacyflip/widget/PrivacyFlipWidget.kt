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

class PrivacyFlipWidget : AppWidgetProvider() {
    
    companion object {
        private const val TAG = "PrivacyFlipWidget"
        private const val ACTION_TOGGLE_PRIVACY = "io.github.dorumrr.privacyflip.TOGGLE_PRIVACY"
        
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
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        if (intent.action == ACTION_TOGGLE_PRIVACY) {
            Log.d(TAG, "Widget toggle privacy action received")
            
            try {
                val preferenceManager = PreferenceManager.getInstance(context)
                
                // Toggle global privacy state
                val currentState = preferenceManager.isGlobalPrivacyEnabled
                val newState = !currentState
                preferenceManager.isGlobalPrivacyEnabled = newState
                
                Log.d(TAG, "Global privacy toggled from widget: $currentState -> $newState")
                
                // Update all widgets to reflect new state
                updateAllWidgets(context)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling privacy from widget", e)
            }
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
        
        // Set click listener for toggle
        val toggleIntent = Intent(context, PrivacyFlipWidget::class.java).apply {
            action = ACTION_TOGGLE_PRIVACY
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
