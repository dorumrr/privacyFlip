# PrivacyFlip - open work

Tier: 3

   [ ]  not started        [~]  in progress        [x]  done

   A. THE #20 FIX'S REMAINING GAPS
   [x] A1  isLocationInUse must not crash, must keep failing safe, on older Android   done 09 Sep - fixed and reverified live on the crashing build
   [~] A2  Know what else besides "android" can suppress Location's disable          researched 09 Sep, the method was flawed - real question still open, see below
   [x] A3  Automated coverage for the awk command itself, not just its Kotlin half    done 09 Sep - test-location-detection.sh + 4 fixtures, no personal data
   [~] A4  dumpsys appops shape across SDK 24-33                                     5 of 10 versions directly tested 09 Sep - the 2 lowest (24-25) are NOT covered by the others, see below
   [ ] A5  Watch a real navigation app trigger this live, end to end                  blocked - checked BOTH the emulators and the real test phone, neither has Play Services or a nav app

   B. FOUND WHILE AUDITING #20, UNRELATED TO IT
   [ ] B1  A hotspot started during the lock delay survives being re-checked         pre-existing, not caused by #20
   [ ] B2  Unlocking during the lock delay actually cancels the pending disable      pre-existing, not caused by #20

## A1  isLocationInUse must not crash, must keep failing safe, on older Android

Tested live this round on 5 emulator images already installed on this machine, plus the real
device used all session (6 Android versions total):

```
API 26 (Android 8)   no awk binary exists at all. Fails safe (returns "not in use").
API 28 (Android 9)   awk exists, but the exact shipped command SEGFAULTS on it (exit 139).
                      Isolated: the crash is in the core state machine (inloc/found), present
                      since this session's FIRST appops-based rewrite - not something today's
                      widening added. Confirmed by running the original, simpler 2-op version:
                      it segfaults on this same awk build too. Still fails safe - a segfault is a
                      clean non-zero exit, caught the same way any other command failure is.
API 29/30/33/34       awk present, command runs clean, correct answers, nothing new here.
```

Nobody is told this ever happens. It fails exactly as safely as every other failure this app
already handles (falls back to "not in use", disables normally) - but on Android 8 and 9
specifically, "only disable Location if unused" provides exactly the same protection it did
before any of this session's work: none, silently.

depends on: none    touches: ConnectionStateChecker.kt:223-260    [Verified at runtime]

DONE 09 Sep. Root cause isolated exactly: `!found` (a bare logical-not on a variable) inside the
awk END block is what crashes API 28's build - not the 4-way alternation, not the if/else, not
`!=` used elsewhere in the same command. Confirmed by testing 11 progressively narrower variants
live on the actual crashing device until the exact trigger was found. Fix: `found==0` instead of
`!found` - numerically identical (found is only ever `1` or uninitialized-as-0, never assigned
`""`), tested and confirmed no longer crashes, on the same device, in all 4 shapes: real idle
state (NONE), no location blocks at all (NOBLOCKS), a synthetic active third-party entry (caught
correctly), and no regression on the real API 34 device. Every claim above re-verified against
the final committed string, not an earlier draft - extracted straight from the source file and
run, not retyped.

## A2  Know what else besides "android" can suppress Location's disable

The fix excludes Android's own system package because it was directly observed holding
MONITOR_LOCATION open near-permanently for internal housekeeping. Nothing says another real app
- a weather app, a fitness tracker, Play Services itself - doesn't do something similar. If one
does, Location stops getting disabled on lock for that user, silently, for a reason unrelated to
them actually navigating anywhere.

