package io.github.dorumrr.privacyflip.util

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33])
class ForegroundAppDetectorTest {

    private lateinit var context: Application
    private lateinit var detector: ForegroundAppDetector
    private var now = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        detector = ForegroundAppDetector(context)
        now = System.currentTimeMillis()
    }

    private fun event(packageName: String, secondsAgo: Long, type: Int) {
        val usageStats = context.getSystemService(UsageStatsManager::class.java)
        shadowOf(usageStats).addEvent(packageName, now - secondsAgo * 1000, type)
    }

    private fun opened(packageName: String, secondsAgo: Long) =
        event(packageName, secondsAgo, UsageEvents.Event.ACTIVITY_RESUMED)

    private fun install(packageName: String) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
        )
    }

    private fun usageAccess(mode: Int) {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, mode)
    }

    @Test
    fun `usage access reads as missing until the user allows it`() {
        usageAccess(AppOpsManager.MODE_IGNORED)
        assertFalse(detector.hasUsageAccess())

        usageAccess(AppOpsManager.MODE_ALLOWED)
        assertTrue(detector.hasUsageAccess())
    }

    @Test
    fun `a default app-op mode defers to the usage stats permission`() {
        usageAccess(AppOpsManager.MODE_DEFAULT)
        assertFalse(detector.hasUsageAccess())

        shadowOf(context).grantPermissions(Manifest.permission.PACKAGE_USAGE_STATS)
        assertTrue(detector.hasUsageAccess())
    }

    @Test
    fun `missing usage access matters only once an app is exempt`() {
        install("com.example.maps")
        usageAccess(AppOpsManager.MODE_IGNORED)
        assertFalse(detector.exemptAppsNeedUsageAccess(emptySet()))
        assertTrue(detector.exemptAppsNeedUsageAccess(setOf("com.example.maps")))

        usageAccess(AppOpsManager.MODE_ALLOWED)
        assertFalse(detector.exemptAppsNeedUsageAccess(setOf("com.example.maps")))
    }

    @Test
    fun `one installed exempt app is enough to ask for usage access`() {
        install("com.example.maps")
        usageAccess(AppOpsManager.MODE_IGNORED)

        assertTrue(detector.exemptAppsNeedUsageAccess(setOf("com.example.maps", "com.example.removed")))
    }

    @Test
    fun `an exempt app that was uninstalled does not ask for usage access`() {
        usageAccess(AppOpsManager.MODE_IGNORED)

        assertFalse(detector.exemptAppsNeedUsageAccess(setOf("com.example.removed")))
    }

    @Test
    fun `an app opened a minute before the lock is still the one in front`() {
        opened("com.example.maps", secondsAgo = 60)

        assertEquals("com.example.maps", detector.getFirstForegroundApp(setOf("com.example.maps")))
    }

    @Test
    fun `an app kept on screen for hours is still the one in front`() {
        opened("com.example.maps", secondsAgo = 3 * 60 * 60)

        assertEquals("com.example.maps", detector.getForegroundApp())
    }

    @Test
    fun `the app opened last is the one in front`() {
        opened("com.example.maps", secondsAgo = 60)
        opened("com.example.browser", secondsAgo = 30)

        assertEquals("com.example.browser", detector.getForegroundApp())
    }

    @Test
    fun `an exempt app opened earlier does not count once another app opened after it`() {
        opened("com.example.maps", secondsAgo = 60)
        opened("com.example.browser", secondsAgo = 30)

        assertEquals(null, detector.getFirstForegroundApp(setOf("com.example.maps")))
    }

    @Test
    fun `each screen-on period counts, not only the first one in the window`() {
        opened("com.example.maps", secondsAgo = 300)
        event("android", secondsAgo = 240, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        event("android", secondsAgo = 200, UsageEvents.Event.SCREEN_INTERACTIVE)
        opened("com.example.browser", secondsAgo = 150)
        event("android", secondsAgo = 100, UsageEvents.Event.SCREEN_NON_INTERACTIVE)

        assertEquals("com.example.browser", detector.getForegroundApp())
    }

    @Test
    fun `waking the screen without opening an app keeps the app the user locked with`() {
        opened("com.example.maps", secondsAgo = 300)
        event("android", secondsAgo = 240, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        event("android", secondsAgo = 200, UsageEvents.Event.SCREEN_INTERACTIVE)
        event("android", secondsAgo = 190, UsageEvents.Event.SCREEN_NON_INTERACTIVE)

        assertEquals("com.example.maps", detector.getForegroundApp())
    }

    @Test
    fun `after a reboot no app is in front until one opens`() {
        opened("com.example.maps", secondsAgo = 3 * 60 * 60)
        event("android", secondsAgo = 2 * 60 * 60, UsageEvents.Event.DEVICE_STARTUP)
        event("android", secondsAgo = 2 * 60 * 60 - 5, UsageEvents.Event.SCREEN_INTERACTIVE)

        assertEquals(null, detector.getForegroundApp())
    }

    @Test
    fun `the pause and stop that a screen-off causes do not drop the app the user locked with`() {
        opened("com.example.maps", secondsAgo = 60)
        event("android", secondsAgo = 5, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        event("com.example.maps", secondsAgo = 4, UsageEvents.Event.ACTIVITY_PAUSED)
        event("com.example.maps", secondsAgo = 3, UsageEvents.Event.ACTIVITY_STOPPED)

        assertEquals("com.example.maps", detector.getForegroundApp())
    }

    @Test
    fun `an app opened after a reboot counts`() {
        opened("com.example.maps", secondsAgo = 3 * 60 * 60)
        event("android", secondsAgo = 2 * 60 * 60, UsageEvents.Event.DEVICE_STARTUP)
        opened("com.example.browser", secondsAgo = 30 * 60)

        assertEquals("com.example.browser", detector.getForegroundApp())
    }

    @Test
    fun `an app opened more than a day ago no longer counts`() {
        opened("com.example.maps", secondsAgo = 25 * 60 * 60)

        assertEquals(null, detector.getForegroundApp())
    }

    @Test
    fun `an app used for longer than the short window is still found when only its pause is recent`() {
        opened("com.example.maps", secondsAgo = 30 * 60)
        event("android", secondsAgo = 5, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        event("com.example.maps", secondsAgo = 4, UsageEvents.Event.ACTIVITY_PAUSED)
        event("com.example.maps", secondsAgo = 3, UsageEvents.Event.ACTIVITY_STOPPED)

        assertEquals("com.example.maps", detector.getFirstForegroundApp(setOf("com.example.maps")))
    }

    @Test
    fun `an app that opens after the screen went off is not the one the user locked with`() {
        opened("com.example.maps", secondsAgo = 60)
        event("android", secondsAgo = 5, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        opened("com.example.alarm", secondsAgo = 2)

        assertEquals("com.example.maps", detector.getForegroundApp())
    }
}
