package io.github.dorumrr.privacyflip.ui.fragment

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import io.github.dorumrr.privacyflip.BuildConfig
import io.github.dorumrr.privacyflip.R
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.data.TimerSettings
import io.github.dorumrr.privacyflip.databinding.FragmentMainBinding
import io.github.dorumrr.privacyflip.ui.viewmodel.MainViewModel
import io.github.dorumrr.privacyflip.ui.viewmodel.UiState
import io.github.dorumrr.privacyflip.util.Constants

class MainFragment : Fragment() {

    companion object {
        private const val TAG = "privacyFlip-MainFragment"
    }

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    // Flag to prevent infinite loops when updating switches programmatically
    private var isUpdatingUI = false

    // Permission launcher for notification permission (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "Notification permission granted")
            viewModel.setDebugNotificationsEnabled(true)
        } else {
            Log.w(TAG, "Notification permission denied")
            // Reset the switch since permission was denied
            // The ViewModel state update will trigger UI update via observer
            viewModel.setDebugNotificationsEnabled(false)
        }
    }

    // Broadcast receiver for Shizuku status changes
    private val shizukuStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Shizuku status changed - refreshing UI")
            viewModel.refresh()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() called - reloading screen lock configuration and privilege status")
        // Reload screen lock configuration from preferences when fragment resumes
        // This ensures the UI reflects any changes made by the worker (e.g., after lock/unlock)
        viewModel.reloadScreenLockConfig()

        // Refresh privilege status to detect if Shizuku/Root status changed while app was in background
        viewModel.refresh()

        // Register broadcast receiver for Shizuku status changes
        val filter = IntentFilter("io.github.dorumrr.privacyflip.SHIZUKU_STATUS_CHANGED")
        requireContext().registerReceiver(shizukuStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "Registered Shizuku status receiver")
    }

    override fun onPause() {
        super.onPause()
        // Unregister broadcast receiver
        try {
            requireContext().unregisterReceiver(shizukuStatusReceiver)
            Log.d(TAG, "Unregistered Shizuku status receiver")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver: ${e.message}")
        }
    }

    private fun setupUI() {
        // Setup click listeners for footer elements
        binding.creditsFooter.createdByText.setOnClickListener {
            openGitHubRepository()
        }

        binding.creditsFooter.donateButton.setOnClickListener {
            openDonateLink()
        }

        // Setup privacy feature cards
        setupScreenLockCard()
        setupTimerCard()
        setupBackgroundPermissionErrorCard()
        setupGlobalPrivacyCard()
        setupSystemRequirementsCard()
        setupPrivilegeErrorAlert()
    }

    private fun setupPrivilegeErrorAlert() {
        // No click listeners needed - this is a static alert card
    }

    private fun setupScreenLockCard() {
        with(binding.screenLockCard) {
            val featureConfigs = listOf(
                FeatureConfig(PrivacyFeature.WIFI, wifiSettings, R.drawable.ic_wifi, "Wi-Fi"),
                FeatureConfig(PrivacyFeature.BLUETOOTH, bluetoothSettings, R.drawable.ic_bluetooth, "Bluetooth"),
                FeatureConfig(PrivacyFeature.MOBILE_DATA, mobileDataSettings, R.drawable.ic_signal_cellular, "Mobile Data"),
                FeatureConfig(PrivacyFeature.LOCATION, locationSettings, R.drawable.ic_location, "Location"),
                FeatureConfig(PrivacyFeature.NFC, nfcSettings, R.drawable.ic_nfc, "NFC")
            )

            featureConfigs.forEach { config ->
                setupPrivacyFeature(
                    config.binding,
                    config.iconRes,
                    config.displayName,
                    config.feature,
                    { viewModel.updateFeatureSetting(config.feature, disableOnLock = it) },
                    { viewModel.updateFeatureSetting(config.feature, enableOnUnlock = it) }
                )
            }

            cameraDisableOnLockSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (!isUpdatingUI) {
                    viewModel.updateFeatureSetting(PrivacyFeature.CAMERA, disableOnLock = isChecked)
                }
            }
            cameraEnableOnUnlockSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (!isUpdatingUI) {
                    viewModel.updateFeatureSetting(PrivacyFeature.CAMERA, enableOnUnlock = isChecked)
                }
            }

            microphoneDisableOnLockSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (!isUpdatingUI) {
                    viewModel.updateFeatureSetting(PrivacyFeature.MICROPHONE, disableOnLock = isChecked)
                    // Show/hide the "only if unused" checkbox based on disable switch state
                    microphoneOnlyIfUnusedContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                }
            }
            microphoneEnableOnUnlockSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (!isUpdatingUI) {
                    viewModel.updateFeatureSetting(PrivacyFeature.MICROPHONE, enableOnUnlock = isChecked)
                }
            }
            microphoneOnlyIfUnusedCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (!isUpdatingUI) {
                    viewModel.updateFeatureOnlyIfUnused(PrivacyFeature.MICROPHONE, isChecked)
                }
            }

            cameraInfoIcon.setOnClickListener {
                showCameraMicInfoDialog()
            }
            microphoneInfoIcon.setOnClickListener {
                showCameraMicInfoDialog()
            }
        }
    }

    private fun setupGlobalPrivacyCard() {
        binding.globalPrivacyCard.globalPrivacySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUI) {
                // Toggling global privacy on/off just changes monitoring behavior.
                // It does NOT require root permission since it doesn't modify feature states.
                viewModel.toggleGlobalPrivacy(isChecked)
            }
        }
    }

    private fun setupSystemRequirementsCard() {
        with(binding.systemRequirementsCard) {
            // Root access button
            grantRootButton.setOnClickListener {
                viewModel.requestRootPermission()
            }



            // Battery optimization button
            batteryOptimizationButton.setOnClickListener {
                openBatteryOptimizationSettings()
            }
        }
    }





    private fun setupBackgroundPermissionErrorCard() {
        binding.backgroundPermissionErrorCard.grantBackgroundPermissionButton.setOnClickListener {
            requestBackgroundServicePermission()
        }
    }

    private fun setupTimerCard() {
        with(binding.timerCard) {
            // Setup Lock Delay SeekBar using DRY helper
            setupSeekBar(
                lockDelaySeekBar,
                lockDelayValue
            ) { position ->
                val currentSettings = viewModel.uiState.value?.timerSettings ?: return@setupSeekBar
                val seconds = TimerSettings.positionToSeconds(position)
                val newSettings = currentSettings.copy(lockDelaySeconds = seconds)
                viewModel.updateTimerSettings(newSettings)
            }

            // Setup Unlock Delay SeekBar using DRY helper
            setupSeekBar(
                unlockDelaySeekBar,
                unlockDelayValue
            ) { position ->
                val currentSettings = viewModel.uiState.value?.timerSettings ?: return@setupSeekBar
                val seconds = TimerSettings.positionToSeconds(position)
                val newSettings = currentSettings.copy(unlockDelaySeconds = seconds)
                viewModel.updateTimerSettings(newSettings)
            }

            // Setup Debug Notifications toggle
            debugNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (!isUpdatingUI) {
                    if (isChecked) {
                        // Check if we need to request notification permission (Android 13+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(
                                    requireContext(),
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                // Request permission - the result handler will enable the feature if granted
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@setOnCheckedChangeListener
                            }
                        }
                        // Permission already granted or not needed (Android < 13)
                        viewModel.setDebugNotificationsEnabled(true)
                    } else {
                        viewModel.setDebugNotificationsEnabled(false)
                    }
                }
            }
        }
    }



    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { uiState ->
            updateUI(uiState)
        }
    }

    private fun updateUI(uiState: UiState) {
        // Show/hide loading
        binding.loadingIndicator.visibility = if (uiState.isLoading) View.VISIBLE else View.GONE

        // CRITICAL: Hide ALL content while loading (during permission request)
        // Only show content after permission status is determined
        if (uiState.isLoading) {
            // During loading - hide everything, only show spinner
            binding.globalPrivacyCard.root.visibility = View.GONE
            binding.mainContentContainer.visibility = View.GONE
            binding.systemRequirementsCard.root.visibility = View.GONE
            binding.creditsFooter.root.visibility = View.GONE
        } else {
            // After loading - always show main UI (regardless of privilege status)
            binding.globalPrivacyCard.root.visibility = View.VISIBLE
            binding.mainContentContainer.visibility = View.VISIBLE

            // System Requirements card visibility: Hide when ALL requirements are met
            // Requirements: 1) Privilege granted, 2) Battery optimization disabled
            val batteryManager = io.github.dorumrr.privacyflip.util.BatteryOptimizationManager(requireContext())
            val isBatteryOptimizationDisabled = batteryManager.isIgnoringBatteryOptimizations()
            val shouldShowSystemRequirements = !uiState.isRootGranted || !isBatteryOptimizationDisabled
            binding.systemRequirementsCard.root.visibility = if (shouldShowSystemRequirements) View.VISIBLE else View.GONE

            // Always show footer after loading completes
            binding.creditsFooter.root.visibility = View.VISIBLE
        }



        // Update privacy settings
        updatePrivacySettings(uiState)

        // Update timer settings
        updateTimerSettings(uiState)

        // Update card states
        updateGlobalPrivacyCard(uiState)
        updateSystemRequirementsCard(uiState)
        updateBackgroundPermissionErrorCard(uiState)
        updatePrivilegeErrorAlert(uiState)

        // Update interactive elements state (enable/disable based on privilege)
        updateInteractiveElementsState(uiState)
    }

    private fun updatePrivilegeErrorAlert(@Suppress("UNUSED_PARAMETER") uiState: UiState) {
        // Alert removed - System Requirements card now shows all privilege status information
        // This eliminates redundant UI elements and reduces visual clutter
        binding.privilegeErrorAlert.root.visibility = View.GONE
    }

    private fun updatePrivacySettings(uiState: UiState) {
        // Set flag to prevent infinite loops
        isUpdatingUI = true

        // Update WiFi and Bluetooth with "only if unused" support
        updatePrivacyFeatureSetting(
            binding.screenLockCard.wifiSettings,
            uiState.screenLockConfig.wifiDisableOnLock,
            uiState.screenLockConfig.wifiEnableOnUnlock,
            uiState.screenLockConfig.wifiOnlyIfUnused,
            showOnlyIfUnused = true
        )

        updatePrivacyFeatureSetting(
            binding.screenLockCard.bluetoothSettings,
            uiState.screenLockConfig.bluetoothDisableOnLock,
            uiState.screenLockConfig.bluetoothEnableOnUnlock,
            uiState.screenLockConfig.bluetoothOnlyIfUnused,
            showOnlyIfUnused = true
        )

        // Mobile Data, Location, NFC - hide "only if unused" checkbox (detection not supported)
        updatePrivacyFeatureSetting(
            binding.screenLockCard.mobileDataSettings,
            uiState.screenLockConfig.mobileDataDisableOnLock,
            uiState.screenLockConfig.mobileDataEnableOnUnlock,
            onlyIfUnused = false,
            showOnlyIfUnused = false
        )

        updatePrivacyFeatureSetting(
            binding.screenLockCard.locationSettings,
            uiState.screenLockConfig.locationDisableOnLock,
            uiState.screenLockConfig.locationEnableOnUnlock,
            onlyIfUnused = false,
            showOnlyIfUnused = false
        )

        updatePrivacyFeatureSetting(
            binding.screenLockCard.nfcSettings,
            uiState.screenLockConfig.nfcDisableOnLock,
            uiState.screenLockConfig.nfcEnableOnUnlock,
            onlyIfUnused = false,
            showOnlyIfUnused = false
        )

        binding.screenLockCard.cameraDisableOnLockSwitch.isChecked = uiState.screenLockConfig.cameraDisableOnLock
        binding.screenLockCard.cameraEnableOnUnlockSwitch.isChecked = uiState.screenLockConfig.cameraEnableOnUnlock

        // Microphone with "only if unused" support
        binding.screenLockCard.microphoneDisableOnLockSwitch.isChecked = uiState.screenLockConfig.microphoneDisableOnLock
        binding.screenLockCard.microphoneEnableOnUnlockSwitch.isChecked = uiState.screenLockConfig.microphoneEnableOnUnlock
        binding.screenLockCard.microphoneOnlyIfUnusedCheckbox.isChecked = uiState.screenLockConfig.microphoneOnlyIfUnused
        binding.screenLockCard.microphoneOnlyIfUnusedContainer.visibility = 
            if (uiState.screenLockConfig.microphoneDisableOnLock) View.VISIBLE else View.GONE

        isUpdatingUI = false
    }

    private fun updateTimerSettings(uiState: UiState) {
        // Set flag to prevent infinite loops
        isUpdatingUI = true

        with(binding.timerCard) {
            // Update Lock Delay
            val lockPosition = TimerSettings.secondsToPosition(uiState.timerSettings.lockDelaySeconds)
            lockDelaySeekBar.progress = lockPosition
            lockDelayValue.text = TimerSettings.formatSeconds(uiState.timerSettings.lockDelaySeconds)

            // Update Unlock Delay
            val unlockPosition = TimerSettings.secondsToPosition(uiState.timerSettings.unlockDelaySeconds)
            unlockDelaySeekBar.progress = unlockPosition
            unlockDelayValue.text = TimerSettings.formatSeconds(uiState.timerSettings.unlockDelaySeconds)

            // Update Debug Notifications toggle
            debugNotificationsSwitch.isChecked = uiState.debugNotificationsEnabled
        }

        // Clear flag
        isUpdatingUI = false
    }

    private fun openGitHubRepository() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://github.com/dorumrr/privacyFlip")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Handle case where no browser is available
            android.widget.Toast.makeText(requireContext(), "Unable to open GitHub repository", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDonateLink() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(Constants.UI.DONATE_URL)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Handle case where no browser is available
            android.widget.Toast.makeText(requireContext(), "Unable to open donation page", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCameraMicInfoDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Camera & Microphone Info")
            .setMessage(getString(R.string.lock_delay_warning_message))
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun requestBackgroundServicePermission() {
        try {
            // Open Android settings for background app restrictions
            val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
            android.widget.Toast.makeText(requireContext(), "Please allow Privacy Flip to run in background", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Unable to open background settings", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = android.content.Intent().apply {
                action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = android.net.Uri.parse("package:${requireContext().packageName}")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general battery optimization settings
            try {
                val fallbackIntent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallbackIntent)
            } catch (e2: Exception) {
                android.widget.Toast.makeText(requireContext(), "Unable to open battery settings", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateGlobalPrivacyCard(uiState: UiState) {
        with(binding.globalPrivacyCard) {
            // Set flag to prevent infinite loops
            isUpdatingUI = true

            // Force switch OFF when:
            // 1. No privilege method available (NONE)
            // 2. Privilege method available but permission not granted
            val switchState = if (uiState.privilegeMethod == io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.NONE || !uiState.isRootGranted) {
                false
            } else {
                uiState.isGlobalPrivacyEnabled
            }
            globalPrivacySwitch.isChecked = switchState

            // Clear flag
            isUpdatingUI = false

            // Show green only when enabled, normal card style when disabled
            if (switchState) {
                // Green - Protection Active
                globalPrivacyCardView.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.success_green)
                )
                globalPrivacyIcon.setImageResource(R.drawable.app_icon)
                globalPrivacyIcon.clearColorFilter()
                globalPrivacyTitle.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.icon_white)
                )
                globalPrivacyTitle.text = getAppTitleWithVersion()
                globalPrivacyStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.icon_white)
                )
                globalPrivacyStatus.text = "Protection Active"

                // Switch colors: Blue when ON
                globalPrivacySwitch.thumbTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.trust_blue)
                )
                globalPrivacySwitch.trackTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.trust_blue_light)
                )
            } else {
                // Normal card style - Protection Inactive
                globalPrivacyCardView.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.background_card)
                )
                globalPrivacyIcon.setImageResource(R.drawable.app_icon)
                globalPrivacyIcon.clearColorFilter()
                globalPrivacyTitle.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary)
                )
                globalPrivacyTitle.text = getAppTitleWithVersion()
                globalPrivacyStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary)
                )
                globalPrivacyStatus.text = "Protection Inactive"

                // Switch colors: Red when OFF
                globalPrivacySwitch.thumbTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.error_red)
                )
                globalPrivacySwitch.trackTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.error_red)
                )
            }
        }
    }

    private fun updateSystemRequirementsCard(uiState: UiState) {
        Log.d(TAG, "========== updateSystemRequirementsCard() ==========")
        Log.d(TAG, "updateSystemRequirementsCard() - isRootGranted: ${uiState.isRootGranted}")
        Log.d(TAG, "updateSystemRequirementsCard() - isRootAvailable: ${uiState.isRootAvailable}")
        Log.d(TAG, "updateSystemRequirementsCard() - privilegeMethod: ${uiState.privilegeMethod}")

        // Check if battery optimization is disabled
        val batteryManager = io.github.dorumrr.privacyflip.util.BatteryOptimizationManager(requireContext())
        val isBatteryOptimizationDisabled = batteryManager.isIgnoringBatteryOptimizations()
        Log.d(TAG, "updateSystemRequirementsCard() - isBatteryOptimizationDisabled: $isBatteryOptimizationDisabled")

        // Hide entire card if both root is granted AND battery optimization is disabled
        if (uiState.isRootGranted && isBatteryOptimizationDisabled) {
            Log.d(TAG, "updateSystemRequirementsCard() - ✅ All requirements met, hiding entire card")
            binding.systemRequirementsCard.root.visibility = View.GONE
            return
        }

        // Show card if any requirement is not met
        Log.d(TAG, "updateSystemRequirementsCard() - ⚠️ Requirements not met, showing card (root=${uiState.isRootGranted}, battery=$isBatteryOptimizationDisabled)")
        binding.systemRequirementsCard.root.visibility = View.VISIBLE

        with(binding.systemRequirementsCard) {
            // Update privileged access section
            val privilegeMethod = uiState.privilegeMethod

            if (!uiState.isRootGranted) {
                Log.d(TAG, "updateSystemRequirementsCard() - Root NOT granted, showing grant UI")
                // Privilege not granted
                rootStatusText.text = if (uiState.isRootAvailable) {
                    when (privilegeMethod) {
                        io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.SHIZUKU -> "Shizuku Available"
                        io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.ROOT -> "Root Available"
                        io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.SUI -> "Sui Available"
                        else -> "Available - Grant Required"
                    }
                } else {
                    "Not Available/Started"
                }

                // Update description text based on privilege method
                privilegeAccessDescription.text = when (privilegeMethod) {
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.SHIZUKU ->
                        "Click 'Grant Shizuku Permission' to try again or uninstall and reinstall the app, then grant permission at first start."
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.ROOT ->
                        "To properly grant root access, please uninstall and reinstall the app, then grant permission when prompted."
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.SUI ->
                        "Sui permission required. Please grant permission when prompted."
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.NONE ->
                        "Privacy Flip requires Shizuku (for non-rooted devices) or root access (via Magisk or similar) to control privacy features."
                }

                // Show/hide button based on privilege method
                // ROOT method: Hide button (Magisk doesn't allow re-requesting), show instructions only
                // SHIZUKU/SUI: Show button (can re-request permission)
                // NONE: Show button (to open Shizuku install page)
                when (privilegeMethod) {
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.ROOT -> {
                        // Hide button for ROOT - Magisk doesn't allow re-requesting
                        rootActionsContainer.visibility = View.GONE
                    }
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.SHIZUKU,
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.SUI,
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.NONE -> {
                        // Show button for Shizuku/Sui/None
                        rootActionsContainer.visibility = View.VISIBLE

                        grantRootButton.text = when (privilegeMethod) {
                            io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.SHIZUKU -> "GRANT SHIZUKU PERMISSION"
                            io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.SUI -> "GRANT SUI PERMISSION"
                            io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.NONE -> "INSTALL SHIZUKU OR ROOT DEVICE"
                            else -> "GRANT PERMISSION"
                        }

                        // Disable button if no privilege method is available
                        grantRootButton.isEnabled = privilegeMethod != io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.NONE
                        grantRootButton.alpha = if (privilegeMethod != io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.NONE) 1.0f else 0.5f
                    }
                }

            } else {
                // Privilege granted
                Log.d(TAG, "updateSystemRequirementsCard() - Root IS granted, hiding grant UI")
                rootStatusText.text = when (privilegeMethod) {
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.SHIZUKU -> "Shizuku Granted"
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.ROOT -> "Root Granted"
                    io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.SUI -> "Sui Granted"
                    else -> "Granted"
                }

                // Hide root actions when granted
                rootActionsContainer.visibility = View.GONE
                Log.d(TAG, "updateSystemRequirementsCard() - Set rootActionsContainer.visibility = GONE")
            }

            // Update battery optimization section
            if (isBatteryOptimizationDisabled) {
                batteryStatusText.text = "Optimization disabled"
                batteryActionsContainer.visibility = View.GONE
            } else {
                batteryStatusText.text = "Optimization enabled"
                batteryActionsContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun updateFeatureStatusIndicator(
        icon: android.widget.ImageView,
        text: android.widget.TextView,
        featureState: io.github.dorumrr.privacyflip.data.FeatureState?
    ) {
        val isEnabled = featureState == io.github.dorumrr.privacyflip.data.FeatureState.ENABLED
        text.text = if (isEnabled) "ON" else "OFF"

        val colorRes = if (isEnabled) {
            R.color.trust_blue
        } else {
            R.color.text_secondary
        }

        val color = androidx.core.content.ContextCompat.getColor(requireContext(), colorRes)
        icon.setColorFilter(color)
        text.setTextColor(color)
    }



    private fun updateBackgroundPermissionErrorCard(uiState: UiState) {
        // Show error card only when root is granted but background service permission is missing
        val shouldShowError = uiState.isRootGranted && !uiState.backgroundServicePermissionGranted
        binding.backgroundPermissionErrorCard.root.visibility = if (shouldShowError) View.VISIBLE else View.GONE
    }

    private fun updateInteractiveElementsState(uiState: UiState) {
        // Enable only when privilege method is available AND permission is granted
        val isEnabled = uiState.privilegeMethod != io.github.dorumrr.privacyflip.privilege.PrivilegeMethod.NONE && uiState.isRootGranted

        // Global privacy switch
        binding.globalPrivacyCard.globalPrivacySwitch.isEnabled = isEnabled

        // All feature switches (5 standard features using <include>)
        with(binding.screenLockCard) {
            wifiSettings.disableOnLockSwitch.isEnabled = isEnabled
            wifiSettings.enableOnUnlockSwitch.isEnabled = isEnabled
            bluetoothSettings.disableOnLockSwitch.isEnabled = isEnabled
            bluetoothSettings.enableOnUnlockSwitch.isEnabled = isEnabled
            mobileDataSettings.disableOnLockSwitch.isEnabled = isEnabled
            mobileDataSettings.enableOnUnlockSwitch.isEnabled = isEnabled
            locationSettings.disableOnLockSwitch.isEnabled = isEnabled
            locationSettings.enableOnUnlockSwitch.isEnabled = isEnabled
            nfcSettings.disableOnLockSwitch.isEnabled = isEnabled
            nfcSettings.enableOnUnlockSwitch.isEnabled = isEnabled

            // Camera and microphone (custom inline layouts)
            cameraDisableOnLockSwitch.isEnabled = isEnabled
            cameraEnableOnUnlockSwitch.isEnabled = isEnabled
            microphoneDisableOnLockSwitch.isEnabled = isEnabled
            microphoneEnableOnUnlockSwitch.isEnabled = isEnabled
        }

        // Timer seekbars
        with(binding.timerCard) {
            lockDelaySeekBar.isEnabled = isEnabled
            unlockDelaySeekBar.isEnabled = isEnabled
        }
    }



    // DRY Helper Functions
    private fun setupPrivacyFeature(
        featureBinding: io.github.dorumrr.privacyflip.databinding.PrivacyFeatureRowBinding,
        iconRes: Int,
        name: String,
        feature: PrivacyFeature,
        onDisableLockChange: (Boolean) -> Unit,
        onEnableUnlockChange: (Boolean) -> Unit
    ) {
        // Check if this feature supports "only if unused" detection
        val supportsOnlyIfUnused = feature == PrivacyFeature.WIFI || feature == PrivacyFeature.BLUETOOTH
        
        featureBinding.featureIcon.setImageResource(iconRes)
        featureBinding.featureName.text = name
        featureBinding.disableOnLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUI) {
                onDisableLockChange(isChecked)
                // Show/hide the "only if unused" checkbox based on disable switch state
                // Only for features that support connection detection
                if (supportsOnlyIfUnused) {
                    featureBinding.onlyIfUnusedContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                }
            }
        }
        featureBinding.enableOnUnlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUI) onEnableUnlockChange(isChecked)
        }
        featureBinding.onlyIfUnusedCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUI && supportsOnlyIfUnused) {
                viewModel.updateFeatureOnlyIfUnused(feature, isChecked)
            }
        }
        
        // Hide the checkbox container for features that don't support detection
        if (!supportsOnlyIfUnused) {
            featureBinding.onlyIfUnusedContainer.visibility = View.GONE
        }
    }

    private fun updatePrivacyFeatureSetting(
        featureBinding: io.github.dorumrr.privacyflip.databinding.PrivacyFeatureRowBinding,
        disableOnLock: Boolean,
        enableOnUnlock: Boolean,
        onlyIfUnused: Boolean = false,
        showOnlyIfUnused: Boolean = true
    ) {
        featureBinding.disableOnLockSwitch.isChecked = disableOnLock
        featureBinding.enableOnUnlockSwitch.isChecked = enableOnUnlock
        featureBinding.onlyIfUnusedCheckbox.isChecked = onlyIfUnused
        // Show/hide the "only if unused" container based on disableOnLock state
        // But only if this feature supports the option
        featureBinding.onlyIfUnusedContainer.visibility = 
            if (showOnlyIfUnused && disableOnLock) View.VISIBLE else View.GONE
    }

    private fun setupSeekBar(
        seekBar: android.widget.SeekBar,
        valueText: android.widget.TextView,
        onProgressChanged: (Int) -> Unit
    ) {
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, position: Int, fromUser: Boolean) {
                if (fromUser && !isUpdatingUI) {
                    val seconds = TimerSettings.positionToSeconds(position)
                    valueText.text = TimerSettings.formatSeconds(seconds)
                    onProgressChanged(position)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    /**
     * Creates a formatted title with "Privacy Flip" in bold and version in regular text.
     * Example: "Privacy Flip v1.4.2" where "Privacy Flip" is bold and version is 75% size.
     */
    private fun getAppTitleWithVersion(): SpannableString {
        val appName = "Privacy Flip"
        val version = " v${BuildConfig.VERSION_NAME}"
        val fullText = appName + version

        val spannable = SpannableString(fullText)

        // Make only "Privacy Flip" bold (not the version)
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            appName.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Make version text 75% of the normal size (25% smaller)
        spannable.setSpan(
            RelativeSizeSpan(0.75f),
            appName.length,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        return spannable
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// Data class for DRY feature configuration
private data class FeatureConfig(
    val feature: PrivacyFeature,
    val binding: io.github.dorumrr.privacyflip.databinding.PrivacyFeatureRowBinding,
    val iconRes: Int,
    val displayName: String
)
