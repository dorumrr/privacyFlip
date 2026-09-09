# PrivacyFlip - open work

Tier: 3

   [ ]  not started        [~]  in progress        [x]  done

   A. THE #20 FIX'S REMAINING GAPS
   [x] A1  isLocationInUse must not crash, must keep failing safe, on older Android   done 09 Sep - fixed and reverified live on the crashing build
   [x] A2  Know what else besides "android" can suppress Location's disable          code confirmed correct 09 Sep - real-device verification left to user reports, Doru's call
   [x] A3  Automated coverage for the awk command itself, not just its Kotlin half    done 09 Sep - test-location-detection.sh + 4 fixtures, no personal data
   [x] A4  dumpsys appops shape across SDK 24-33                                     code confirmed correct 09 Sep - the 2 untested versions (24-25) left to user reports, Doru's call
   [ ] A5  Watch a real navigation app trigger this live, end to end                  NOT pursuing further - Doru's call, see below

   B. FOUND WHILE AUDITING #20, UNRELATED TO IT
   [x] B1  A hotspot started during the lock delay survives being re-checked         done 09 Sep - narrow scope only, see C1 for what this does not cover
   [x] B2  Unlocking during the lock delay actually cancels the pending disable      done 09 Sep - narrow scope only, see C2/C3 for what this does not cover

   C. FOUND WHILE FIXING B1/B2 - BIGGER THAN B1/B2's OWN SCOPE
   [x] C1  Protection modes (Airplane Mode) ignore hotspot entirely                   done 09 Sep - Airplane Mode only; Battery Saver reasoned safe, not verified live
   [x] C2  B2's cancel is skipped exactly when it matters most, and a filtered-out     done 10 Sep - both parts built. 6 more findings surfaced while
       feature never gets re-added even after the reason for filtering ends            checking it, all pre-existing/adjacent - NEEDS A /phi:plan PASS
   [x] C3  B2 only protected the ScreenStateReceiver path                             done 09 Sep - PrivacyAccessibilityService and the service restart path now covered too

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

Closed 09 Sep, Doru's call: the code itself is correct - `pkg!="android"` excludes exactly and
only the literal package it was written to exclude, nothing broader, verified earlier this round
against both real and synthetic data. What was never resolved is empirical (does GMS or an OEM
service actually hold this open on a retail phone), not a code defect, and Doru decided that
question is better answered by real user reports than by chasing down test hardware. If a report
ever says Location stops disabling on a specific phone, this is where to look first.

depends on: none    touches: ConnectionStateChecker.kt:207-213    [Verified in code - the
  exclusion does what it claims; the broader real-world question is deferred, not unresolved code]

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
likely to share 26's problem than not.

Closed 09 Sep, Doru's call: the parsing code (the awk pattern matching op headers and Package
lines) is not version-gated in any way - it is the same generic text match for every Android
version, with no code path that could behave differently on 24-25 specifically versus 26-33. If
those 2 versions' dumpsys output genuinely differs in shape, that is a fact about the OS, not
something more code-reading here would catch - left to a user report from an SDK 24-25 device if
it ever surfaces, same reasoning as A1's confirmed break (a NOBLOCKS answer either way, never a
wrong "in use").

## A5  Watch a real navigation app trigger this live, end to end

Checked directly, not just assumed: neither the real device nor any available emulator has
Google Play Services or a navigation app installed (252 real-device packages checked). Closed 09
Sep, Doru's call: not pursuing further. The underlying mechanism (AppOps' "Running start at"
marker) was already proven correct in both directions this session - a synthetic active entry is
correctly caught, a genuinely idle device correctly isn't - so the missing piece here is only the
reassurance of watching one specific real app do it, not evidence the logic is wrong. Same
reasoning as A2 and A4: a real-world miss would surface as a user report, not as something more
test setup on this machine would have found.

depends on: none    touches: none    [Verified at runtime - confirmed blocked, not just assumed]

## B1  A hotspot started during the lock delay survives being re-checked

