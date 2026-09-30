package io.github.dorumrr.privacyflip.util

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.root.RootManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Mobile Data is in use" must say what was playing, or a false positive cannot be traced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MediaInUseReasonTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val checker = ConnectionStateChecker(context, RootManager.getInstance(Unit))

    @Test
    fun `the reason names the kind of audio each active player is playing`() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audio).setActivePlaybackConfigurationsFor(
            listOf(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
        )

        val reason = checker.describeMediaPlayers()

        assertTrue("got: $reason", reason.contains("USAGE_GAME"))
    }

    @Test
    fun `with no active player the reason says so`() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audio).setActivePlaybackConfigurationsFor(emptyList())

        assertEquals("no active players", checker.describeMediaPlayers())
    }
}
