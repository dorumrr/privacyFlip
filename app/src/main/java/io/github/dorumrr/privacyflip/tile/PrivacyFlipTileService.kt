package io.github.dorumrr.privacyflip.tile

import android.annotation.TargetApi
import android.os.Build
import android.service.quicksettings.Tile
import android.util.Log

@TargetApi(Build.VERSION_CODES.N)
class PrivacyFlipTileService : BaseTileService() {

    override val tag = "PrivacyFlipTileService"
    override val serviceName = "PrivacyFlip Tile Service"

    override suspend fun executeAction() {
        val allFeatures = io.github.dorumrr.privacyflip.data.PrivacyFeature.getConnectivityFeatures().toSet()
        val results = privacyManager.disableFeatures(allFeatures)
        val allSuccess = results.all { it.success }

        if (allSuccess) {
            Log.d(tag, "Privacy features toggled successfully")
        } else {
            Log.w(tag, "Some privacy features failed to toggle")
        }
    }

    override suspend fun updateTileStateInternal() {
        val status = privacyManager.getPrivacyStatus()
        val tile = qsTile ?: return

        when {
            status.isActive && status.activeFeatures.size >= 3 -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Privacy Active"
                tile.contentDescription = "Privacy features are active"
            }
            status.isActive -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Privacy Partial"
                tile.contentDescription = "Some privacy features are active"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Privacy Inactive"
                tile.contentDescription = "Privacy features are inactive"
            }
        }

        tile.updateTile()
    }
}
