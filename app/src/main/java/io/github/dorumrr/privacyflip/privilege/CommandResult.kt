package io.github.dorumrr.privacyflip.privilege

/**
 * Result of executing a privileged command
 */
data class CommandResult(
    val success: Boolean,
    val output: List<String>,
    val error: String? = null,
    // null when no command ran, so no log shows an exit code nothing produced.
    val exitCode: Int? = if (success) 0 else null
) {
    companion object {
        fun fromProcess(exitCode: Int, outputLines: List<String>, errorLines: List<String>): CommandResult =
            CommandResult(
                success = exitCode == 0,
                output = outputLines,
                error = errorFrom(exitCode == 0, outputLines, errorLines),
                exitCode = exitCode
            )

        // Android's own commands often explain a failure on stdout. With libsu's
        // FLAG_REDIRECT_STDERR the two lists are the same object, so stderr carries nothing of its own.
        internal fun errorFrom(
            success: Boolean,
            outputLines: List<String>,
            errorLines: List<String>
        ): String? {
            if (success) return null
            val separateStderr = errorLines !== outputLines && errorLines.isNotEmpty()
            val source = if (separateStderr) errorLines else outputLines
            return source.joinToString("\n").ifBlank { null }
        }

        fun failure(error: String, exitCode: Int? = null): CommandResult {
            return CommandResult(
                success = false,
                output = emptyList(),
                error = error,
                exitCode = exitCode
            )
        }

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

