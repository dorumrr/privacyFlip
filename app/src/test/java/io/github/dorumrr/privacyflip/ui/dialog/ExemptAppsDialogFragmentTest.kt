package io.github.dorumrr.privacyflip.ui.dialog

import android.app.AppOpsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.os.Process
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import io.github.dorumrr.privacyflip.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExemptAppsDialogFragmentTest {

    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_PrivacyFlip)
        activity = controller.create().start().resume().get()
        shadowOf(activity.packageManager).installPackage(
            PackageInfo().apply {
                packageName = "com.example.maps"
                applicationInfo = ApplicationInfo().apply { packageName = "com.example.maps" }
            }
        )
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    private fun usageAccess(mode: Int) {
        val appOps = activity.getSystemService(AppOpsManager::class.java)
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), activity.packageName, mode)
    }

    private fun show(): ExemptAppsDialogFragment =
        ExemptAppsDialogFragment().also { it.showNow(activity.supportFragmentManager, ExemptAppsDialogFragment.TAG) }

    @Test
    fun `closing the dialog while the app list loads does not crash the app`() {
        val crashes = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
        try {
            val dialog = show()
            dialog.dismissNow()
            repeat(50) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertTrue("the app must not crash, was: $crashes", crashes.isEmpty())
    }

    @Test
    fun `the dialog asks for usage access only while it is missing`() {
        usageAccess(AppOpsManager.MODE_IGNORED)
        val dialog = show()
        val warning = dialog.requireDialog().findViewById<View>(R.id.permission_warning)
        assertEquals("missing access must be shown", View.VISIBLE, warning.visibility)

        usageAccess(AppOpsManager.MODE_ALLOWED)
        controller.pause().resume()
        assertEquals("a grant made in Settings must clear it on return", View.GONE, warning.visibility)
    }

    @Test
    fun `closing the dialog tells the main screen to re-check its note`() {
        var signalled = false
        activity.supportFragmentManager.setFragmentResultListener(ExemptAppsDialogFragment.RESULT_CLOSED, activity) { _, _ ->
            signalled = true
        }

        show().dismissNow()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(signalled)
    }
}
