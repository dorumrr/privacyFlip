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
   [ ] C1  Protection modes (Airplane Mode, Battery Saver) ignore hotspot entirely    NEEDS YOUR CALL - real gap in the original #34 feature, not new
   [ ] C2  B2's cancel is skipped exactly when it matters most: the first seconds     the most likely real trigger (accidental lock, instant unlock) lands here
       after a lock, and a filtered-out feature (hotspot or in-use) never gets
       re-added even after the reason for filtering it out has passed
   [ ] C3  B2 only protects the ScreenStateReceiver path - PrivacyAccessibilityService  a second, independent lock-work source with no unlock-cancel of its own
       can arm a whole new lock cycle with no screen-state gate at all, and
       ScreenStateReceiver itself is only registered while a service that can die is alive

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

## C1  Protection modes (Airplane Mode, Battery Saver) ignore hotspot entirely

Found by this round's own adversarial review, checking B1's own new code. B1 (and the original
pre-delay check it mirrors) only ever gate WIFI and MOBILE_DATA. Enabling Airplane Mode on lock
kills a hotspot's radio outright, hotspot check or not - the #34 protection this app advertises
never covered protection modes, in either the original feature or B1's fix. Not something B1 was
ever scoped to fix; a real gap in the original feature, found incidentally.

depends on: none    touches: PrivacyActionWorker.kt (protection-mode enable block, after the
  regular-features block)    [Verified in code]
NEEDS YOUR CALL: worth closing, and is "keep the hotspot on" or "warn the user Airplane Mode will
  kill it" the right behaviour for someone who explicitly asked for Airplane Mode on lock?

## C2  B2's cancel window sits exactly where the most common real trigger lands, and a
      feature filtered out once is never reconsidered even after the reason ends

Two related findings from this round's adversarial review, both about the SHAPE of the fix
rather than a bug in it:

Camera/mic disable at the very start of every lock (immediately, no delay - #F1b). That is
exactly when `sensorDisableInProgress` is true, which is exactly when B2's guard skips the
cancel - and an accidental lock immediately followed by unlocking again (fingerprint, a pocket
press-and-release) lands squarely in that first-second window. The unlock-side work still gets
enqueued either way; what's missing is only the cancel, so the pending lock action survives and
can still fire at the end of the lock delay on a phone the user is actively holding. The default
lock delay is 10 seconds, not an obscure edge case.

Separately: once a feature is filtered out of `regularFeatures` at lock time - for being in use,
or for a hotspot being active - nothing re-adds it if the reason stops applying before the delay
ends (hotspot turned off, app stopped using the feature). This is not specific to hotspot or to
B1; it is how the whole filter-once-at-lock-time design already works, for every feature, and
predates both #20 and B1.

depends on: none    touches: PrivacyActionWorker.kt (the whole filter/re-check structure, not one
  line)    [Verified in code - both claims traced through the actual control flow this round]
NEEDS YOUR CALL: closing the first part properly needs the lock and unlock workers to coordinate
  around sensorDisableInProgress instead of one guard checked at one instant - real design work,
  not a quick patch. The second part needs the filter to become re-evaluated rather than
  progressively narrowed, which is a bigger structural change than B1/B2's own scope.

## C3  B2 only protects one of two independent paths that can arm a lock cycle, and only
      while the service that registers it is alive

Two more findings from the same review round, about completeness rather than correctness:

ScreenStateReceiver is registered only dynamically, only from PrivacyMonitorService's own
lifecycle (already known from this project's own history - no manifest entry exists for it).
While that service is dead, ACTION_USER_PRESENT reaches no registered receiver at all, so B2's
new cancel cannot run - unrelated to B2 itself, but it means B2's protection has the same uptime
dependency the rest of this app's screen-state handling already has.

PrivacyAccessibilityService has its own, entirely independent way to arm a lock cycle - any
window whose class name contains "Keyguard" or "LockScreen" (deliberately ungated on real
keyguard state - see PrivacyAccessibilityService.kt's own doc comment for why). A prior false
positive in this exact detector was already found and fixed once (#26, the notification shade).
If a similar false positive is ever hit again, it can arm a brand new lock cycle after B2's
cancel has already run, and B2 has no way to see or prevent that - it only reacts to the
ScreenStateReceiver path.

depends on: none    touches: PrivacyAccessibilityService.kt (isLockScreenClass, triggerEarly
  PrivacyActions), util/ScreenStateReceiverManager.kt    [Verified in code]
NEEDS YOUR CALL: the service-uptime dependency is accepted risk this project already carries
  elsewhere (ServiceHealthWorker's 15-minute restart is the existing mitigation). The
  accessibility path is a real, separate gap - whether it is worth chasing depends on how often
  #26-style false positives actually recur, which only real usage will show.
