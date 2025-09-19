package io.github.dorumrr.privacyflip.tile

import android.annotation.TargetApi
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import io.github.dorumrr.privacyflip.privacy.PrivacyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@TargetApi(Build.VERSION_CODES.N)
class PrivacyFlipTileService : TileService() {
    
    companion object {
        private const val TAG = "PrivacyFlipTileService"
    }
    
    private lateinit var privacyManager: PrivacyManager
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()
        privacyManager = PrivacyManager.getInstance(this)
        Log.d(TAG, "PrivacyFlip Tile Service created")
    }
    
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }
    
    override fun onClick() {
        super.onClick()
        Log.d(TAG, "PrivacyFlip tile clicked")
        
        serviceScope.launch {
            try {
                val allFeatures = io.github.dorumrr.privacyflip.data.PrivacyFeature.getConnectivityFeatures().toSet()
                val results = privacyManager.disableFeatures(allFeatures)
                val allSuccess = results.all { it.success }

                if (allSuccess) {
                    Log.d(TAG, "Privacy features toggled successfully")
                } else {
                    Log.w(TAG, "Some privacy features failed to toggle")
                }

                updateTileState()
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling privacy features", e)
                qsTile?.state = Tile.STATE_UNAVAILABLE
                qsTile?.updateTile()
            }
        }
    }
    
    private fun updateTileState() {
        serviceScope.launch {
            try {
                val status = privacyManager.getPrivacyStatus()
                val tile = qsTile ?: return@launch
                
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
            } catch (e: Exception) {
                Log.e(TAG, "Error updating tile state", e)
                qsTile?.state = Tile.STATE_UNAVAILABLE
                qsTile?.updateTile()
            }
        }
    }
}
