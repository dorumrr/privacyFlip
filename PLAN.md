# PrivacyFlip - open work

Tier: 3        how hard each step is worked. 1 quick, 2 normal, 3 full. Default 3.

```
   [ ]  not started        [~]  in progress        [x]  done

   PART 1: v2.1.3 - SHIPPED (tag v2.1.3, commit ebc2b5b, pushed). Closed, kept here as record.
   [x] A1  Accessibility service can't disable sensors    Shipped. A post-release audit then found
                                                             the ORDINARY (non-accessibility) lock
                                                             path likely never disables sensors at
                                                             all, a separate, pre-existing gap this
                                                             fix didn't reach. See F1a/F1b below.
   [x] A2  Delay fix lets a screen blip cancel lock        Shipped, verified live twice.
   [x] B1  Exempt-apps picker sees every app               Shipped, verified live: 111 -> 252.
   [x] B2  WiFi disable skips an active hotspot             Shipped, off-state verified live. Audit
                                                             found the guard only covers WiFi itself,
                                                             not Mobile-Data-as-upstream. See G3.
   [x] E1  Native Bluetooth API + permission flow          Shipped, verified live end to end. Audit
                                                             found the permission isn't re-checked
                                                             after being granted once. See G2.

   PART 2: POST-RELEASE AUDIT - HIGH SEVERITY
   [x] F1a Confirm live: does the ordinary lock path        CONFIRMED LIVE on your real phone:
           ever disable camera/mic at all?                   5/5 regular features disabled,
                                                             camera+mic silently skipped every
                                                             time. Bug is real.
   [x] F1b Fix it, if F1a confirms it                        VERIFIED LIVE: same phone, same real
                                                             lock scenario as A1's failing run,
                                                             now reads "Camera: SUCCESS",
                                                             "Microphone: SUCCESS", 6/6 features
                                                             disabled total. Watched it fail, then
                                                             watched the fix pass, same setup.
   [x] F2  "Local storage only" is not true on Android        Fixed: kept backup, corrected the
           12+ while allowBackup stays on                    store text (EN+RU) instead, per your
                                                             call. Not yet re-verified live.
   [x] F3  This session's own changelog overclaims a fix     24.txt reworded, no longer states the
                                                             fix as settled fact. GitHub reply to
                                                             #26/#35 still drafted, not sent (yours
                                                             to send).

   PART 3: POST-RELEASE AUDIT - MEDIUM SEVERITY
   [x] G1  3 different triggers can race and cancel a        Fixed: a shared in-progress flag, all
           sensor-disable that's still in progress            3 producers now check it before
                                                             REPLACE-ing. Builds clean, not
                                                             live-tested (can't force the timing).
   [x] G2  Bluetooth permission isn't re-checked after       Fixed: re-checked on every resume now,
           being granted once                                turns the setting back off if revoked.
   [x] G3  Hotspot guard only covers WiFi, not Mobile        Fixed: Mobile Data now covered by the
           Data as the hotspot's own upstream                 same guard as WiFi.
   [x] G4  A warning the app computes is never shown         Fixed: now shown inline between the
                                                             Camera and Microphone rows, your choice.

   PART 4: POST-RELEASE AUDIT - LOW SEVERITY
   [x] H1  Lock-screen matcher could false-fire on a         Left the matcher itself alone (too
           third-party app's own screen                       risky to narrow blind, could break
                                                             real OEM keyguards). Added the package
                                                             name to the log line instead, so a real
                                                             report would be traceable.
   [x] H2  Dual-SIM check can read the wrong SIM             Fixed: now follows the phone's own
                                                             active-data-SIM marker instead of
                                                             grabbing whichever line comes first.
                                                             Verified live the lookup lands on the
                                                             right SIM; both SIMs agree on this
                                                             phone so a genuinely wrong answer was
                                                             never observed to begin with.
   [x] H3  dev.sh points at an untracked "step 2"            Fixed: reworded to something true on
                                                             its own.

   PART 5: CARRIED OVER, NOT FROM THE AUDIT
   [x] I1  Fastlane changelog 23.txt trimmed under the       Fixed: 481 bytes/chars, both counted.
           F-Droid limit
   [x] J1  Regression tests for the status-parsing layer     Done: 12 tests, 0 failures. Covers
                                                             StatusParsingUtils and the new
                                                             MobileDataToggle dual-SIM logic,
                                                             including a test proving the OLD naive
                                                             matcher really would have gotten it
                                                             wrong, not just that the new one works.
```

