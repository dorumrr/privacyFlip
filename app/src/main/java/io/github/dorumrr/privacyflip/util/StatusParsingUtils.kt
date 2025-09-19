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
}
