# PrivacyFlip - open work

Tier: 3

   [ ]  not started        [~]  in progress        [x]  done

   A. FOUND DURING C2's ADVERSARIAL REVIEW (10 Sep), CONFIRMED BY /phi:debug (10 Sep), NOT BUILT
   [ ] A1  ACTION_SCREEN_ON's "not locked" branch never re-enables what it disabled   CONFIRMED at runtime, narrow fact - see below for what's still Inferred
   [ ] A2  Unlock-side re-enable can race ahead of and finish before the sensor disable   CONFIRMED structurally, needs your decision first
   [ ] A3  Side-button unlock detection has a bounded, not permanent, blind spot      REWORDED 10 Sep - the "permanent" framing was wrong, corrected below
   [ ] A4  PendingLockWork.cancel() logs success even when nothing was cancelled      CONFIRMED
   [ ] A5  The hotspot check goes stale between when it's read and when it's used     CONFIRMED structurally, live consequence still unprovable here
   [ ] A6  Lock-delay warning text overclaims what camera protection does             CONFIRMED - wording/product call, not a code bug

## A1  ACTION_SCREEN_ON's "not locked" branch never re-enables what it disabled

CONFIRMED 10 Sep by /phi:debug, at runtime, not just by reading. ScreenStateReceiverTest fires
ACTION_SCREEN_ON with the keyguard shadow reporting unlocked and confirms zero NAME_UNLOCK work
gets enqueued - unlike the other 3 unlock-detection paths, this branch only cancels the pending
lock job, never re-enables anything.

The narrower, confirmed fact: if the device is woken during Android's own pre-keyguard grace
period (screen off, keyguard not yet engaged), ACTION_SCREEN_ON correctly cancels the pending
lock-side job once it confirms the keyguard never actually engaged, but does nothing to re-enable
what the immediate sensor block already disabled.

Corrected by this round's own adversarial review: "so ACTION_USER_PRESENT never fires later to
catch it either" was asserted, not verified. That direction still holds by reasoning (Android's
own ACTION_USER_PRESENT fires on a genuine keyguard dismissal, and this scenario is specifically
one where the keyguard never engaged, so there is nothing to dismiss) but was not independently
tested - both adversarial reviewers who saw this conclusion separately flagged the same gap:
whether ACTION_USER_PRESENT still fires on a device with no lock-screen security set ("None")
could not be confirmed either way.

