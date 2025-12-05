package io.github.dorumrr.privacyflip.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.dorumrr.privacyflip.R
import io.github.dorumrr.privacyflip.databinding.ActivityLogViewerBinding
import io.github.dorumrr.privacyflip.util.DebugLogHelper

class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private lateinit var debugLogHelper: DebugLogHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        debugLogHelper = DebugLogHelper.getInstance(this)

        setupToolbar()
        setupButtons()
        loadLogs()
    }

    private fun setupToolbar() {
        binding.backButton.setOnClickListener {
            finish()
        }
    }

    private fun setupButtons() {
        binding.refreshButton.setOnClickListener {
            loadLogs()
            Toast.makeText(this, "Logs refreshed", Toast.LENGTH_SHORT).show()
        }

        binding.scrollBottomButton.setOnClickListener {
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }

        binding.shareButton.setOnClickListener {
            shareDebugLogs()
        }

        binding.clearButton.setOnClickListener {
            confirmClearLogs()
        }
    }

    private fun loadLogs() {
        if (!debugLogHelper.hasLogs()) {
            showEmptyState()
            return
        }

        try {
            val logFile = debugLogHelper.getLogFileForShare()
            val content = logFile.readText()
            
            binding.emptyStateText.visibility = View.GONE
            binding.logScrollView.visibility = View.VISIBLE
            binding.logContentText.text = content
            binding.logInfoText.text = "Log size: ${debugLogHelper.getLogSizeFormatted()} • Lines: ${content.lines().size}"
            
            // Auto-scroll to bottom
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        } catch (e: Exception) {
            binding.logContentText.text = "Error reading logs: ${e.message}"
        }
    }

    private fun showEmptyState() {
        binding.emptyStateText.visibility = View.VISIBLE
        binding.logScrollView.visibility = View.GONE
        binding.logInfoText.text = "No logs recorded yet"
    }

    private fun shareDebugLogs() {
        if (!debugLogHelper.hasLogs()) {
            Toast.makeText(this, R.string.no_logs_to_share, Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = debugLogHelper.createShareIntent()
        if (shareIntent != null) {
            startActivity(Intent.createChooser(shareIntent, "Share Debug Logs"))
        } else {
            Toast.makeText(this, "Failed to create share intent", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle("Clear Logs")
            .setMessage("Are you sure you want to clear all debug logs?")
            .setPositiveButton("Clear") { _, _ ->
                debugLogHelper.clearLogs()
                loadLogs()
                Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
