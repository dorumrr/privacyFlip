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
abstract class BaseTileService : TileService() {
    
    protected abstract val tag: String
    protected abstract val serviceName: String
    
    protected lateinit var privacyManager: PrivacyManager
    protected val serviceScope = CoroutineScope(Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()
        privacyManager = PrivacyManager.getInstance(this)
        Log.d(tag, "$serviceName created")
    }
    
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }
    
    override fun onClick() {
        super.onClick()
        Log.d(tag, "$serviceName clicked")
        
        serviceScope.launch {
            try {
                executeAction()
                updateTileState()
            } catch (e: Exception) {
                Log.e(tag, "Error executing tile action", e)
                qsTile?.state = Tile.STATE_UNAVAILABLE
                qsTile?.updateTile()
            }
        }
    }
    
    protected abstract suspend fun executeAction()
    
    protected fun updateTileState() {
        serviceScope.launch {
            try {
                updateTileStateInternal()
            } catch (e: Exception) {
                Log.e(tag, "Error updating tile state", e)
                qsTile?.state = Tile.STATE_UNAVAILABLE
                qsTile?.updateTile()
            }
        }
    }
    
    protected abstract suspend fun updateTileStateInternal()
}
