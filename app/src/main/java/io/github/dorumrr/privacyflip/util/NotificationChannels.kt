package io.github.dorumrr.privacyflip.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Creates this app's notification channels. Two classes used to hand-write the same
 * version guard, builder block and manager lookup, so a change Android requires of every
 * channel had to be made in both or one channel would quietly keep the old shape.
 *
 * The channels themselves are deliberately different (a quiet ongoing service notification
 * versus a debug one that should actually be noticed), so those differences are parameters
 * rather than something a shared helper flattens. [vibration] and [lights] left null keep
 * Android's own defaults for that importance, which is what the service channel relies on.
 */
object NotificationChannels {

    fun create(
        context: Context,
        id: String,
        name: String,
        importance: Int,
        description: String,
        showBadge: Boolean = false,
        vibration: Boolean? = null,
        lights: Boolean? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(id, name, importance).apply {
            this.description = description
            setShowBadge(showBadge)
            vibration?.let { enableVibration(it) }
            lights?.let { enableLights(it) }
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
