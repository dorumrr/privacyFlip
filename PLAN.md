# PrivacyFlip - open work

Tier: 3        how hard each step is worked. 1 quick, 2 normal, 3 full. Default 3.

```
   [ ]  not started        [~]  in progress        [x]  done

   A. FIX WHAT THIS PLAN RUN FOUND BROKEN
   [~] A1  Accessibility service can't disable sensors    redesigned, deployed. Code-verified only -
                                                            this test phone never fires the accessibility
                                                            event at all, real lock or shade, so the
                                                            positive case can't be observed here.
   [x] A2  Delay fix lets a screen blip cancel lock        VERIFIED LIVE, twice: a real screen blip
                                                            mid-wait no longer cancelled the disable,
                                                            and it still completed 5/5 both times.

   B. LAND WHAT'S SAFE
   [x] B1  Exempt-apps picker sees every app, not just     widened to QUERY_ALL_PACKAGES, your call.
           ones with a launcher icon                        Verified live: 111 -> 252 packages seen.
   [~] B2  WiFi disable skips an active hotspot             off-state verified live. On-state still
                                                            needs a real hotspot switched on once.

   C. SMALL CONFIRMED FIX
   [ ] C1  Fastlane changelog 23.txt trimmed under 500      635 bytes today. Ready any time.

   D. HARDEN THE PARSING LAYER
   [ ] D1  Regression tests for status-parsing utilities    not requested by an issue - flagging it

   E. BLUETOOTH: STOP GUESSING AT DUMPSYS TEXT
   [x] E1  Native Bluetooth API + permission flow          VERIFIED LIVE end to end: real earbuds
                                                            playing music, locked, log read
                                                            "CONNECTED (A2DP)" and skipped disabling
                                                            Bluetooth. Confirmed still connected after.
```

A2 and E1 are now fully proven, watched live on the real phone. Left: B2's hotspot-on reading (a
real hotspot switched on once), and A1's positive case, which this specific test phone cannot
exercise - proven safe by code, not by a live lock on this device.

---

## A1  Accessibility service can't disable sensors

Depends on: none. Touches: `app/src/main/java/io/github/dorumrr/privacyflip/accessibility/PrivacyAccessibilityService.kt`

**Fixed this run.** The earlier attempt at #26 (stop the notification shade from looking like a
lock) gated on `KeyguardManager.isKeyguardLocked()` before acting - correct against the
false-positive, but self-defeating: by the time that can be confirmed true, Android's own lock
restriction already blocks changing sensor privacy, so the service could never disable a sensor
again either way (traced by hand into `PrivacyActionWorker.kt:198-215`, both branches refuse once
locked).

