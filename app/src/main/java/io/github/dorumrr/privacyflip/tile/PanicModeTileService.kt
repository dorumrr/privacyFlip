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
class PanicModeTileService : TileService() {
    
    companion object {
        private const val TAG = "PanicModeTileService"
    }
    
    private lateinit var privacyManager: PrivacyManager
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()
        privacyManager = PrivacyManager.getInstance(this)
        Log.d(TAG, "Panic Mode Tile Service created")
    }
    
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }
    
    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Panic Mode tile clicked")
        
        serviceScope.launch {
            try {
                val results = privacyManager.executePanicMode()
                val allSuccess = results.all { it.success }
                
                if (allSuccess) {
                    Log.d(TAG, "Panic mode executed successfully")
                } else {
                    Log.w(TAG, "Some panic mode actions failed")
                }
                
                updateTileState()
            } catch (e: Exception) {
                Log.e(TAG, "Error executing panic mode", e)
                qsTile?.state = Tile.STATE_UNAVAILABLE
                qsTile?.updateTile()
            }
        }
    }
    
    private fun updateTileState() {
        serviceScope.launch {
            try {
                val tile = qsTile ?: return@launch

                tile.state = Tile.STATE_INACTIVE
                tile.label = "Panic Mode"
                tile.contentDescription = "Emergency privacy activation"

                tile.updateTile()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating tile state", e)
                qsTile?.state = Tile.STATE_UNAVAILABLE
                qsTile?.updateTile()
            }
        }
    }
}
