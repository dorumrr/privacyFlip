package io.github.dorumrr.privacyflip.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class PermissionChecker(private val context: Context) {

    companion object {
        private const val TAG = "privacyFlip-PermissionChecker"
    }

    data class PermissionStatus(
        val permission: String,
        val displayName: String,
        val isGranted: Boolean,
        val isRequired: Boolean,
        val description: String
    )

    fun getAllPermissionStatus(): List<PermissionStatus> {
        Log.d(TAG, "App relies on root access - no runtime permissions needed")
        return emptyList()
    }

    fun getUngrantedPermissions(): List<PermissionStatus> {
        return emptyList()
    }

    fun getRequiredUngrantedPermissions(): List<PermissionStatus> {
        return emptyList()
    }
    
    private fun isPermissionGranted(permission: String): Boolean {
        val result = ContextCompat.checkSelfPermission(context, permission)
        val isGranted = result == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "isPermissionGranted($permission): result=$result, isGranted=$isGranted")
        return isGranted
    }

    fun areAllRequiredPermissionsGranted(): Boolean {
        return getRequiredUngrantedPermissions().isEmpty()
    }

    fun getPermissionsToRequest(): Array<String> {
        return getUngrantedPermissions().map { it.permission }.toTypedArray()
    }
}
