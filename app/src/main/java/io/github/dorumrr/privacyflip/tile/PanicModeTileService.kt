package io.github.dorumrr.privacyflip.tile

import android.annotation.TargetApi
import android.os.Build
import android.service.quicksettings.Tile
import android.util.Log

@TargetApi(Build.VERSION_CODES.N)
class PanicModeTileService : BaseTileService() {

    override val tag = "PanicModeTileService"
    override val serviceName = "Panic Mode Tile Service"

    override suspend fun executeAction() {
        val results = privacyManager.executePanicMode()
        val allSuccess = results.all { it.success }

        if (allSuccess) {
            Log.d(tag, "Panic mode executed successfully")
        } else {
            Log.w(tag, "Some panic mode actions failed")
        }
    }

    override suspend fun updateTileStateInternal() {
        val tile = qsTile ?: return

        tile.state = Tile.STATE_INACTIVE
        tile.label = "Panic Mode"
        tile.contentDescription = "Emergency privacy activation"

        tile.updateTile()
    }
}
