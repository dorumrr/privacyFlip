package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.root.RootManager
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs MobileDataToggle's real status commands through a POSIX shell, with dumpsys and settings
 * stubbed by a telephony dump shaped like a real phone's, and stops at the first command that
 * exits 0, as PrivilegeExecutor.executeWithFallbacks does.
 */
class MobileDataStatusCommandTest {

    private class Commands : MobileDataToggle(RootManager.getInstance(Unit)) {
        val status get() = statusCommands.map { it.primary }
    }

    private val toggle = Commands()

    private fun registry(slot0: Boolean?, slot1: Boolean?, activeSub: Int?, vararg log: String) = buildString {
        appendLine("last known state:")
        listOf(slot0, slot1).forEachIndexed { slot, enabled ->
            appendLine("  Phone Id=$slot")
            appendLine("    mDataConnectionState=-1")
            if (enabled != null) appendLine("    mIsDataEnabled=$enabled")
        }
        if (activeSub != null) appendLine("  mActiveDataSubId=$activeSub")
        appendLine("local logs:")
        log.forEach { appendLine("  2026-10-12T12:56:55 - notifyDataConnectionForSubscriber: $it  state: CONNECTED") }
    }

    private fun read(dump: String, keys: Map<String, String>): FeatureState {
        assumeTrue("needs a POSIX shell", File("/bin/sh").canExecute())
        val dir = Files.createTempDirectory("telephony").toFile()
        try {
            File(dir, "registry").writeText(dump)
            keys.forEach { (key, value) -> File(dir, key).writeText(value + "\n") }
            val stubs = "dumpsys() { [ \"\$1\" = telephony.registry ] && cat '${dir.path}/registry'; }; " +
                "settings() { [ \"\$1 \$2\" = 'get global' ] || return 1; cat \"${dir.path}/\$3\" 2>/dev/null || echo null; }; "
            for (command in toggle.status) {
                val process = ProcessBuilder("/bin/sh", "-c", stubs + command).start()
                val output = process.inputStream.bufferedReader().readLines()
                if (process.waitFor() == 0) return toggle.parseStatusOutput(output.joinToString(" ").lowercase())
            }
            return FeatureState.UNKNOWN
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `dual SIM with the data SIM in the second slot reads that slot`() {
        assertEquals(FeatureState.ENABLED, read(registry(false, true, 3, "phoneId=1 subId=3"), mapOf("mobile_data" to "0")))
        assertEquals(FeatureState.DISABLED, read(registry(true, false, 3, "phoneId=1 subId=3"), mapOf("mobile_data" to "1")))
    }

    @Test
    fun `dual SIM whose slot log rolled away reads the data SIM's own key, not the first slot`() {
        assertEquals(FeatureState.ENABLED, read(registry(false, true, 3), mapOf("mobile_data3" to "1", "mobile_data" to "0")))
        assertEquals(FeatureState.DISABLED, read(registry(true, false, 3), mapOf("mobile_data3" to "0", "mobile_data" to "1")))
    }

    @Test
    fun `a sub id that is a prefix of another does not pick the other's slot`() {
        assertEquals(FeatureState.ENABLED, read(registry(false, true, 3, "phoneId=0 subId=31"), mapOf("mobile_data3" to "1")))
    }

    @Test
    fun `a SIM that moved slot is read where the newest log line puts it`() {
        assertEquals(FeatureState.ENABLED, read(registry(false, true, 3, "phoneId=0 subId=3", "phoneId=1 subId=3"), emptyMap()))
    }

    @Test
    fun `no active data SIM never takes a slot from a log line for sub id -1`() {
        assertEquals(FeatureState.DISABLED, read(registry(false, true, -1, "phoneId=1 subId=-1"), emptyMap()))
    }

    @Test
    fun `a data SIM slot without its own line never borrows the next slot's`() {
        assertEquals(FeatureState.ENABLED, read(registry(null, false, 3, "phoneId=0 subId=3"), mapOf("mobile_data3" to "1")))
    }

    @Test
    fun `single SIM with no per-SIM key reads the telephony line`() {
        assertEquals(FeatureState.DISABLED, read(registry(false, null, 1), mapOf("mobile_data" to "1")))
    }

    @Test
    fun `before Android 12, with no telephony line, the stored key is read`() {
        assertEquals(FeatureState.DISABLED, read(registry(null, null, 1, "phoneId=0 subId=1"), mapOf("mobile_data" to "0")))
    }
}
