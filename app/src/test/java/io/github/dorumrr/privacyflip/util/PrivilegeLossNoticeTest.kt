package io.github.dorumrr.privacyflip.util

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.privilege.PrivilegeMethod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeLossNoticeTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = PreferenceManager.getInstance(context)
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var savedDebug = false
    private var savedProtection = true

    @Before
    fun setUp() {
        savedDebug = prefs.debugNotificationsEnabled
        savedProtection = prefs.isGlobalPrivacyEnabled
        prefs.debugNotificationsEnabled = false
        prefs.privilegeMissingReason = null
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        prefs.debugNotificationsEnabled = savedDebug
        prefs.isGlobalPrivacyEnabled = savedProtection
        prefs.privilegeMissingReason = null
        manager.cancelAll()
    }

    private fun accessNotifications() =
        shadowOf(manager).allNotifications.filter { it.channelId == Constants.AccessNotification.CHANNEL_ID }

    @Test
    fun `a loss reaches a user without debug notifications, once however many locks it skips`() {
        val reason = PrivilegeLossNotice.reasonFor(PrivilegeMethod.NONE)

        repeat(3) { PrivilegeLossNotice.report(context, reason) }

        assertEquals(1, accessNotifications().size)
        assertEquals(reason, prefs.privilegeMissingReason)
    }

    @Test
    fun `a different reason replaces the notice instead of adding one`() {
        PrivilegeLossNotice.report(context, PrivilegeLossNotice.reasonFor(PrivilegeMethod.NONE))
        val later = PrivilegeLossNotice.reasonFor(PrivilegeMethod.DHIZUKU)

        PrivilegeLossNotice.report(context, later)

        val shown = accessNotifications()
        assertEquals(1, shown.size)
        assertEquals(later, shown.single().extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals(later, prefs.privilegeMissingReason)
    }

    @Test
    fun `access coming back removes the notice`() {
        PrivilegeLossNotice.report(context, PrivilegeLossNotice.reasonFor(PrivilegeMethod.SHIZUKU))

        PrivilegeLossNotice.clear(context)

        assertTrue(accessNotifications().isEmpty())
        assertNull(prefs.privilegeMissingReason)
    }

    @Test
    fun `protection on but without access is not protection that can run`() {
        prefs.isGlobalPrivacyEnabled = true
        assertTrue(PrivilegeLossNotice.protectionCanRun(prefs))

        prefs.privilegeMissingReason = PrivilegeLossNotice.reasonFor(PrivilegeMethod.ROOT)

        assertFalse(PrivilegeLossNotice.protectionCanRun(prefs))
    }

    @Test
    fun `the reason is never restored onto another phone, where it would silence that phone's notice`() {
        PrivilegeLossNotice.report(context, PrivilegeLossNotice.reasonFor(PrivilegeMethod.NONE))

        val file = "${Constants.Preferences.PRIVILEGE_STATE_PREFS}.xml"
        assertFalse(
            "the backed-up settings must not hold it",
            context.getSharedPreferences(Constants.Preferences.PRIVACY_SWITCH_PREFS, Context.MODE_PRIVATE)
                .contains(Constants.Preferences.KEY_PRIVILEGE_MISSING)
        )
        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { rules ->
            val text = java.io.File("src/main/res/xml/$rules").readText()
            val excludes = Regex("""<exclude domain="sharedpref" path="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
            val includes = Regex("""<include domain="sharedpref"""").findAll(text).count()
            assertEquals("$rules: every place that includes settings must exclude $file", includes, excludes.count { it == file })
        }
    }

    @Test
    fun `each state says what to do`() {
        assertTrue(PrivilegeLossNotice.reasonFor(PrivilegeMethod.NONE).contains("Check Again"))
        PrivilegeMethod.values().filter { it != PrivilegeMethod.NONE }.forEach {
            val reason = PrivilegeLossNotice.reasonFor(it)
            assertTrue("$it: $reason", reason.contains(it.getDisplayName()) && reason.contains("permission"))
        }
    }
}
