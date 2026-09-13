package io.github.dorumrr.privacyflip.tile

import android.annotation.TargetApi
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import io.github.dorumrr.privacyflip.privacy.PrivacyManager
import io.github.dorumrr.privacyflip.util.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@TargetApi(Build.VERSION_CODES.N)
abstract class BaseTileService : TileService() {
    
    protected abstract val tag: String
    protected abstract val serviceName: String
    
    protected lateinit var privacyManager: PrivacyManager
    protected val preferenceManager: PreferenceManager by lazy { PreferenceManager.getInstance(this) }
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

    // onDestroy, not onStopListening: the panel closing stops listening while this same instance
    // lives on and will be reused, and a cancelled scope never recovers - cancelling there would
    // leave the tile unable to update again.
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
    
    override fun onClick() {
        super.onClick()
        Log.d(tag, "$serviceName clicked")
        
        serviceScope.launch {
            try {
                executeAction()
                updateTileState()
            } catch (cancelled: CancellationException) {
                // Teardown cancelled this, so the tile is already gone: reporting an error on it
                // would touch a destroyed instance. CancellationException is an Exception, so
                // without this it would fall into the catch below.
                throw cancelled
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Error updating tile state", e)
                qsTile?.state = Tile.STATE_UNAVAILABLE
                qsTile?.updateTile()
            }
        }
    }
    
    protected abstract suspend fun updateTileStateInternal()
}
