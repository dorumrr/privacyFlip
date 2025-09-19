package io.github.dorumrr.privacyflip.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import io.github.dorumrr.privacyflip.MainActivity
import io.github.dorumrr.privacyflip.R
import io.github.dorumrr.privacyflip.privacy.PrivacyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PrivacyFlipWidget : AppWidgetProvider() {
    
    companion object {
        private const val TAG = "PrivacyFlipWidget"
        private const val ACTION_TOGGLE_PRIVACY = "io.github.dorumrr.privacyflip.TOGGLE_PRIVACY"
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
            
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                try {
                    val privacyManager = PrivacyManager.getInstance(context)
                    
                    val allFeatures = io.github.dorumrr.privacyflip.data.PrivacyFeature.getConnectivityFeatures().toSet()
                    val results = privacyManager.disableFeatures(allFeatures)
                    val allSuccess = results.all { it.success }
                    
                    if (allSuccess) {
                        Log.d(TAG, "Privacy features toggled successfully from widget")
                    } else {
                        Log.w(TAG, "Some privacy features failed to toggle from widget")
                    }
                    
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val widgetIds = appWidgetManager.getAppWidgetIds(
                        android.content.ComponentName(context, PrivacyFlipWidget::class.java)
                    )
                    onUpdate(context, appWidgetManager, widgetIds)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling privacy from widget", e)
                }
            }
        }
    }
    
    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        Log.d(TAG, "Updating widget $appWidgetId")
        
        val views = RemoteViews(context.packageName, R.layout.privacy_flip_widget)
        
        val toggleIntent = Intent(context, PrivacyFlipWidget::class.java).apply {
            action = ACTION_TOGGLE_PRIVACY
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_container, togglePendingIntent)
        
        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_icon, openAppPendingIntent)
        
        views.setTextViewText(R.id.widget_text, context.getString(R.string.widget_name))
        
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