A1, A2, B1, B2 and E1 are done and live in v2.1.3. Everything else below is new: 10 findings from
this session's post-release audit, plus the 2 items (I1, J1) that were never started before the
release and still aren't.

---

## Part 1 - v2.1.3, closed

Shipped as tag `v2.1.3`, commit `ebc2b5b`, pushed to `origin/main`. [Verified in code - `git log
origin/main..HEAD` returns nothing, the tag exists.] Nothing further scheduled here; the two gaps
this closing note points at (A1's real limit, B2's real limit) are now their own steps below,
F1a/F1b and G3, so they are tracked once, not twice.

---

## F1a  Confirm live: does the ordinary lock path ever disable camera/mic at all?

Depends on: none. Touches: `app/src/main/java/io/github/dorumrr/privacyflip/worker/PrivacyActionWorker.kt:78-92,197-227`

**What the code does today.** `isScreenCurrentlyLocked()` (`PrivacyActionWorker.kt:87`) is
`isKeyguardLocked || !isInteractive`. The sensor block has two separate ways to skip, and they mean
different things - the adversarial round caught me conflating them:

- `PrivacyActionWorker.kt:220-226` (the `else` of `if (!isDeviceLocked)` at line 198): fires when
  whoever enqueued the work already decided "locked" before the 75ms wait even starts. On a phone
  set to lock instantly when the screen turns off, `ScreenStateReceiver`'s own check
  (`keyguardManager.isKeyguardLocked` read right at the `ACTION_SCREEN_OFF` broadcast) will already
  be true, so this is the branch that runs, unconditionally, no re-check at all. This is not a new
  discovery - it's the exact, already-understood reason the Accessibility feature (A1) exists: the
  code comment in `PrivacyAccessibilityService.kt` says as much.
- `PrivacyActionWorker.kt:214` (inside `if (!isDeviceLocked)`, after the 75ms wait): fires when the
  device was NOT yet locked at enqueue time (a lock-delay phone, or the accessibility path, both of
  which pass `is_device_locked=false`), but the re-check 75ms later finds `isScreenCurrentlyLocked()`
  true anyway. For a plain `ACTION_SCREEN_OFF`-triggered run specifically, the screen is by
  definition already off by the time this fires (that's what triggered the broadcast), so the
  `!isInteractive` half of the formula is very likely already true regardless of the real keyguard
  state - meaning this re-check may be unable to ever say "still unlocked," even on a phone with a
  real lock delay that should give the app a genuine window.

Both branches end the same way: sensors don't get disabled, silently, no error shown. [Inferred -
this project has no record of what `isInteractive()` reports in the exact 75ms window this checks;
the reasoning is sound but nobody has watched it happen.]

**Proves it is done:** two real locks on your phone, recorded together, not separately:
1. Check your screen lock timeout setting first (Settings > Display > Lock screen, "instant" vs a
   delay) and note which one you have.
2. Turn the Accessibility feature OFF. Set camera and microphone to disable-on-lock. Lock the phone
   for real (power button). Read the debug log for exactly one of two lines: `"already locked at
   ACTION_SCREEN_OFF - cannot disable sensors"` or `"Keyguard engaged during stabilization -
   skipping sensors"` (bug, either way) versus `"SUCCESS disable Camera"` / `"SUCCESS disable
   Microphone"` (works).
3. If you have a lock delay available, repeat with it set to a few seconds, to test the second
   branch specifically.

A single "SUCCESS" line does not by itself prove the fix works later - see F1b's own proof section
for why.

Skill: none, this is a live test. `/phi:fix` only follows if this confirms the bug.

---

## F1b  Fix the ordinary-lock-path sensor gate, if F1a confirms it

Depends on: F1a. Scheduled directly after it, not later - the evidence goes stale if other steps
land first and nobody re-confirms it still applies the same way.

**Proves it is done:** re-run the exact same test as F1a, same phone, same lock-timeout setting,
and watch it flip from the skip line to `"SUCCESS disable Camera"` / `"SUCCESS disable Microphone"`.
Watching the SAME setting go from broken to fixed is the proof; a fresh run that happens to show
SUCCESS on a different setting doesn't show the fix did anything.

