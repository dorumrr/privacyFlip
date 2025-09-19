package io.github.dorumrr.privacyflip.ui.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import io.github.dorumrr.privacyflip.R
import io.github.dorumrr.privacyflip.databinding.FragmentMainBinding
import io.github.dorumrr.privacyflip.ui.viewmodel.MainViewModel
import io.github.dorumrr.privacyflip.ui.viewmodel.UiState

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    // Flag to prevent infinite loops when updating switches programmatically
    private var isUpdatingUI = false

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

    private fun setupUI() {
        // Error card dismiss button
        binding.dismissErrorButton.setOnClickListener {
            binding.errorCard.visibility = View.GONE
        }

        // Panic mode button
        binding.panicModeButton.setOnClickListener {
            viewModel.triggerPanicMode()
        }

        // Setup click listeners for footer elements
        binding.creditsFooter.viewLogsButton.setOnClickListener {
            findNavController().navigate(R.id.action_main_to_logs)
        }

        binding.creditsFooter.createdByText.setOnClickListener {
            openGitHubRepository()
        }

        binding.creditsFooterNoRoot.createdByText.setOnClickListener {
            openGitHubRepository()
        }

        // Setup privacy feature cards
        setupScreenLockCard()
        setupTimerCard()
        setupServiceCard()
        setupGlobalPrivacyCard()
        setupSystemRequirementsCard()
    }

    private fun setupScreenLockCard() {
        with(binding.screenLockCard) {
            // Setup privacy features using DRY helper
            setupPrivacyFeature(
                wifiSettings,
                R.drawable.ic_wifi,
                "Wi-Fi",
                { viewModel.updateWifiSettings(disableOnLock = it) },
                { viewModel.updateWifiSettings(enableOnUnlock = it) }
            )

            setupPrivacyFeature(
                bluetoothSettings,
                R.drawable.ic_bluetooth,
                "Bluetooth",
                { viewModel.updateBluetoothSettings(disableOnLock = it) },
                { viewModel.updateBluetoothSettings(enableOnUnlock = it) }
            )

            setupPrivacyFeature(
                mobileDataSettings,
                R.drawable.ic_signal_cellular,
                "Mobile Data",
                { viewModel.updateMobileDataSettings(disableOnLock = it) },
                { viewModel.updateMobileDataSettings(enableOnUnlock = it) }
            )

            setupPrivacyFeature(
                locationSettings,
                R.drawable.ic_location,
                "Location",
                { viewModel.updateLocationSettings(disableOnLock = it) },
                { viewModel.updateLocationSettings(enableOnUnlock = it) }
            )
        }
    }

    private fun setupGlobalPrivacyCard() {
        binding.globalPrivacyCard.globalPrivacySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUI) {
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

            // Background service permission button
            grantBackgroundServiceButton.setOnClickListener {
                requestBackgroundServicePermission()
            }

            // Battery optimization button
            batteryOptimizationButton.setOnClickListener {
                openBatteryOptimizationSettings()
            }
        }
    }





    private fun setupServiceCard() {
        binding.serviceCard.backgroundServiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleBackgroundService(isChecked)
        }
    }

    private fun setupTimerCard() {
        with(binding.timerCard) {
            // Setup Lock Delay SeekBar
            lockDelaySeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !isUpdatingUI) {
                        lockDelayValue.text = progress.toString()
                        val currentSettings = viewModel.uiState.value?.timerSettings ?: return
                        val newSettings = currentSettings.copy(lockDelaySeconds = progress)
                        viewModel.updateTimerSettings(newSettings)
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })

            // Setup Unlock Delay SeekBar
            unlockDelaySeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !isUpdatingUI) {
                        unlockDelayValue.text = progress.toString()
                        val currentSettings = viewModel.uiState.value?.timerSettings ?: return
                        val newSettings = currentSettings.copy(unlockDelaySeconds = progress)
                        viewModel.updateTimerSettings(newSettings)
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
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
        
        // Show/hide content based on root status
        if (uiState.isRootGranted) {
            // Root granted - show Global Privacy card and main content, hide system requirements
            binding.globalPrivacyCard.root.visibility = View.VISIBLE
            binding.mainContentContainer.visibility = View.VISIBLE
            binding.systemRequirementsCard.root.visibility = View.GONE
            binding.creditsFooter.root.visibility = View.VISIBLE
            binding.creditsFooterNoRoot.root.visibility = View.GONE
        } else {
            // Root not granted - hide Global Privacy card and main content, show System Requirements
            binding.globalPrivacyCard.root.visibility = View.GONE
            binding.mainContentContainer.visibility = View.GONE
            binding.systemRequirementsCard.root.visibility = View.VISIBLE
            binding.creditsFooter.root.visibility = View.GONE
            binding.creditsFooterNoRoot.root.visibility = View.VISIBLE
        }


        
        // Update error message
        updateErrorMessage(uiState)
        
        // Update privacy settings
        updatePrivacySettings(uiState)

        // Update timer settings
        updateTimerSettings(uiState)

        // Update card states
        updateGlobalPrivacyCard(uiState)
        updateSystemRequirementsCard(uiState)
        updateServiceCard(uiState)
    }



    private fun updateErrorMessage(uiState: UiState) {
        if (uiState.errorMessage != null) {
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = uiState.errorMessage
        } else {
            binding.errorCard.visibility = View.GONE
        }
    }

    private fun updatePrivacySettings(uiState: UiState) {
        // Set flag to prevent infinite loops
        isUpdatingUI = true

        // Update Wi-Fi settings
        with(binding.screenLockCard.wifiSettings) {
            disableOnLockSwitch.isChecked = uiState.screenLockConfig.wifiDisableOnLock
            enableOnUnlockSwitch.isChecked = uiState.screenLockConfig.wifiEnableOnUnlock
        }

        // Update Bluetooth settings
        with(binding.screenLockCard.bluetoothSettings) {
            disableOnLockSwitch.isChecked = uiState.screenLockConfig.bluetoothDisableOnLock
            enableOnUnlockSwitch.isChecked = uiState.screenLockConfig.bluetoothEnableOnUnlock
        }

        // Update Mobile Data settings
        with(binding.screenLockCard.mobileDataSettings) {
            disableOnLockSwitch.isChecked = uiState.screenLockConfig.mobileDataDisableOnLock
            enableOnUnlockSwitch.isChecked = uiState.screenLockConfig.mobileDataEnableOnUnlock
        }

        // Update Location settings
        with(binding.screenLockCard.locationSettings) {
            disableOnLockSwitch.isChecked = uiState.screenLockConfig.locationDisableOnLock
            enableOnUnlockSwitch.isChecked = uiState.screenLockConfig.locationEnableOnUnlock
        }

        // Clear flag
        isUpdatingUI = false
    }

    private fun updateTimerSettings(uiState: UiState) {
        // Set flag to prevent infinite loops
        isUpdatingUI = true

        with(binding.timerCard) {
            // Update Lock Delay
            lockDelaySeekBar.progress = uiState.timerSettings.lockDelaySeconds
            lockDelayValue.text = uiState.timerSettings.lockDelaySeconds.toString()

            // Update Unlock Delay
            unlockDelaySeekBar.progress = uiState.timerSettings.unlockDelaySeconds
            unlockDelayValue.text = uiState.timerSettings.unlockDelaySeconds.toString()
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

    private fun requestBackgroundServicePermission() {
        try {
            // This will trigger the background service permission dialog
            // The actual permission is handled automatically when the service starts
            viewModel.toggleBackgroundService(true)
            android.widget.Toast.makeText(requireContext(), "Background service permission requested", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Unable to request background service permission", android.widget.Toast.LENGTH_SHORT).show()
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

            // Update master switch
            globalPrivacySwitch.isChecked = uiState.isGlobalPrivacyEnabled

            // Clear flag
            isUpdatingUI = false

            // Update card appearance based on privacy status
            if (uiState.isGlobalPrivacyEnabled) {
                // Green - Protection Active
                globalPrivacyCardView.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.success_green)
                )
                globalPrivacyIcon.setImageResource(R.drawable.ic_check_circle)
                globalPrivacyTitle.text = "PrivacyFlip"
                globalPrivacyStatus.text = "Protection Active"
            } else {
                // Red - Protection Inactive
                globalPrivacyCardView.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.error_red)
                )
                globalPrivacyIcon.setImageResource(R.drawable.ic_error)
                globalPrivacyTitle.text = "PrivacyFlip"
                globalPrivacyStatus.text = "Protection Inactive"
            }
        }
    }

    private fun updateSystemRequirementsCard(uiState: UiState) {
        with(binding.systemRequirementsCard) {
            if (!uiState.isRootGranted) {
                // Root not granted - show prominent message and grant button
                systemRequirementsDescription.text = "Root access is required to control privacy features. Please grant root access to continue."

                rootStatusText.text = if (uiState.isRootAvailable) {
                    "Available - Grant Required"
                } else {
                    "Not Available"
                }
                rootStatusIcon.setImageResource(R.drawable.ic_error)

                // Always show root actions when root is not granted
                rootActionsContainer.visibility = View.VISIBLE

                // Hide background service section when root is the main issue
                backgroundServiceStatusText.text = "Requires root access first"
                backgroundServiceActionsContainer.visibility = View.GONE

            } else {
                // Root granted - show normal status
                systemRequirementsDescription.text = "System requirements status"

                rootStatusText.text = "Available & Granted"
                rootStatusIcon.setImageResource(R.drawable.ic_check_circle)

                // Hide root actions when granted
                rootActionsContainer.visibility = View.GONE

                // Update background service status
                backgroundServiceStatusText.text = if (uiState.backgroundServicePermissionGranted) {
                    "Permission granted"
                } else {
                    "Permission required"
                }
                backgroundServiceStatusIcon.setImageResource(
                    if (uiState.backgroundServicePermissionGranted) R.drawable.ic_check_circle else R.drawable.ic_error
                )

                // Show/hide background service actions
                backgroundServiceActionsContainer.visibility =
                    if (uiState.backgroundServicePermissionGranted) View.GONE else View.VISIBLE
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



    private fun updateServiceCard(uiState: UiState) {
        with(binding.serviceCard) {
            backgroundServiceSwitch.isChecked = uiState.backgroundServiceEnabled
            serviceStatusText.text = if (uiState.backgroundServiceEnabled) {
                "Service status: Running"
            } else {
                "Service status: Stopped"
            }
        }
    }

    // DRY Helper Functions
    private fun setupPrivacyFeature(
        featureBinding: io.github.dorumrr.privacyflip.databinding.PrivacyFeatureRowBinding,
        iconRes: Int,
        name: String,
        onDisableLockChange: (Boolean) -> Unit,
        onEnableUnlockChange: (Boolean) -> Unit
    ) {
        featureBinding.featureIcon.setImageResource(iconRes)
        featureBinding.featureName.text = name
        featureBinding.disableOnLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUI) onDisableLockChange(isChecked)
        }
        featureBinding.enableOnUnlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUI) onEnableUnlockChange(isChecked)
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
