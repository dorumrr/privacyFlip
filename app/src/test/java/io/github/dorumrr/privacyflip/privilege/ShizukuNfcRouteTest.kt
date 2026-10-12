package io.github.dorumrr.privacyflip.privilege

import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.fakenfc.HiddenNfcProxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android 16's NFC service refuses `cmd nfc disable-nfc` from the shell user: the command passes
 * the NFC app's package name, which is not the shell's. Shizuku has to call the service itself.
 */
@RunWith(RobolectricTestRunner::class)
class ShizukuNfcRouteTest {

    // Shaped like INfcAdapter on Android 15+: the caller names its own package.
    class FakeNfcService(private val answer: Boolean = true) {
        val calls = mutableListOf<String>()
        fun disable(saveState: Boolean, pkg: String): Boolean { calls += "disable($saveState, $pkg)"; return answer }
        fun enable(pkg: String): Boolean { calls += "enable($pkg)"; return answer }
    }

    // How a real refusal arrives: the service throws, as Android 16 does for a foreign package name.
    class RefusingNfcService {
        fun disable(saveState: Boolean, pkg: String): Boolean = throw SecurityException("Package $pkg does not belong to 2000")
        fun enable(pkg: String): Boolean = throw SecurityException("Package $pkg does not belong to 2000")
    }

    // Shaped like INfcAdapter on Android 14, which takes no package name.
    class OldNfcService {
        fun disable(saveState: Boolean): Boolean = true
        fun enable(): Boolean = true
    }

    private fun executorWith(service: Any?) = ShizukuExecutor().apply { nfcService = { service } }

    @Test
    @Config(sdk = [35])
    fun `turning NFC off calls the service as the shell and keeps it off after a reboot`() {
        val service = FakeNfcService()

        val result = runBlocking { executorWith(service).setFeatureState(PrivacyFeature.NFC, false) }

        assertEquals(listOf("disable(true, com.android.shell)"), service.calls)
        assertTrue(result!!.success)
    }

    @Test
    @Config(sdk = [35])
    fun `turning NFC on calls the service as the shell`() {
        val service = FakeNfcService()

        val result = runBlocking { executorWith(service).setFeatureState(PrivacyFeature.NFC, true) }

        assertEquals(listOf("enable(com.android.shell)"), service.calls)
        assertTrue(result!!.success)
    }

    @Test
    @Config(sdk = [35])
    fun `the call reaches a service whose class other packages cannot see`() {
        val calls = mutableListOf<String>()

        val result = runBlocking { executorWith(HiddenNfcProxy.create(calls)).setFeatureState(PrivacyFeature.NFC, false) }

        assertEquals(listOf("disable(true, com.android.shell)"), calls)
        assertTrue(result!!.success)
    }

    @Test
    @Config(sdk = [35])
    fun `a refused call falls back to the shell commands`() {
        val result = runBlocking { executorWith(FakeNfcService(answer = false)).setFeatureState(PrivacyFeature.NFC, false) }

        assertNull(result)
    }

    @Test
    @Config(sdk = [35])
    fun `a service that throws falls back to the shell commands`() {
        val executor = executorWith(RefusingNfcService())

        assertNull(runBlocking { executor.setFeatureState(PrivacyFeature.NFC, false) })
        assertNull(runBlocking { executor.setFeatureState(PrivacyFeature.NFC, true) })
    }

    @Test
    @Config(sdk = [35])
    fun `with no NFC service reachable the shell commands run instead`() {
        assertNull(runBlocking { executorWith(null).setFeatureState(PrivacyFeature.NFC, false) })
    }

    @Test
    @Config(sdk = [35])
    fun `a service without the package-taking calls falls back to the shell commands`() {
        assertNull(runBlocking { executorWith(OldNfcService()).setFeatureState(PrivacyFeature.NFC, false) })
    }

    @Test
    @Config(sdk = [35])
    fun `other features keep using the shell commands`() {
        val service = FakeNfcService()
        val executor = executorWith(service)

        PrivacyFeature.values().filter { it != PrivacyFeature.NFC }.forEach {
            assertNull(it.name, runBlocking { executor.setFeatureState(it, false) })
        }
        assertEquals(emptyList<String>(), service.calls)
    }

    // Below Android 15 the shell commands work and the service takes no package name.
    @Test
    @Config(sdk = [34])
    fun `before Android 15 NFC keeps using the shell commands`() {
        val service = FakeNfcService()

        assertNull(runBlocking { executorWith(service).setFeatureState(PrivacyFeature.NFC, false) })
        assertEquals(emptyList<String>(), service.calls)
    }
}
