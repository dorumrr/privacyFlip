package io.github.dorumrr.privacyflip.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether this app has root is decided by running `id -u` and looking for "0".
 *
 * The shell is built with FLAG_REDIRECT_STDERR. Verified in the libsu 5.0.4 bytecode, not assumed:
 * JobImpl reads ShellImpl.redirect and, when set, assigns the err list to be the SAME list object
 * as out. So anything su writes to stderr is appended to the output this parser reads, and it can
 * arrive ahead of the uid.
 *
 * Reading only the first line therefore reported a fully rooted, fully granted phone as NOT
 * granted, on every single check, on any device whose su prints a banner or a warning.
 *
 * The two streams are drained by separate threads, so the banner can land on EITHER side of the
 * uid. Both orders are pinned below, or a last-line-only parse would satisfy every case here.
 *
 * Over-matching is the more dangerous direction and is pinned harder: reporting an unrooted phone
 * as rooted would enable toggles that then fail silently.
 */
class RootExecutorUidParseTest {

    @Test
    fun `a plain uid line is read as root`() {
        assertTrue(RootExecutor.isRootUid(listOf("0")))
    }

    @Test
    fun `a banner ahead of the uid does not hide it`() {
        // The whole defect, in one line of fake output.
        assertTrue(
            "the uid is there, it is just not first",
            RootExecutor.isRootUid(listOf("su: applying SELinux context", "0"))
        )
    }

    @Test
    fun `several noisy lines ahead of the uid do not hide it`() {
        assertTrue(
            RootExecutor.isRootUid(listOf("W: something", "mount: ignoring", "0"))
        )
    }

    @Test
    fun `a banner AFTER the uid does not hide it either`() {
        // Without this, a last-line-only parse satisfies every other case in this file.
        assertTrue(
            "the uid is there, it is just not last",
            RootExecutor.isRootUid(listOf("0", "su: applying SELinux context"))
        )
    }

    @Test
    fun `whitespace around the uid does not hide it`() {
        assertTrue(RootExecutor.isRootUid(listOf(" 0 ")))
    }

    @Test
    fun `a carriage return or tab around the uid does not hide it`() {
        // What a pty-backed su line really looks like. A trim that only handles spaces would
        // re-introduce the false "not granted" this whole file exists to stop.
        assertTrue(RootExecutor.isRootUid(listOf("0\r")))
        assertTrue(RootExecutor.isRootUid(listOf("\t0\t")))
    }

    @Test
    fun `a successful command reports no error, even though the output is not empty`() {
        // With the redirect flag applied, libsu hands back ONE list for both streams, so err is
        // literally the same object. Reading it made every successful command carry a failure
        // message built from its own normal output.
        val merged = listOf("BluetoothShellCommand: Success")
        assertNull(
            "a command that worked has no error to report",
            RootExecutor.errorFrom(success = true, outputLines = merged, errorLines = merged)
        )
    }

    @Test
    fun `a SUCCESSFUL command reports no error even when the two streams are separate lists`() {
        // The success check must come first. An implementation that looked at the lists before the
        // success flag would hand every successful command on a non-redirected shell an error
        // string, which is the exact regression this file exists to stop, in the branch the other
        // success case never enters.
        assertNull(
            RootExecutor.errorFrom(
                success = true,
                outputLines = listOf("ordinary output"),
                errorLines = listOf("a warning nobody asked about")
            )
        )
    }

    @Test
    fun `a failed command with merged streams reports the WHOLE output, not just the first line`() {
        // The first line is very often a banner. Reporting only it would show the user the banner
        // instead of the failure, which this file already proves can arrive ahead of real output.
        val merged = listOf("su: applying SELinux context", "cmd: Failure calling service")
        assertEquals(
            "su: applying SELinux context\ncmd: Failure calling service",
            RootExecutor.errorFrom(success = false, outputLines = merged, errorLines = merged)
        )
    }

    @Test
    fun `a failed command with a SEPARATE stderr reports the stderr, not the stdout`() {
        // The redirect flag is only applied if the shell had not already been built, so a real,
        // separate stderr happens. Ignoring it would throw away the only reason a failure gives.
        assertEquals(
            "svc: Killed",
            RootExecutor.errorFrom(
                success = false,
                outputLines = listOf("some ordinary output"),
                errorLines = listOf("svc: Killed")
            )
        )
    }

    @Test
    fun `a failed command with no output at all reports null rather than an empty string`() {
        assertNull(RootExecutor.errorFrom(false, emptyList(), emptyList()))
        val blank = listOf("", "   ")
        assertNull(RootExecutor.errorFrom(false, blank, blank))
    }

    @Test
    fun `a cached NON-root shell is dropped, so a re-check can see a new grant`() {
        // libsu keeps one main shell and only replaces it when it DIES. A non-root shell stays
        // alive, so without dropping it the "Check Again" control re-reads the same answer for
        // the life of the process: a button that cannot do what it says.
        assertTrue(RootExecutor.shouldDropCachedShell(hasCachedShell = true, cachedIsRoot = false))
    }

    @Test
    fun `a cached ROOT shell is never dropped`() {
        // The dangerous direction. Privileged work runs on that shell, and closing it mid-lock
        // would tear down a disable that is in flight.
        assertFalse(RootExecutor.shouldDropCachedShell(hasCachedShell = true, cachedIsRoot = true))
    }

    @Test
    fun `nothing is dropped when there is no cached shell at all`() {
        assertFalse(RootExecutor.shouldDropCachedShell(hasCachedShell = false, cachedIsRoot = false))
    }

    @Test
    fun `a zero inside a longer line is not a uid`() {
        // The dangerous direction. A scan that accepted any line CONTAINING a zero would call an
        // unrooted phone rooted, and the app would then offer toggles that silently fail.
        assertFalse(
            RootExecutor.isRootUid(listOf("avc: denied { read } for uid=0 comm=id", "10279"))
        )
        assertFalse(
            RootExecutor.isRootUid(listOf("setresuid(0,0,0) failed", "10279"))
        )
    }

    @Test
    fun `a non-root uid is not root`() {
        assertFalse(RootExecutor.isRootUid(listOf("10279")))
    }

    @Test
    fun `a banner with a non-root uid is still not root`() {
        // The guard against over-correcting: scanning every line must not start saying yes to a
        // phone that has no root at all.
        assertFalse(RootExecutor.isRootUid(listOf("su: permission denied", "10279")))
    }

    @Test
    fun `no output at all is not root`() {
        assertFalse(RootExecutor.isRootUid(emptyList()))
    }

    @Test
    fun `a uid that merely contains a zero is not root`() {
        assertFalse("10 is not 0, and neither is 1000", RootExecutor.isRootUid(listOf("1000")))
    }
}
