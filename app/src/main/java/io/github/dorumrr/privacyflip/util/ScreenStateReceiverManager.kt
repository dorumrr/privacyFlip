package io.github.dorumrr.privacyflip.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.dorumrr.privacyflip.receiver.ScreenStateReceiver

object ScreenStateReceiverManager {
    
    fun registerReceiver(context: Context, tag: String): ScreenStateReceiver? {
        return try {
            val receiver = ScreenStateReceiver()
            
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            
            context.registerReceiver(receiver, filter)
            Log.i(tag, "✅ Screen state receiver registered successfully")
            
            receiver
        } catch (e: Exception) {
            Log.e(tag, "❌ Failed to register screen state receiver", e)
            null
        }
    }
    
    fun unregisterReceiver(context: Context, receiver: ScreenStateReceiver?, tag: String) {
        try {
            receiver?.let {
                context.unregisterReceiver(it)
                Log.i(tag, "✅ Screen state receiver unregistered successfully")
            }
        } catch (e: Exception) {
            Log.e(tag, "❌ Failed to unregister screen state receiver", e)
        }
    }
}