Research done 09 Sep, and the result needs to be read carefully - the METHOD turned out to be the
finding. Checked 5 `dumpsys appops` captures across 2 Android versions and both a real device and
several emulators: not one non-"android" holder appeared, anywhere, ever. That reads like a clean
result. It is not one - this round's own adversarial review caught why: every device in the
sample was Google-Play-Services-free (4 stock AOSP emulators by construction, and the one real
device is a de-Googled LineageOS phone, confirmed earlier while checking A5). Google Play
Services is the actual fused location provider on nearly every retail Android phone, and is a
well-known real holder of long-lived background location sessions for geofencing, Find My
Device, and similar features. The sample could not have found it even if it is common, because
it tested exactly the one configuration where GMS does not exist. Stock OEM ROMs (Samsung,
Xiaomi, etc, each with their own bundled location-fusion component) were equally absent from the
sample for the same structural reason.

Correction: "no further exclusion is currently evidenced" is true only for de-Googled, non-OEM
Android - which is not what most users of this app are running. The real question - does GMS (or
a common OEM equivalent) hold this open on a normal, unmodified retail phone - remains untested
and, based on how GMS's fused location provider is known to work, is plausibly a real problem:
if it does, "only disable Location if unused" may silently never actually disable Location for
most users, the opposite failure from #20 but on a much larger share of the install base than a
de-Googled phone represents.

depends on: none    touches: ConnectionStateChecker.kt:207-213    [Needs confirmation - the real
  test, a stock/GMS device or a Google-Play-flavoured emulator, was not available this session]