Skill: `/phi:fix`

---

## F2  "Local storage only" is not true on Android 12+ while backup stays on

Depends on: none. A product decision comes before the code change. Touches:
`fastlane/metadata/android/en-US/full_description.txt:63-64` (+ `ru/full_description.txt`),
`app/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml`,
`app/src/main/AndroidManifest.xml:34`

**What the code does today.** The store listing says "Zero telemetry - No data sent to external
servers" and "Local storage only - All settings stored on device"
(`full_description.txt:63-64`). But `allowBackup="true"` (`AndroidManifest.xml:34`) plus THREE
separate inclusion points - `backup_rules.xml:4`, and `data_extraction_rules.xml`'s cloud-backup
block (line 5) AND its separate device-transfer block (line 13) - all say to include every
SharedPreferences file except one (`root_status.xml`) in Android's own backup. [Verified in code -
read all three files directly.] One extra wrinkle the adversarial round found: `root_status.xml` is
never actually written by anything in this app (the only file it opens is the one
`PreferenceManager.kt:15` names), so the one "exclude sensitive data" line excludes a file that
doesn't exist - not a new problem, just means nothing is currently excluded in practice.

**The trap in fixing only part of this:** Android 12+ (this app's own build target) ignores
`backup_rules.xml` entirely once `data_extraction_rules.xml` is present - editing only the older
file changes nothing on any modern phone. Both blocks in `data_extraction_rules.xml` (cloud-backup
AND device-transfer are separate channels) have to be addressed together with it, or the underlying
behaviour stays exactly as it is while the fix looks complete. [Verified in code - both blocks
independently declare the same include.]

**NEEDS YOUR YES.** Two honest directions, not mine to pick:
- Turn backup off for real (`allowBackup="false"`, or exclude ALL sharedpref in every one of the
  three spots above) - makes the existing claim true.
- Or correct the store text to describe what actually happens today - makes the claim honest
  without changing behaviour.

Proves it is done: whichever direction, re-read every one of the three inclusion points plus the
manifest flag together, not just one file, and confirm they now agree with whatever the store text
says.

Skill: `/phi:fix`, after you pick a direction.

---

## F3  This session's own changelog overclaims a fix

Depends on: none. Touches: `fastlane/metadata/android/en-US/changelogs/24.txt:1`

**What's wrong.** `changelogs/24.txt` says plainly: "camera and mic actually get blocked, without
misfiring on the notification shade." PLAN.md's own record (written the same session) says the
positive case was never actually observed - this session's only test phone can't produce the
signal - and the "no misfiring" half was only checked against the PREVIOUS version of the matcher,
not the one that shipped.

**One thing worth being honest about before editing anything:** `24.txt` describes a version
(`v2.1.3`) that is already tagged and pushed. Editing the file in git now may not change anything
anyone already sees - F-Droid/IzzyOnDroid typically build from the tagged commit, which won't move.
The part that actually reaches anyone still watching is a reply on the GitHub issues this claim
touches (#26, #35), being honest about what's confirmed and what isn't, and not repeating the same
overclaim in the NEXT changelog.

Proves it is done: the next changelog this project writes doesn't restate something as fact that
hasn't been observed, and #26/#35 have an honest reply matching what's actually known. Not "24.txt
is edited," since that alone may reach nobody.

Skill: `/phi:fix` for the wording pattern going forward; the GitHub replies are Doru's own words to
send, same as always.

---

## G1  Three different triggers can race and cancel a sensor-disable in progress

Depends on: none strictly, though it touches the same file area as F1b - worth landing with a clear
head about what F1a found, not blocking on it. Touches:
`app/src/main/java/io/github/dorumrr/privacyflip/accessibility/PrivacyAccessibilityService.kt:126-132`,
`app/src/main/java/io/github/dorumrr/privacyflip/receiver/ScreenStateReceiver.kt:100-104`,
`app/src/main/java/io/github/dorumrr/privacyflip/service/PrivacyMonitorService.kt:212-216`

**What the code does today.** All three enqueue WorkManager work under the same unique name
(`"privacy_action_lock"`) with `ExistingWorkPolicy.REPLACE`. The adversarial round found the third
one - `PrivacyMonitorService`, which fires at service startup - that this plan's first draft
missed. [Verified in code - all three read directly.] If the accessibility-triggered worker is
still disabling sensors when a later one REPLACEs it, the disable is cancelled mid-flight,
silently.

Proves it is done: honestly, hard to observe directly - the sensor path's only "delay" is a fixed
75ms, too narrow to reliably time by hand, and a live attempt can fail for F1a's reason instead of
this one, muddying the result. The real proof is in the fix design itself: change how the three
producers coordinate (a status flag the disable step checks before yielding to REPLACE, or moving
sensor-disable outside the racy unique-work entirely) so that cancellation can no longer land
mid-disable, then confirm by reading the changed code path by hand rather than chasing a timing
window blind.

Skill: `/phi:fix`

---

## G2  Bluetooth permission isn't re-checked after being granted once

Depends on: none. Touches:
`app/src/main/java/io/github/dorumrr/privacyflip/ui/fragment/MainFragment.kt:476,~950`,
`app/src/main/java/io/github/dorumrr/privacyflip/util/ConnectionStateChecker.kt:140-146`

**What the code does today.** `BLUETOOTH_CONNECT` is checked only at the moment the checkbox is
tapped. Every later app resume restores the checkbox purely from the saved preference
(`onResume -> MainViewModel.kt:484 -> MainFragment.kt:475,997`), with no permission read anywhere
on that path. [Verified in code, full call chain read.] If the permission is revoked later, the
checkbox stays ticked, and `ConnectionStateChecker` silently treats Bluetooth as "not connected"
(one warning log line), so it gets disabled even mid-call.

Proves it is done: grant the permission, tick the box, revoke the permission in system Settings
(this normally kills the app process - that's expected, test the COLD START after, not a
background switch), reopen the app - the checkbox now shows unticked or a clear warning, not
silently ticked with nothing behind it.

Skill: `/phi:fix`

---

## G3  Hotspot guard only covers WiFi, not Mobile Data as the hotspot's own upstream

Depends on: none. Touches: `app/src/main/java/io/github/dorumrr/privacyflip/worker/PrivacyActionWorker.kt:148-163`

**What the code does today.** The hotspot-active guard filters only `PrivacyFeature.WIFI` out of
the disable list. `PrivacyFeature.MOBILE_DATA` stays free to be disabled in the same run - and
`isFeatureInUse(MOBILE_DATA)` is hardcoded `false` regardless (`ConnectionStateChecker.kt:49-53`),
so it always gets disabled if configured to. A phone hotspot's actual internet usually comes from
mobile data, so a user with both "WiFi: disable on lock" and "Mobile Data: disable on lock" set,
plus an active hotspot, keeps the WiFi radio up (protected) but loses the data feeding it - while
the app logs "WiFi skipped: hotspot is active" as if it fully protected the hotspot.

Proves it is done: two paired runs, not one - hotspot active with Mobile Data also set to disable:
the log now shows Mobile Data skipped too, hotspot keeps working. Hotspot NOT active, same
settings: Mobile Data still disables normally. Both halves needed, or a fix that just always skips
Mobile Data would silently break the normal case.

Skill: `/phi:fix`

---

## G4  A warning the app computes is never shown

Depends on: none, though where it should appear is a small call worth naming, not assuming.
Touches: `app/src/main/java/io/github/dorumrr/privacyflip/ui/viewmodel/MainViewModel.kt:808`,
`app/src/main/res/layout/card_experimental_features.xml:55`

**What the code does today.** `showLockDelayWarning` is computed and written to UI state, with a
debug log claiming "WARNING WILL BE DISPLAYED TO USER." `card_experimental_features.xml` (the
layout holding the `lockDelayWarning` view) has zero references anywhere in the app - no
`<include>`, no generated-binding use. [Verified in code - searched the whole layout directory.]
Nobody who hits this condition (an instant-lock or short-delay setup with camera/mic protection on)
is ever actually told their protection may not work.

Proves it is done: two runs, not one - an incompatible setup shows the warning somewhere real in
the app. A compatible setup does NOT show it. Both halves needed, or a fix that shows the warning
unconditionally would "pass" the first check while lying the rest of the time.

Skill: `/phi:fix`, after Doru says where in the UI it should land.

---

## H1  Lock-screen matcher could false-fire on a third-party app's own screen

Depends on: none. Touches:
`app/src/main/java/io/github/dorumrr/privacyflip/accessibility/PrivacyAccessibilityService.kt:103-106`

`isLockScreenClass()` is a bare substring check for "Keyguard"/"LockScreen," no package scoping.
Same shape of risk as the #26 StatusBar false positive this session already fixed, just narrower -
a third-party app whose own screen class name happens to contain either word could false-trigger a
lock action while it's in active use. [Verified in code - the mechanism is real.] Real-world
frequency: no known example found, likely rare. [Needs confirmation - there is no way to prove a
negative here beyond watching for it.]

Proves it is done: honestly, this can't be cleanly proven the way most steps can - there's no known
counter-example app to test against today. If real-world reports ever name a false-fire, that's the
proof this needs fixing; until then, this stays a documented, low-odds risk, not a confirmed bug.

Skill: `/phi:fix`, low priority.

---

## H2  Dual-SIM mobile-data check can read the wrong SIM

Depends on: none. Touches: `app/src/main/java/io/github/dorumrr/privacyflip/privacy/MobileDataToggle.kt:29-33`

`grep -m1 mIsDataEnabled` takes the FIRST matching line, with nothing tying it to the SIM actually
carrying data. Confirmed live on a real dual-SIM phone: 2 such lines exist. Both currently agree on
that phone, so no wrong answer has been observed yet - the mechanism is real, a demonstrated wrong
answer isn't.

Proves it is done: deliberately set the two SIMs to disagree (one with mobile data on, one off,
whichever is NOT first in the dump), and confirm the app reads the one actually carrying data -
plus record which command answered (the new telephony check, or the old settings fallback), so a
pass can't be credited to the fallback quietly doing the work instead.

Skill: `/phi:fix`

---

## H3  dev.sh points at an untracked "step 2"

Depends on: none. Touches: `dev.sh` (release case block, "Current Commit" summary line)

The release summary prints "(will change after step 2)," naming a step that exists in no tracked
file - the likely runbook is itself gitignored. Developer-facing only, but exactly the kind of
mix-up the new release guard exists to prevent (tagging the wrong commit).

Proves it is done: the printed instruction matches a real, findable step, or is removed.

Skill: `/phi:fix`

---

## I1  Fastlane changelog 23.txt trimmed under the F-Droid limit

Depends on: none. Touches: `fastlane/metadata/android/en-US/changelogs/23.txt`

Still 635 bytes, describing the already-released v2.1.2. The adversarial round found the original
proof ("wc -c reports 500 or fewer") may not actually settle this: the F-Droid limit is likely
CHARACTERS, and `wc -c` counts bytes - 14 of the 24 files already in this changelogs folder contain
non-ASCII text, where the two counts differ. The original issue's own wording (#32) said "634
chars," suggesting the tool THEY hit counts characters, not bytes.

Proves it is done: check both a character count and a byte count come in under 500 for the actual
file being trimmed, and that the text still reads as complete changelog content, not cut off
mid-word.

Skill: `/phi:fix`

---

## J1  Regression tests for the status-parsing utilities

Depends on: cleanest after F1b, G1 and H2 land, so tests target settled code rather than code about
to change. Touches: a new test source set (still none exists - checked again this session).

Not from a filed issue - covers the exact bug class that hit Mobile Data earlier this project's
history: a status field whose own NAME contains the state word, breaking a naive
`.contains("enabled")`-style check. The adversarial round tried to break this differential and
could not - a crafted `"mIsDataEnabled=false"` genuinely fails the naive matcher and passes the
current regex - confirming the core test idea is sound. One coverage gap it did find: this doesn't
yet cover `StatusParsingUtils.parseStandardOutput`'s own fallback path, or a true-positive
no-regression case (a genuinely-enabled reading still reads as enabled). Both added to scope.

Proves it is done: a test fails against the old, simpler matcher and passes against the current
parser, in both directions, plus the fallback path and a true-positive case are each covered too.

Skill: `/phi:create-tests`

---

Non-goals (unchanged from before): #36 time-based scheduling, #37 per-app permission revocation
instead of the global toggle, Battery Saver's missing checkbox label (#21) - all real feature/UX
requests, none of them steps in this plan.

Also open, not steps here: #20 (location cutting off navigation, root cause still unconfirmed), the
rest of #21's "over-firing" report (needs fresh logs), #22 (NFC - code fix already shipped, just
needs a nudge to the reporters), #33 (a question, not a bug, needs a reply not a step).