depends on: none    touches: receiver/ScreenStateReceiver.kt (ACTION_SCREEN_ON branch)
  [Verified at runtime - the narrow fact (this branch never re-enables); Inferred - the "stays
  off indefinitely" consequence, which depends on no other detector covering the same case]

proves it is done: ScreenStateReceiverTest (already exists) goes from red to green once
  ACTION_SCREEN_ON's "not locked" branch also enqueues the unlock-side re-enable

## A2  Unlock-side re-enable can race ahead of and finish before the sensor disable

CONFIRMED structurally 10 Sep by /phi:debug. ACTION_USER_PRESENT correctly skips cancelling an
in-flight sensor disable (#G1 - cancelling it would interrupt the running coroutine mid-command)
but still unconditionally enqueues the unlock-side re-enable as a separate, concurrent WorkManager
job - and PrivacyActionWorker's own unlock-side enableFeatures() branch never checks
sensorDisableInProgress either, confirmed independently by this round's adversarial review reading
the worker in full. That job can finish before the still-running lock-side sensor disable does,
leaving camera/mic off after a real, confirmed unlock - the opposite of what the user just did.

Confirmed this app can actually run the two concurrently, not just that nothing stops it in
theory: grepped for any custom WorkManager Configuration.Provider or Executor anywhere in this
app - none exists, so it runs WorkManager's default configuration, which uses a multi-threaded
pool (never single-threaded, even on a 2-core device) and only serialises work sharing the SAME
unique name. The exact interleaving outcome (which job actually finishes first in a real run)
stays Inferred - forcing it would need controllable timing inside PrivacyManager.disableFeatures(),
which talks to a real root/Shizuku shell and isn't swappable here without a bigger refactor.

depends on: none    touches: receiver/ScreenStateReceiver.kt (ACTION_USER_PRESENT),
  worker/PrivacyActionWorker.kt (both the lock-side sensor block and the unlock-side enable)
  [Verified in code - the missing guard on both sides, and the shared default WorkManager
  configuration that makes them genuinely concurrent; Inferred - the actual race outcome]

Needs a decision before it can be built: how the two competing jobs should be sequenced - hold
the unlock-side enable until sensorDisableInProgress clears, or something else. Not a quick
default flip like A2's sibling (the one already fixed, PrivacyMonitorService's fail-open default).

proves it is done: whatever the chosen fix is, provable the same way - force the race in a test
  (delay the lock-side disable, fire the unlock-side enable) and confirm sensors end up enabled,
  not disabled, once both finish

## A3  Side-button unlock detection has a bounded, not permanent, blind spot

REWORDED 10 Sep by /phi:debug - the original framing ("permanently missed... no retry or
timeout") does not survive a runtime test, and saying so plainly matters more than quietly fixing
the wording: PrivacyAccessibilityServiceTest proves the same race SELF-HEALS on the very next
window-state-change event, because the keyguard read re-runs every time a non-lock-screen window
event arrives while an internal flag (wasShowingKeyguard) stays true - not just once, as first
assumed.

The real, narrower defect underneath: self-healing depends on a FURTHER window event actually
happening before the device locks again, and this round's own adversarial review correctly
pushed back on assuming that is quick or reliable. A user who unlocks just to glance at
something and re-locks without opening or switching anything may generate no further
window-state-change event before locking again - in that exact pattern, the miss can persist
through the whole cycle, only resolving whenever the NEXT separate lock/unlock cycle happens to
produce one. Still only user-visible when PrivacyMonitorService is already dead (the one
situation this detector exists to cover, since it has its own independent lock-detection path
specifically so it doesn't depend on that service being alive).

depends on: none    touches: accessibility/PrivacyAccessibilityService.kt (the unlock branch)
  [Verified at runtime - both that the race can happen and that it self-heals on a later window
  event; Inferred - how often a "glance and re-lock" pattern with no further window event occurs
  in real use]

proves it is done: a retry/timeout mechanism added (e.g. re-check keyguard state again after a
  short delay even with no new window event), tested by simulating "no further window event
  arrives" in Robolectric and confirming eventual detection anyway

## A4  PendingLockWork.cancel() logs success even when nothing was cancelled

CONFIRMED 10 Sep by /phi:debug (in code - the shape leaves no real ambiguity a runtime test would
resolve). Led with the wrong half of this in the first write-up, corrected by this round's own
adversarial review: the consequence that actually matters is that cancelUniqueWork()'s returned
Operation (WorkManager's own async result) is never read, so a GENUINE cancel failure can never
be detected or logged - a pending lock/disable action could still silently fire later even though
the log already said it was cancelled. The weaker half, still true but less important: the same
unconditional log also prints "Cancelled pending lock work due to a confirmed unlock" on every
routine unlock where nothing was pending in the first place (the common case, a true no-op), which
just makes the debug log noisy and not fully trustworthy for diagnosing a real race.

depends on: none    touches: util/PendingLockWork.kt (cancel())
  [Verified in code - traced through this round]

proves it is done: a test confirming the log/notify only claims a cancellation happened when
  something was actually pending, and that a failed Operation is surfaced rather than silently
  dropped

## A5  The hotspot check goes stale between when it's read and when it's used

CONFIRMED structurally 10 Sep by /phi:debug. hotspotActiveNow is computed exactly ONCE in
PrivacyActionWorker's lock branch (one call to connectionChecker.isHotspotActive(), stored in a
val) - not two separate live reads, one cached value referenced 3 times afterward: the skip-log,
the regularFeatures filter, and, after a real disableFeatures() root/Shizuku round-trip has
already run in between, the protection-modes loop's Airplane Mode decision. A hotspot starting or
stopping in that gap gets the stale verdict at the last of those 3 points. Pre-existing from C1,
not introduced or worsened by C2's restructure.

depends on: none    touches: worker/PrivacyActionWorker.kt (the lock branch's hotspot check)
  [Verified in code - the single-computation, multi-use pattern, re-confirmed this round]

proves it is done: a live device test starting/stopping a real hotspot in that exact window (this
  machine's shell could not start one this session - SecurityException, missing
  MAINLINE_NETWORK_STACK - so this may need a rooted test device, not just adb shell)

## A6  Lock-delay warning text overclaims what camera protection does

CONFIRMED 10 Sep by /phi:debug, independently re-derived by 2 of the round's 3 adversarial
reviewers from cold reads of the code, not just re-checked against the original claim.
`lock_delay_warning_message` (and the matching fastlane store-listing line) says camera "triggers
immediately (if it is not in use)", implying camera has the same in-use interlock microphone
does. It does not - ConnectionStateChecker.kt hardcodes CAMERA to never be "in use" (no detection
exists for it at all) - so the app will cut the camera mid video-call despite what this text
promises.

depends on: none    touches: res/values/strings.xml (lock_delay_warning_message),
  fastlane/metadata/android/en-US/full_description.txt    [Verified in code - the hardcoded
  false traced directly; the string's wording read as making a promise the code cannot keep]

Product call, not a pure code fix: either correct the wording to stop implying camera has this
protection, or build real camera-in-use detection so the wording becomes true. Which one is
Doru's call.

proves it is done: the string no longer claims something the code doesn't do - either the wording
  changed, or a real camera in-use check was added and verified