proves it is done: a `dumpsys appops` capture from an idle GMS-equipped device (stock Pixel,
  Samsung, or a "Google APIs"/"Google Play" flavoured emulator AVD, none of which were on this
  machine's already-installed AVD list), checked for what `com.google.android.gms` and any
  visible OEM location service hold open, with a positive control first (open a real maps app on
  that same device, confirm ITS package shows up through the identical capture before trusting
  any negative reading from it).

## A3  Automated coverage for the awk command itself, not just its Kotlin half

The current test (ConnectionStateCheckerTest.kt) only covers what happens to a string this app
already has. Nothing in CI or the test suite runs the real awk command against real device output
- that has only ever been checked by hand, live, this session.

Important limit, found by this round's own adversarial pass: a script that replays captured
`dumpsys appops` text through awk would run on the awk installed on whatever machine runs the
script - not the two specific broken builds A1 found (API 26's missing binary, API 28's
segfaulting one). It cannot catch that class of bug by itself. What it CAN catch: the command
silently stopping matching a real dumpsys shape it used to match, which is a real and different
risk. Framed honestly as covering a narrower risk than "the awk problem" as a whole.

depends on: A1 (done)    touches: test-location-detection.sh (new), app/src/test/resources/
  appops-fixtures/*.txt (new)    [Verified at runtime]

DONE 09 Sep. `test-location-detection.sh` at the repo root extracts the exact command straight
from ConnectionStateChecker.kt (reversing Kotlin's string escaping in Python, so it can never
silently drift from what ships) and runs it against 4 fixtures: 2 real captures (a stock,
freshly-booted API 28 and API 30 emulator - NOT 5 versions as first drafted; only 2 were kept as
committed fixtures) and 2 synthetic ones (no location blocks at all; a genuine third-party
match). All 4 pass. Watched it fail first: an early version of this script's own self-check was
broken 3 separate ways (a `grep` that could never match real output, a missing-fixture check
that never called the real comparison function, a fixture-read failure that could silently look
like a valid NOBLOCKS answer) - all 3 found by this round's adversarial review, all 3 fixed, then
re-verified by deliberately breaking the real comparison logic and watching the self-check catch
it before restoring.

Data handling note, found and fixed the same round: the first draft of this fixture set included
a full, unredacted `dumpsys appops` capture from the maintainer's own real phone - which lists
every installed app and a timestamped history of what each one has accessed. That would have
committed real personal data to a public repo. Caught before any commit; removed and replaced
with an emulator capture that serves the same test purpose with nothing personal in it. Every
fixture in this directory must be from a fresh stock emulator, never a personal device - noted in
the script's own header comment so this isn't re-learned the hard way next time.

## A4  dumpsys appops shape across SDK 24-33

Directly tested live, 09 Sep: API 26, 28, 29, 30, 33 (plus 34, already known from earlier this
session). The format itself (Package lines, op headers, the Running-start marker) was consistent
everywhere it could be read - the 2 versions that fail (26, 28) fail on missing/broken awk, not on
a different dumpsys shape.

Correction from this round's own review: API 24 and 25 are NOT covered by that consistency
argument. Their only tested neighbour is API 26 - one of the two known-BROKEN versions - so there
is no known-good bracket around them the way there is for the untested gaps elsewhere (27, 31,
32, each sitting between two versions that already tested clean). If anything, 24-25 are now more
likely to share 26's problem than not. Left as `[~]`, not `[x]`.

## A5  Watch a real navigation app trigger this live, end to end

Still blocked - now checked properly rather than assumed. This round's own review pointed out the
AOSP emulators lacking Play Services was given as the reason, without ever checking the real
physical device used all session. Checked directly: that device's installed packages (252 total,
`pm list packages --user 0`) include no Google Play Services and no common navigation app (Maps,
OsmAnd, Waze, Organic Maps). Both available devices are confirmed unable to run this test as they
stand.

depends on: none    touches: none    [Verified at runtime - confirmed blocked, not just assumed]

## B1  A hotspot started during the lock delay survives being re-checked

PrivacyActionWorker.kt:166-167 samples whether a hotspot is active once, before the lock delay
starts, and never again. #20's own post-delay re-check (added this session, PrivacyActionWorker.kt
:322-364) only re-tests per-feature "only if unused" state via `isFeatureInUse` - WIFI routes to
`isWifiConnected()` (client-mode connection only) and MOBILE_DATA is hardcoded false; neither
touches tethering state. If a hotspot gets turned on during the delay window specifically, WiFi/
Mobile Data can still be switched off at the end of it - exactly the outcome the comment at
PrivacyActionWorker.kt:158-165 says must never happen.

Pre-existing. Not caused or made worse by #20 - found only because reviewing #20's fix meant
reading this code closely for the first time in a while.

depends on: none    touches: PrivacyActionWorker.kt:166-167,322-364    [Verified in code]
proves it is done - corrected by this round's own review, which caught that the first draft of
  this check never locked the phone at all, so it could not have exercised the bug either way:
  lock the device (arms the delay), START the hotspot only AFTER locking - during the delay
  window, not before it - wait past the configured delay, then read WiFi/Mobile Data state with
  a named command (`dumpsys wifi` / the app's own status check) and confirm both are still on.

## B2  Unlocking during the lock delay actually cancels the pending disable

ScreenStateReceiver.kt:50-53 handles ACTION_USER_PRESENT but only logs and enqueues unlock-side
work under a different unique name (WORK_NAME_UNLOCK) - it never cancels the lock-side work.
Only ACTION_SCREEN_ON's handler (:55, cancel call at :74) calls cancelPendingLockWork (defined
:122), and only when `keyguardManager?.isKeyguardLocked ?: true` reads false at that exact
moment (:68-70) - which the ordinary screen-on-then-enter-PIN sequence may not satisfy in time.

Pre-existing. Not caused or made worse by #20 - found the same way as B1.

depends on: none    touches: ScreenStateReceiver.kt:50-53,55,68-74,122-129    [Verified in code]
proves it is done - corrected by this round's own review, which caught that a bare "a cancel
  line appeared in logcat" cannot tell a real cancel apart from a job that merely lost the race
  anyway, since an outrun job can log something that looks similar: clear the log first
  (`logcat -c`), unlock the device during the configured lock delay via the normal path, assert
  the exact expected cancel log line appears exactly once, THEN wait past the original deadline
  and confirm zero feature toggles actually executed after that point - the outcome that
  actually matters, not just that a log line was printed.
