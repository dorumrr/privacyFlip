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
import io.github.dorumrr.privacyflip.util.Constants

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

        } else {
            // Root not granted - hide Global Privacy card and main content, show System Requirements
            binding.globalPrivacyCard.root.visibility = View.GONE
            binding.mainContentContainer.visibility = View.GONE
            binding.systemRequirementsCard.root.visibility = View.VISIBLE

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
        updateBackgroundPermissionErrorCard(uiState)
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
                globalPrivacyTitle.text = "Privacy Flip"
                globalPrivacyStatus.text = "Protection Active"
            } else {
                // Red - Protection Inactive
                globalPrivacyCardView.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.error_red)
                )
                globalPrivacyIcon.setImageResource(R.drawable.ic_error)
                globalPrivacyTitle.text = "Privacy Flip"
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



            } else {
                // Root granted - show normal status
                systemRequirementsDescription.text = "System requirements status"

                rootStatusText.text = "Available & Granted"
                rootStatusIcon.setImageResource(R.drawable.ic_check_circle)

                // Hide root actions when granted
                rootActionsContainer.visibility = View.GONE
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
