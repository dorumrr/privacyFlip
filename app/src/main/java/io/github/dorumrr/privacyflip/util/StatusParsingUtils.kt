package io.github.dorumrr.privacyflip.util

import io.github.dorumrr.privacyflip.data.FeatureState

object StatusParsingUtils {
    
    /**
     * Standard parsing logic for feature status output
     * Handles common patterns across different privacy features
     */
    fun parseStandardOutput(output: String): FeatureState {
        val normalizedOutput = output.lowercase().trim()
        
        return when {
            // Enabled patterns
            normalizedOutput.contains("1") || 
            normalizedOutput.contains("2") || 
            normalizedOutput.contains("enabled") || 
            normalizedOutput.contains("on") ||
            normalizedOutput.contains("connected") -> {
                FeatureState.ENABLED
            }
            
            // Disabled patterns
            normalizedOutput.contains("0") || 
            normalizedOutput.contains("disabled") || 
            normalizedOutput.contains("off") ||
            normalizedOutput.contains("disconnected") -> {
                FeatureState.DISABLED
            }
            
            // Unknown/empty patterns
            normalizedOutput.isEmpty() || 
            normalizedOutput.contains("null") -> {
                FeatureState.UNKNOWN
            }
            
            else -> {
                FeatureState.UNKNOWN
            }
        }
    }
    
    /**
     * Location-specific parsing with additional patterns
     */
    fun parseLocationOutput(output: String): FeatureState {
        val normalizedOutput = output.lowercase().trim()

        return when {
            // Location enabled patterns
            normalizedOutput.contains("3") ||
            normalizedOutput.contains("gps") ||
            normalizedOutput.contains("network") -> {
                FeatureState.ENABLED
            }

            // Location disabled patterns
            normalizedOutput.contains("0") ||
            normalizedOutput.isEmpty() ||
            normalizedOutput.contains("null") -> {
                FeatureState.DISABLED
            }

            else -> FeatureState.UNKNOWN
        }
    }

    /**
     * Parse sensor privacy output from dumpsys sensor_privacy
     *
     * Expected format:
     * sensor=1 (microphone) or sensor=2 (camera)
     * state_type=1 (privacy enabled/blocked) or state_type=2 (privacy disabled/allowed)
     *
     * @param output The output from dumpsys sensor_privacy command
     * @param sensorId The sensor ID to check (1=microphone, 2=camera)
     * @return FeatureState.ENABLED if sensor is allowed (state_type=2),
     *         FeatureState.DISABLED if sensor is blocked (state_type=1),
     *         FeatureState.ENABLED if sensor entry not found (default allowed)
     */
    fun parseSensorPrivacyOutput(output: String, sensorId: Int): FeatureState {
        try {
            // Look for the sensor block in the output
            val sensorPattern = "sensor=$sensorId[\\s\\S]*?state_type=(\\d+)".toRegex()
            val match = sensorPattern.find(output)

            return if (match != null) {
                val stateType = match.groupValues[1].toIntOrNull()
                when (stateType) {
                    1 -> FeatureState.DISABLED  // Privacy enabled = sensor blocked
                    2 -> FeatureState.ENABLED   // Privacy disabled = sensor allowed
                    else -> FeatureState.UNKNOWN
                }
            } else {
                // If sensor entry not found, assume it's allowed (default state)
                FeatureState.ENABLED
            }
        } catch (e: Exception) {
            return FeatureState.UNKNOWN
        }
    }
}