DONE 09 Sep. PrivacyActionWorker.kt used to sample whether a hotspot is active once, before the
lock delay, and never again - a hotspot started during the delay window could still have WiFi/
Mobile Data switched off underneath it. Fixed by re-applying the same unconditional hotspot check
after the delay, inside the same block that already re-checks per-feature "only if unused" state
(added for #20). Mirrors the pre-existing check's own logic exactly, does not duplicate it.

depends on: none    touches: PrivacyActionWorker.kt:166-172 (unchanged, the original pre-delay
  check), :329-345 (new, the post-delay re-check)    [Verified in code + Verified at runtime -
  build and full test suite green; no live hotspot-during-delay device run, see C2]

## B2  Unlocking during the lock delay actually cancels the pending disable

DONE 09 Sep, narrow scope. ScreenStateReceiver's ACTION_USER_PRESENT handler used to enqueue the
unlock-side work but never cancel the still-pending lock-side one. Fixed: it now cancels the
pending lock work too, guarded by the same `sensorDisableInProgress` check the other 3 producers
already use before they'd REPLACE that work (#G1) - cancelling a sensor disable that's actively
running would interrupt it mid-command with sensors left on and no error shown anywhere.

depends on: none    touches: ScreenStateReceiver.kt:50-66 (the ACTION_USER_PRESENT branch)
  [Verified in code + Verified at runtime - build and full test suite green; no live unlock-
  during-delay device run, see C2/C3 for why a live run of this specific fix would not have
  settled the concerns this round actually found]

## C1  Protection modes (Airplane Mode) ignore hotspot entirely

DONE 09 Sep. B1 (and the original pre-delay check it mirrors) only ever gated WIFI and
MOBILE_DATA - Airplane Mode, a full radio kill switch, could still take a live hotspot down on
lock regardless. Fixed: the shared post-delay hotspot check (already used by B1) now also skips
enabling Airplane Mode while a hotspot is active, matching the "keep it on" answer this project
already gave WiFi/Mobile Data (#34) - going with that same precedent rather than asking, since
it was the obvious consistent choice.

Corrected mid-round by this round's own adversarial review: the first version of this fix still
only ran when `lockDelay > 0`, inherited from B1's own gate - but unlike WiFi/Mobile Data (which
already had a pre-delay check to fall back on), Airplane Mode never had ANY hotspot check before
this fix, at any delay, so gating it the same way silently left 0-second-delay users with no
protection at all. Now checked whenever Airplane Mode is configured, regardless of delay.

Battery Saver deliberately does NOT get the same treatment. Android documents low-power mode as
throttling background activity, not disabling radios - unlike Airplane Mode this was not
verified live against a real hotspot (this device's shell lacks the permission to start one via
`cmd wifi start-softap` - `SecurityException: ... android.permission.MAINLINE_NETWORK_STACK`),
so it rests on documented platform behaviour, not a test.

depends on: none    touches: PrivacyActionWorker.kt (the shared hotspot re-check, and the
  protection-modes loop)    [Verified in code + Verified at runtime - build and full test suite
  green; Battery Saver's own claim is Inferred from documented Android behaviour, not tested]

Also corrected: `strings.xml`'s `airplane_mode_info_message` and README/full_description's
Airplane Mode bullets all promised "disables all radios, regardless of individual settings" with
no exception - now literally false for anyone tethering. The in-app string is fixed. README.md
and the fastlane store listing still say the old, now-slightly-imprecise thing - short marketing
bullets, not touched, flagged here rather than edited without asking.

## C2  B2's cancel window sits exactly where the most common real trigger lands, and a
      feature filtered out once is never reconsidered even after the reason ends

DONE 10 Sep, both parts. Adversarial round found nothing wrong in either part itself - it found 6
real things adjacent to them instead, all pre-existing or needing a decision beyond this fix's own
scope, filed below rather than fixed. Read that list before assuming this is fully closed.

**Part 1, built as recommended.** `PrivacyActionWorker`'s companion object gained
`@Volatile var lastUnlockAtMillis: Long`. A new shared function, `PendingLockWork.recordUnlock()`,
writes it via `SystemClock.elapsedRealtime()` (immune to the wall clock moving, and unlike
`uptimeMillis` survives deep sleep); all 4 real unlock-detection call sites now call it
unconditionally, before their own `sensorDisableInProgress`-guarded cancel - ScreenStateReceiver's
ACTION_USER_PRESENT and ACTION_SCREEN_ON handlers, PrivacyAccessibilityService's unlock branch,
and PrivacyMonitorService's restart catch-up. `doWork()` records its own `thisLockCycleStartedAt`
at the very start of the lock branch and checks `lastUnlockAtMillis > thisLockCycleStartedAt` at
one checkpoint, placed right after the delay-or-no-delay branch converges - this covers both the
`lockDelay==0` path (its only re-validation of any kind) and, for `lockDelay>0`, acts as a second
signal alongside the existing `isStillLocked` re-check, in one place instead of two.

Found and fixed while wiring this up: ACTION_SCREEN_ON's own cancel call had no
`sensorDisableInProgress` guard at all, unlike the other 3 call sites - a screen-on fast enough
could have interrupted an in-flight sensor disable via `cancelUniqueWork()`, the same race #G1
already guards everywhere else. Now guarded the same way.

**Part 2, built as recommended.** `featuresToDisable` is now categorised by type only, unfiltered,
in one pass. The sensor group gets its own immediate `filterByOnlyIfUnused()` call, right before
its no-delay disable - functionally identical to before (this is what the shared pre-delay filter
already amounted to for sensors, just now explicit). Regular features and protection modes are
filtered together exactly once, in the same shared `filterByOnlyIfUnused()` function, at the
latest safe moment - the point B1/C1's hotspot re-check already ran at. The pre-delay filter and
the old post-delay re-narrowing pass are both gone; there is one filter, one moment, and it can
re-include a feature that was excluded earlier in the same cycle if the reason no longer applies,
closing the subtract-only defect at its root rather than patching around it again.

depends on: none    touches: worker/PrivacyActionWorker.kt (both parts), util/PendingLockWork.kt
  (new recordUnlock()), receiver/ScreenStateReceiver.kt (2 call sites + the missing guard),
  accessibility/PrivacyAccessibilityService.kt (1 call site), service/PrivacyMonitorService.kt
  (1 call site)    [Verified in code + Verified at runtime - build and all 17 existing tests plus
  the awk regression script green; both invariants (unlock-during-cycle always caught; filter
  runs exactly once and can re-include) held against all 3 adversarial reviewers; no live
  lock/unlock device run, same depth as B1/C1/C3's own verification]

**6 real findings surfaced while checking this, all filed rather than fixed** - each is either
pre-existing (in code this fix did not touch, or inherited unchanged from C1/C3) or needs a
decision beyond a timestamp-style safety net, so none cleared the bar to fix inline this round.
NEEDS A /phi:plan PASS to give these their own IDs:

- ScreenStateReceiver's ACTION_SCREEN_ON "not locked" branch cancels the pending lock job but,
  unlike the other 3 detectors, never enqueues the unlock-side re-enable - if the device is woken
  during Android's own pre-keyguard grace period (so ACTION_USER_PRESENT never follows), whatever
  the immediate sensor block already disabled can stay off indefinitely
- ACTION_USER_PRESENT enqueues the unlock-side re-enable as a separate, concurrent job while
  correctly leaving an in-flight sensor disable running (#G1) - the re-enable can finish first,
  leaving camera/mic off after a real, confirmed unlock
- PrivacyAccessibilityService's unlock branch can miss an unlock permanently, with no retry, if
  the window-class-change event is delivered before KeyguardManager's own state catches up -
  matters only when PrivacyMonitorService is dead, which is this detector's whole reason to exist
- `PendingLockWork.cancel()` logs "Cancelled pending lock work" even when nothing was pending
  (the common case), and never reads the async `Operation` WorkManager's cancel returns, so a
  genuine cancel failure can never be logged either
- `PrivacyMonitorService.isScreenCurrentlyLocked()` defaults to "unlocked" on an exception reading
  KeyguardManager/PowerManager, unlike every other lock-state read in this app (which defaults to
  "locked"). Pre-existing, but this round's own wiring means that wrong default can now also
  stamp `lastUnlockAtMillis` and cancel real pending protection, not just skip a re-enable. Small,
  ready fix for later: change that one `false` to `true`
- The hotspot check in the lock branch is sampled once, then used both for the regularFeatures
  filter and, after a real `disableFeatures()` round-trip, the protection-modes loop's Airplane
  Mode decision - a hotspot starting or stopping in that gap gets the stale verdict. Inherited
  unchanged from C1, not introduced or worsened by this round's restructure
- `lock_delay_warning_message` (and the matching fastlane store-listing line) says camera
  "triggers immediately (if it is not in use)" - camera has no in-use detection at all
  (`ConnectionStateChecker.kt` hardcodes it to never be "in use"), so this promises an interlock
  that does not exist and will cut the camera mid video-call. Unrelated to C2, found incidentally

## C3  B2 only protected the ScreenStateReceiver path

DONE 09 Sep, with a real regression caught and fixed mid-round - worth reading, not just the
outcome. PrivacyAccessibilityService had its own, fully independent way to arm a lock cycle (any
"Keyguard"/"LockScreen"-classed window, deliberately ungated on real keyguard state - it has to
act before that can be confirmed, see its own doc comment) with no equivalent way to notice an
unlock, and PrivacyMonitorService's own restart catch-up found the device unlocked without ever
cancelling a stale pending lock job either - both closed, using one new shared function
(`util/PendingLockWork.kt`) so all 3 unlock-detection paths in this app cancel the same way.

The regression: the first version of PrivacyAccessibilityService's new unlock detection fired on
ANY window that wasn't lock-screen-classed, with no check of real keyguard state - an incoming
call, the lock screen's own camera shortcut, an alarm, the notification shade pulled down ON the
lock screen, an always-on-display or OEM overlay would all have looked like "unlocked" to it.
Caught by this round's own adversarial review, which correctly called it a real defect, not an
adjacent one: it could have cancelled a genuine pending disable and re-enabled sensors on a phone
that was never actually unlocked. Fixed the same way ScreenStateReceiver's own ACTION_SCREEN_ON
handler already does it - confirm real `KeyguardManager.isKeyguardLocked()` state before acting,
not just the window-class heuristic alone. Unlike the lock-detection branch (which has a real
timing reason it can't wait for confirmation), the unlock branch has no such pressure, so this
costs nothing.

Also corrected: `strings.xml`'s `accessibility_service_description` (shown to the user at the
exact moment they grant this permission in Android Settings) and the in-app card's mirrored text
both still said the service "only detects lock screen appearance" - both updated to describe the
unlock detection too, so the disclosure a user reads before granting this permission matches what
it now does.

depends on: none    touches: accessibility/PrivacyAccessibilityService.kt, util/PendingLockWork.kt
  (new), service/PrivacyMonitorService.kt, receiver/ScreenStateReceiver.kt (refactored to use the
  shared function), strings.xml, layout/card_accessibility_service.xml    [Verified in code +
  Verified at runtime - build and full test suite green; the false-positive-while-locked
  regression was live-reasoned through and fixed, not observed on a real device via a live wait]

Residual, correctly still part of C2 not C3: a genuine, keyguard-confirmed unlock while
`sensorDisableInProgress` is true still skips the cancel (correctly, to protect the in-flight
sensor disable) but the unlock-side work still gets enqueued regardless, on BOTH paths now
(ScreenStateReceiver and PrivacyAccessibilityService) - the same timing gap C2 Part 1 already
describes, just reachable from one more place after this fix. C2 Part 1's recommended fix closes
it for all paths at once, since it checks inside `doWork()` itself rather than per-detector.
