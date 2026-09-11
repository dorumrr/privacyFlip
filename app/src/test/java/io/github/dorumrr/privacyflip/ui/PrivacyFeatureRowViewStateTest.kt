package io.github.dorumrr.privacyflip.ui

import android.app.Application
import android.os.Parcelable
import android.util.SparseArray
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #38. privacy_feature_row.xml is <include>d 5 times and privacy_protection_mode_row.xml
 * twice, and both declare the same child ids, so up to 7 live controls share one id. Android's
 * view state is a SparseArray keyed by view id, so whichever control saved last was restored
 * into all of them, and each resulting change fired the listener that persists it - rewriting
 * every feature's saved settings with one row's values.
 *
 * The first test asserts the whole invariant rather than one row's value: with every shared
 * control checked before the save, a correct build restores none of them. That makes it fail if
 * android:saveEnabled="false" is dropped from ANY one of the 8 controls in EITHER layout, which
 * an earlier version of this test did not - it set only Battery Saver's controls, so removing
 * the attribute from privacy_feature_row.xml alone left it green. Watched failing both ways
 * before it was trusted: attribute removed from privacy_feature_row.xml alone, and from
 * privacy_protection_mode_row.xml alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PrivacyFeatureRowViewStateTest {

    /** Every row built from one of the two shared layouts, with the ids it actually contains. */
    private val sharedRows = mapOf(
        R.id.wifiSettings to FEATURE_ROW_CONTROLS,
        R.id.bluetoothSettings to FEATURE_ROW_CONTROLS,
        R.id.mobileDataSettings to FEATURE_ROW_CONTROLS,
        R.id.locationSettings to FEATURE_ROW_CONTROLS,
        R.id.nfcSettings to FEATURE_ROW_CONTROLS,
        R.id.airplaneModeSettings to PROTECTION_ROW_CONTROLS,
        R.id.batterySaverSettings to PROTECTION_ROW_CONTROLS
    )

    private fun inflateScreen(): ViewGroup {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val themed = ContextThemeWrapper(context, R.style.Theme_PrivacyFlip)
        return LayoutInflater.from(themed).inflate(R.layout.fragment_main, null) as ViewGroup
    }

    private fun control(root: View, rowId: Int, controlId: Int): CompoundButton =
        root.findViewById<View>(rowId).findViewById(controlId)

    @Test
    fun `no control shared between rows carries state across a save and restore`() {
        val saved = inflateScreen()
        var setCount = 0
        sharedRows.forEach { (rowId, controlIds) ->
            controlIds.forEach { controlId ->
                control(saved, rowId, controlId).isChecked = true
                setCount++
            }
        }
        // Guards against the whole test passing because it found nothing to set.
        // 5 feature rows and 2 protection rows, 4 controls each.
        assertEquals("expected to set every shared control", 28, setCount)

        val state = SparseArray<Parcelable>()
        saved.saveHierarchyState(state)

        val restored = inflateScreen()
        restored.restoreHierarchyState(state)

        sharedRows.forEach { (rowId, controlIds) ->
            controlIds.forEach { controlId ->
                assertEquals(
                    "a control shared across rows was restored, so one row's value can still " +
                        "be replayed into the others (row ${idName(rowId)}, " +
                        "control ${idName(controlId)})",
                    false,
                    control(restored, rowId, controlId).isChecked
                )
            }
        }
    }

    @Test
    fun `a control with an id of its own still restores its own state`() {
        // Control for the test above, which asserts false everywhere and would pass vacuously if
        // save and restore never ran at all. Camera's switches are declared directly in
        // card_screen_lock_config.xml with unique ids, so they never collided and must keep
        // working. This also fails if the fix is pasted on too widely.
        val saved = inflateScreen()
        saved.findViewById<CompoundButton>(R.id.cameraDisableOnLockSwitch).isChecked = true

        val state = SparseArray<Parcelable>()
        saved.saveHierarchyState(state)

        val restored = inflateScreen()
        restored.restoreHierarchyState(state)

        assertTrue(
            "Camera's switch has an id of its own and must still restore its own state, " +
                "which also proves save and restore actually ran in the test above",
            restored.findViewById<CompoundButton>(R.id.cameraDisableOnLockSwitch).isChecked
        )
    }

    private fun idName(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().resources.getResourceEntryName(id)

    private companion object {
        val FEATURE_ROW_CONTROLS = listOf(
            R.id.disableOnLockSwitch,
            R.id.onlyIfUnusedCheckbox,
            R.id.enableOnUnlockSwitch,
            R.id.onlyIfNotEnabledCheckbox
        )
        val PROTECTION_ROW_CONTROLS = listOf(
            R.id.disableOnLockSwitch,
            R.id.onlyIfUnusedCheckbox,
            R.id.enableOnUnlockSwitch,
            R.id.onlyIfNotManualCheckbox
        )
    }
}
