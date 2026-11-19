package io.github.dorumrr.privacyflip.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.dorumrr.privacyflip.MainActivity
import io.github.dorumrr.privacyflip.R
import io.github.dorumrr.privacyflip.privilege.PrivilegeMethod
import io.github.dorumrr.privacyflip.service.PrivacyMonitorService
import io.github.dorumrr.privacyflip.tile.PrivacyFlipTileService
import io.github.dorumrr.privacyflip.widget.PrivacyFlipWidget

/** The one place that tells the user, outside debug output, that lock and unlock actions cannot run. */
object PrivilegeLossNotice {

    private const val TAG = "privacyFlip-PrivilegeLossNotice"

    const val SHIZUKU_STOPPED =
        "Shizuku (or Sui) stopped running, so lock and unlock actions are skipped. " +
            "Start it again; if this notice stays, open Privacy Flip and grant its permission."

    fun reasonFor(method: PrivilegeMethod): String = when (method) {
        PrivilegeMethod.NONE ->
            "No Root, Sui, Dhizuku or Shizuku access was found, so lock and unlock actions are skipped. " +
                "Start Dhizuku or Shizuku, or allow root, then open Privacy Flip and tap Check Again."
        else ->
            "${method.getDisplayName()} is set up, but Privacy Flip does not have its permission, " +
                "so lock and unlock actions are skipped. Open Privacy Flip and grant it."
    }

    fun protectionCanRun(prefs: PreferenceManager): Boolean =
        prefs.isGlobalPrivacyEnabled && prefs.privilegeMissingReason == null

    // Posted only when the reason changes: a loss that lasts days is one notification, not one per lock.
    @Synchronized
    fun report(context: Context, reason: String) {
        val app = context.applicationContext
        val prefs = PreferenceManager.getInstance(app)
        if (prefs.privilegeMissingReason == reason) return
        prefs.privilegeMissingReason = reason

        NotificationChannels.create(
            context = app,
            id = Constants.AccessNotification.CHANNEL_ID,
            name = "Privileged access",
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            description = "Tells you when Privacy Flip has lost the access it needs to act",
            showBadge = true
        )
        val openApp = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(app, Constants.AccessNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_privacy_shield)
            .setContentTitle("Privacy Flip cannot act")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            manager(app).notify(Constants.AccessNotification.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission refused, the main screen still shows it: ${e.message}")
        }
        refreshSurfaces(app)
    }

    @Synchronized
    fun clear(context: Context) {
        val app = context.applicationContext
        val prefs = PreferenceManager.getInstance(app)
        if (prefs.privilegeMissingReason == null) return
        prefs.privilegeMissingReason = null
        manager(app).cancel(Constants.AccessNotification.NOTIFICATION_ID)
        refreshSurfaces(app)
    }

    private fun manager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun refreshSurfaces(app: Context) {
        PrivacyFlipWidget.updateAllWidgets(app)
        PrivacyMonitorService.refreshNotification(app)
        try {
            TileService.requestListeningState(app, ComponentName(app, PrivacyFlipTileService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Tile refresh refused: ${e.message}")
        }
    }
}
