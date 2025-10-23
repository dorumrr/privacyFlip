package io.github.dorumrr.privacyflip.privilege

/**
 * Result of executing a privileged command
 */
data class CommandResult(
    val success: Boolean,
    val output: List<String>,
    val error: String? = null,
    val exitCode: Int = if (success) 0 else 1
) {
    /**
     * Returns the output as a single string
     */
    fun getOutputString(): String = output.joinToString("\n")
    
    /**
     * Returns true if the command succeeded and produced output
     */
    fun hasOutput(): Boolean = success && output.isNotEmpty()
    
    companion object {
        /**
         * Creates a failed result with an error message
         */
        fun failure(error: String, exitCode: Int = 1): CommandResult {
            return CommandResult(
                success = false,
                output = emptyList(),
                error = error,
                exitCode = exitCode
            )
        }
        
        /**
         * Creates a successful result with output
         */
        fun success(output: List<String> = emptyList()): CommandResult {
            return CommandResult(
                success = true,
                output = output,
                error = null,
                exitCode = 0
            )
        }
    }
}

