package io.github.dorumrr.privacyflip.tile

import android.annotation.TargetApi
import android.os.Build
import android.service.quicksettings.Tile
import android.util.Log
import io.github.dorumrr.privacyflip.widget.PrivacyFlipWidget

@TargetApi(Build.VERSION_CODES.N)
class PrivacyFlipTileService : BaseTileService() {

    override val tag = "PrivacyFlipTileService"
    override val serviceName = "PrivacyFlip Tile Service"

    override suspend fun executeAction() {
        // Toggle global privacy state
        val currentState = preferenceManager.isGlobalPrivacyEnabled
        val newState = !currentState
        preferenceManager.isGlobalPrivacyEnabled = newState
        
        Log.d(tag, "Global privacy toggled: $currentState -> $newState")
        
        // Update widgets to reflect new state
        PrivacyFlipWidget.updateAllWidgets(this)
    }

    override suspend fun updateTileStateInternal() {
        val tile = qsTile ?: return
        val isEnabled = preferenceManager.isGlobalPrivacyEnabled

        if (isEnabled) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Privacy ON"
            tile.contentDescription = "Privacy Flip is enabled. Tap to disable."
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Privacy OFF"
            tile.contentDescription = "Privacy Flip is disabled. Tap to enable."
        }

        tile.updateTile()
    }
}
