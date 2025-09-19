package io.github.dorumrr.privacyflip.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import io.github.dorumrr.privacyflip.databinding.FragmentLogViewerBinding
import io.github.dorumrr.privacyflip.ui.viewmodel.MainViewModel

class LogViewerFragment : Fragment() {

    private var _binding: FragmentLogViewerBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupUI()
        observeViewModel()
        
        // Load logs when fragment is created
        viewModel.showLogViewer()
    }

    private fun setupUI() {
        binding.refreshLogsButton.setOnClickListener {
            viewModel.showLogViewer()
        }

        binding.shareLogsButton.setOnClickListener {
            shareLogs()
        }

        binding.clearLogsButton.setOnClickListener {
            viewModel.clearLogs()
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { uiState ->
            binding.logText.text = if (uiState.logs.isNotEmpty()) {
                uiState.logs
            } else {
                "No logs available"
            }
        }
    }

    private fun shareLogs() {
        val currentState = viewModel.uiState.value
        val logsText = currentState?.logs ?: "No logs available"

        if (logsText.isBlank() || logsText == "No logs available") {
            android.widget.Toast.makeText(requireContext(), "No logs to share", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val shareIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, logsText)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "PrivacyFlip App Logs")
            }

            val chooserIntent = android.content.Intent.createChooser(shareIntent, "Share logs via...")
            startActivity(chooserIntent)

        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Unable to share logs", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
