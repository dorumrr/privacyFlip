package io.github.dorumrr.privacyflip.privilege

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A su the user said no to must still count as root being there, or the app falls to "Not
 * Available" and the "Check Again" button that re-runs su can never be reached.
 */
class RootExecutorSuOnPathTest {

    private fun suAt(vararg dirs: String): (File) -> Boolean = { file ->
        file.name == "su" && file.parent in dirs
    }

    @Test
    fun `su in any PATH folder counts`() {
        assertTrue(RootExecutor.suOnPath("/product/bin:/system/bin:/system/xbin", suAt("/system/bin")))
    }

    @Test
    fun `no su on PATH means no root`() {
        assertFalse(RootExecutor.suOnPath("/product/bin:/system/bin", suAt("/sbin")))
    }

    @Test
    fun `a missing or empty PATH means no root, never a look in the working folder`() {
        assertFalse(RootExecutor.suOnPath(null) { true })
        assertFalse("empty PATH entries must not be probed at all", RootExecutor.suOnPath("::") { true })
    }
}