The redesign removes that gate entirely - the service now acts immediately on a class-name match
again, same as before this session - and instead tightens what counts as a match
([PrivacyAccessibilityService.kt:71-79](app/src/main/java/io/github/dorumrr/privacyflip/accessibility/PrivacyAccessibilityService.kt#L71-L79),
matcher at
[PrivacyAccessibilityService.kt:103-106](app/src/main/java/io/github/dorumrr/privacyflip/accessibility/PrivacyAccessibilityService.kt#L103-L106)).
The bare `"StatusBar"` match is gone - that's the one the notification shade also fires, since it's
rendered by the same StatusBar-lineage classes in AOSP. `"Keyguard"` and `"LockScreen"` stay: both
are specific to the lock mechanism itself, and cover every real class name this project has on
record for a genuine lock-screen appearance. [Inferred - this project has no record of a device
whose real keyguard reports through neither word, but that is not the same as proof none exists.
The class name is still logged every time now, tagged with the real `isKeyguardLocked()` state
alongside it, purely for future diagnosis if a device ever needs a name added.]

Proves it is done: on a real phone, a genuine power-button lock produces both `"Lock screen
detected via Accessibility"` and a following `"✅ SUCCESS disable Camera"` / `"✅ SUCCESS disable
Microphone"` log line. Not yet run - needs one real lock. Pulling the notification shade while
unlocked still produces neither (confirmed earlier this session against the previous, gated
version; needs re-confirming against this rewritten matcher too, same test).

Skill: none further unless the real-lock test above fails, then back to `/phi:fix`.

---

## A2  Delay fix lets a screen blip cancel lock protection

Depends on: none. Touches: `app/src/main/java/io/github/dorumrr/privacyflip/receiver/ScreenStateReceiver.kt`,
`app/src/main/java/io/github/dorumrr/privacyflip/worker/PrivacyActionWorker.kt`

**Fixed this run.** The earlier attempt at #30b (always honour the configured lock delay, even on
an already-locked phone) was correct by itself, but exposed an existing interaction that used to
not matter: `ScreenStateReceiver`'s `ACTION_SCREEN_ON` handler cancelled the entire pending
lock-time work outright on any screen-on, locked or not - harmless when the already-locked case
used to disable instantly (nothing left pending to cancel), a real gap once that case started
waiting out the full delay instead.

The fix makes that cancel conditional
([ScreenStateReceiver.kt:55-70](app/src/main/java/io/github/dorumrr/privacyflip/receiver/ScreenStateReceiver.kt#L55-L70)):
only cancel the pending work if the phone is genuinely not locked at that moment (or has no lock
security configured at all, which behaves the same as before). A screen that merely wakes while
the keyguard is still showing no longer erases the wait. The delay-honouring change itself is
unchanged
([PrivacyActionWorker.kt:235-243](app/src/main/java/io/github/dorumrr/privacyflip/worker/PrivacyActionWorker.kt#L235-L243)).

**Proven live.** Locked the phone (10s delay), woke the screen at the 5.7s mark without unlocking,
let it sleep again: log read `"Screen turned ON but still locked - keeping pending lock work"` -
the old bug would have cancelled outright here. The wait then ran its full course and disabled
5/5 regular features. A second lock cycle straight after (fresh 10s wait, no blip) also completed
5/5. Unlock afterward correctly re-enabled everything, sensors instantly, WiFi/Mobile Data after
the unlock delay. [Verified at runtime - real device, real log, watched before and after]

Skill: none further.

---

## B1  Exempt-apps picker sees every installed app

Depends on: none. Touches: `app/src/main/AndroidManifest.xml`

**Done and verified live.** First landed as a narrow `<queries>` block (launcher apps only), which
solved the reported bug (WhatsApp, Telegram both have launcher icons) but left the app's own text
overselling itself - `ExemptAppsDialogFragment.kt:105-106` and `changelogs/23.txt` line 7 both
promise "exempt ANY app including system apps," which a launcher-only filter cannot keep. Widened
to `QUERY_ALL_PACKAGES` instead
([AndroidManifest.xml:20-26](app/src/main/AndroidManifest.xml#L20-L26)), confirmed present in a
built release APK via `aapt2 dump permissions`, and confirmed live on the test phone: the
app-exemption picker's own log went from `"Total installed packages: 111"` before this run's
widening to `"Total installed packages: 252"` after - more than double, including packages with
no launcher icon at all. [Verified at runtime]

`QUERY_ALL_PACKAGES` is a real, larger privacy footprint than a narrow `<queries>` block (any app
holding it can see the phone's full install list) - an unusual thing for a *privacy* app to
request, worth being upfront about if this is ever asked. Distribution is F-Droid/IzzyOnDroid, not
the Play Store, so the strict developer-console justification review that permission usually
triggers there does not apply here.

Proves it is done: (already run) re-run on any device and check two properties, not the raw count,
since 252 is one phone's own app inventory: a package with no launcher icon is now visible where
it was not before, and the picker's own count is close to that device's real total installed-app
count (`pm list packages | wc -l` as ground truth), not a small fraction of it.

Skill: none further - ready to commit.

---

## B2  WiFi disable skips an active hotspot

Depends on: none (A2 has now landed in the shared file, so this is no longer blocked). Touches:
`app/src/main/java/io/github/dorumrr/privacyflip/util/ConnectionStateChecker.kt`,
`app/src/main/java/io/github/dorumrr/privacyflip/worker/PrivacyActionWorker.kt`

Unchanged from before this round: `isHotspotActive()` reads `dumpsys tethering`'s `Tether state:`
section for a tetherable WiFi interface in `TetheredState`
([ConnectionStateChecker.kt:82-116](app/src/main/java/io/github/dorumrr/privacyflip/util/ConnectionStateChecker.kt#L82-L116)),
wired into the lock branch so WiFi is skipped when a hotspot is active regardless of the
per-feature "only if unused" setting
([PrivacyActionWorker.kt:148-163](app/src/main/java/io/github/dorumrr/privacyflip/worker/PrivacyActionWorker.kt#L148-L163)).
The "hotspot off" reading was confirmed live (`ap0 - AvailableState`, correctly read as NOT
ACTIVE). The "hotspot on" reading has still not been observed.

Two honest limits, unchanged, documented rather than fixed: `dumpsys tethering` only exists since
API 30, this app's minSdk is 24, so API 24-29 fails closed to the pre-fix behaviour rather than a
new regression; and Airplane Mode configured alongside WiFi-on-lock still undoes this protection
in the same run, same as it always did before this fix existed.

Proves it is done: two paired runs on the same phone. Hotspot off, lock: WiFi still disables.
Hotspot on, lock: a debug log line says WiFi was skipped because a hotspot is active, and WiFi is
still on afterward. Only the first pairing has been run so far.

Skill: none further unless the hotspot-on test above fails, then `/phi:fix`.

---

## C1  Fastlane changelog 23.txt trimmed under 500 chars

Depends on: none. Touches: `fastlane/metadata/android/en-US/changelogs/23.txt`

Unchanged, not started. Still 635 bytes (re-checked this round), describing versionCode 23
(already-released v2.1.2). No repo-side check catches this - `dev.sh`'s `fdroid-scanner` step
scans APK contents for tracking URLs, not changelog length (`dev.sh:476`). The limit comes from
F-Droid/IzzyOnDroid's own external build pipeline, per the issue's own wording.

Proves it is done: `wc -c fastlane/metadata/android/en-US/changelogs/23.txt` reports 500 or fewer,
and the file still reads as real, complete changelog text.

Skill: `/phi:fix`

---

## D1  Regression tests for the status-parsing utilities

Depends on: none strictly; cleanest now that A1, A2, B1, B2 have landed, so the tests target
settled code. Scope deliberately excludes Bluetooth parsing - E1 already replaced that with the
native API, so there is no dumpsys-parsing code left there to pin down. Touches (new): a test
source set (none exists today - checked again this round, no `test`/`androidTest` directory
anywhere under `app/src`), covering `StatusParsingUtils.kt` (`parseStandardOutput`,
`parseSensorPrivacyOutput`), `MobileDataToggle.kt`'s telephony regex, and
`ConnectionStateChecker.kt`'s `isHotspotActive()` regex.

Not from a filed issue - flagging it because all three exist specifically to avoid the exact bug
class that hit Mobile Data earlier this session: a status field whose own NAME contains the state
word (`"mIsDataEnabled"` contains "enabled" regardless of its value), which breaks a naive
`.contains("enabled")` check.

Proves it is done: a test exists that fails against the *old*, simpler `.contains()`-style matcher
on an input like `"mIsDataEnabled=false"` (name contains "enabled", value says otherwise), and
passes against the current parser - checked in both directions, so a genuinely-on value still
reads as on.

Skill: `/phi:create-tests`

---

## E1  Bluetooth "only if connected" - native API instead of dumpsys text

**Built, deployed, mostly verified live.** #28 (still reported broken after v2.1.2: connected
headphones get disconnected on lock anyway) traced back to `ConnectionStateChecker.kt`'s old
Bluetooth check - 4 stacked guesses at raw `dumpsys` text, wording not guaranteed stable across
phone makers.

Rewritten to use `BluetoothAdapter.getProfileConnectionState()` directly - no dumpsys parsing left
anywhere in this path
([ConnectionStateChecker.kt:117-160](app/src/main/java/io/github/dorumrr/privacyflip/util/ConnectionStateChecker.kt#L117-L160)).
Added the manifest permissions
([AndroidManifest.xml:28-32](app/src/main/AndroidManifest.xml#L28-L32)): `BLUETOOTH_CONNECT` for
Android 12+ (runtime-prompted), the older non-prompting `BLUETOOTH` below that. Wired a real
permission request into the settings screen, following the same pattern the app already uses for
notifications
([MainFragment.kt:945-962](app/src/main/java/io/github/dorumrr/privacyflip/ui/fragment/MainFragment.kt#L945-L962)).

**Proven live, both halves.** Turning on Bluetooth's "only if not connected" checkbox produces the
real Android permission dialog ("Allow Privacy Flip to find, connect to, and determine the
relative position of nearby devices?") - watched denying it correctly revert the checkbox and log
the denial cleanly, no crash, no stuck state. Then watched granting it for real: connected real
earbuds (independently confirmed via `dumpsys bluetooth_manager`: A2DP `mConnectionState:
CONNECTED (Active)`), played music through them, locked the phone, and read the exact line
`"🔵 Bluetooth connection check: CONNECTED (A2DP (audio))"` followed by `"⏸️ Bluetooth is in use -
skipping disable"` - Bluetooth correctly left off the disable list, and confirmed still connected
and on afterward. No dumpsys parsing anywhere in this path any more. [Verified at runtime - real
device, real accessory, watched before and after]

Skill: none further.

---

Non-goals (explicitly out of this plan):
- #36 time-based on/off scheduling - a real new feature, its own `/phi:feat` if greenlit.
- #37 revoke camera/mic permission per app instead of the global sensor-privacy toggle - a
  redesign of how blocking works, its own `/phi:feat` if greenlit.
- Battery Saver's missing "only if not already enabled" checkbox (#21) - the protection already
  exists through a different, always-on mechanism (`PrivacyActionWorker.kt:300-306`); this is a
  labelling gap, not a functional one. Optional, not blocking, not scheduled here.

Also open, not steps in this plan (fail "provable when finished," or need something only a third
party has - not silently dropped, just not fake-scheduled):
- #20 location cuts off active navigation - root cause still unconfirmed; may be an Android
  platform limit on background location for non-system apps, not something in-app code can
  necessarily fix. No fix hypothesis exists yet to turn into a step.
- #21's specific "over-firing sometimes" report - unconfirmed; the one log offered showed the app
  crashing, not the claimed behaviour. Needs fresh logs from that reporter.
- #22 NFC on Samsung - the code fix already ships (opt-in Auto-Retry, off by default;
  `PreferenceManager.kt:53-55`). The only remaining action is asking the reporters to enable it -
  a reply, not a code step.
- #33 - a question about a log line, not a bug. Answer: when it fires, Android's own lock beat
  the app to it, so that time the sensors were not actually blocked. A reply, not a code step.
- #35 - same root cause as A1 above. A1's redesign is what they need; once the real-lock test
  above confirms it, the follow-up is a reply pointing them at Side Button Support - not a
  separate code step.
