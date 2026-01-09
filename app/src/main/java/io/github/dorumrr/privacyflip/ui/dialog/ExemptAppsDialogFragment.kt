package io.github.dorumrr.privacyflip.ui.dialog

import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import io.github.dorumrr.privacyflip.R
import io.github.dorumrr.privacyflip.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog to manage app exemptions.
 * Shows list of installed apps with checkboxes to exempt them from privacy toggles.
 */
class ExemptAppsDialogFragment : DialogFragment() {

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var appListContainer: LinearLayout
    private lateinit var appListScroll: View
    private lateinit var loadingView: View
    private lateinit var permissionWarning: View
    private lateinit var emptyState: View

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: android.graphics.drawable.Drawable
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        preferenceManager = PreferenceManager.getInstance(requireContext())

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_exempt_apps, null)
        appListContainer = view.findViewById(R.id.app_list_container)
        appListScroll = view.findViewById(R.id.app_list_scroll)
        loadingView = view.findViewById(R.id.loading_view)
        permissionWarning = view.findViewById(R.id.permission_warning)
        emptyState = view.findViewById(R.id.empty_state)

        // Setup permission warning button
        view.findViewById<View>(R.id.grant_permission_button).setOnClickListener {
            openUsageAccessSettings()
        }

        // Load apps in background
        loadApps()

        return AlertDialog.Builder(requireContext())
            .setTitle("Exempt Apps")
            .setView(view)
            .setPositiveButton("Done") { _, _ -> dismiss() }
            .create()
    }

    private fun loadApps() {
        CoroutineScope(Dispatchers.Main).launch {
            loadingView.visibility = View.VISIBLE
            appListScroll.visibility = View.GONE
            emptyState.visibility = View.GONE

            val apps = withContext(Dispatchers.IO) {
                getInstalledApps()
            }

            android.util.Log.d(TAG, "Loaded ${apps.size} apps")

            displayApps(apps)

            loadingView.visibility = View.GONE

            if (apps.isEmpty()) {
                emptyState.visibility = View.VISIBLE
                appListScroll.visibility = View.GONE
            } else {
                emptyState.visibility = View.GONE
                appListScroll.visibility = View.VISIBLE
            }
        }
    }

    private fun getInstalledApps(): List<AppInfo> {
        val pm = requireContext().packageManager
        val ourPackageName = requireContext().packageName

        // Get all installed packages (including work profile apps)
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        android.util.Log.d(TAG, "Total installed packages: ${packages.size}")

        // List of common system packages to exclude
        val systemPackagePrefixes = listOf(
            "com.android.",
            "com.google.android.",
            "android",
            "com.qualcomm.",
            "org.codeaurora.",
            "com.qti.",
            "com.sec.android.",
            "com.samsung.android."
        )

        val apps = packages
            .filter { appInfo ->
                // Exclude PrivacyFlip itself
                if (appInfo.packageName == ourPackageName) return@filter false

                // Exclude common system packages
                val isSystemPackage = systemPackagePrefixes.any {
                    appInfo.packageName.startsWith(it)
                }

                !isSystemPackage
            }
            .mapNotNull { appInfo ->
                try {
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = appInfo.loadLabel(pm).toString(),
                        icon = appInfo.loadIcon(pm)
                    )
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error loading app ${appInfo.packageName}", e)
                    null
                }
            }
            .sortedBy { it.appName.lowercase() }

        android.util.Log.d(TAG, "Filtered to ${apps.size} user apps")

        return apps
    }

    private fun displayApps(apps: List<AppInfo>) {
        appListContainer.removeAllViews()
        val exemptApps = preferenceManager.getExemptApps()

        android.util.Log.d(TAG, "Displaying ${apps.size} apps, exempt apps: ${exemptApps.size}")

        apps.forEach { app ->
            val itemView = LayoutInflater.from(context).inflate(
                R.layout.item_exempt_app,
                appListContainer,
                false
            )

            val icon = itemView.findViewById<ImageView>(R.id.app_icon)
            val name = itemView.findViewById<TextView>(R.id.app_name)
            val packageName = itemView.findViewById<TextView>(R.id.app_package)
            val checkbox = itemView.findViewById<CheckBox>(R.id.app_checkbox)

            icon.setImageDrawable(app.icon)
            name.text = app.appName
            packageName.text = app.packageName
            checkbox.isChecked = exemptApps.contains(app.packageName)

            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    preferenceManager.addExemptApp(app.packageName)
                } else {
                    preferenceManager.removeExemptApp(app.packageName)
                }
            }

            itemView.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
            }

            appListContainer.addView(itemView)
        }
    }

    private fun openUsageAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general settings
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    companion object {
        const val TAG = "ExemptAppsDialog"
    }
}

