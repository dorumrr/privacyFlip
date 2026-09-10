# PrivacyFlip - open work

Tier: 3

   [ ]  not started        [~]  in progress        [x]  done

   A. FOUND DURING C2's ADVERSARIAL REVIEW (10 Sep), NOT YET BUILT
   [ ] A1  ACTION_SCREEN_ON's "not locked" branch never re-enables what it disabled
   [ ] A2  Unlock-side re-enable can race ahead of and finish before the sensor disable
   [ ] A3  Side-button unlock detection can be permanently missed in a narrow race     only matters if the main service already died
   [ ] A4  PendingLockWork.cancel() logs success even when nothing was cancelled
   [ ] A5  The hotspot check goes stale between when it's read and when it's used
   [ ] A6  Lock-delay warning text overclaims what camera protection does             wording/product call, not a code bug

## A1  ACTION_SCREEN_ON's "not locked" branch never re-enables what it disabled

If the device is woken during Android's own pre-keyguard grace period (screen off, keyguard not
yet engaged), ACTION_SCREEN_ON correctly cancels the pending lock-side job once it confirms the
keyguard never actually engaged - but unlike the other 3 unlock-detection paths, it never enqueues
the unlock-side re-enable work. Whatever the immediate sensor block already disabled (camera/mic)
can stay off indefinitely, since the device was never truly locked, so ACTION_USER_PRESENT never
fires later to catch it either.

depends on: none    touches: receiver/ScreenStateReceiver.kt (ACTION_SCREEN_ON branch)
  [Verified in code - traced through the actual branch this round]

proves it is done: a test (Robolectric, same pattern as PrivacyAccessibilityServiceTest) that
  fires ACTION_SCREEN_ON with the keyguard reporting unlocked, and confirms unlock-side work
  (Constants.Work.NAME_UNLOCK) is actually enqueued - red on today's code, green after the fix

## A2  Unlock-side re-enable can race ahead of and finish before the sensor disable

ACTION_USER_PRESENT correctly skips cancelling an in-flight sensor disable (#G1 - cancelling it
would interrupt the running coroutine mid-command) but still unconditionally enqueues the
unlock-side re-enable as a separate, concurrent WorkManager job. That job can finish before the
still-running lock-side sensor disable does, leaving camera/mic off after a real, confirmed
unlock - the opposite of what the user just did.

depends on: none    touches: receiver/ScreenStateReceiver.kt (ACTION_USER_PRESENT),
  worker/PrivacyActionWorker.kt (both the lock-side sensor block and the unlock-side enable)
  [Verified in code - traced through both worker branches this round]

Needs a decision before it can be built: how the two competing jobs should be sequenced - hold
the unlock-side enable until sensorDisableInProgress clears, or something else. Not a quick
default flip like A2's sibling (the one already fixed, PrivacyMonitorService's fail-open default).

proves it is done: whatever the chosen fix is, provable the same way - force the race in a test
  (delay the lock-side disable, fire the unlock-side enable) and confirm sensors end up enabled,
  not disabled, once both finish

## A3  Side-button unlock detection can be permanently missed in a narrow race

PrivacyAccessibilityService's unlock branch confirms real KeyguardManager state before acting
(the #C3 regression fix). But if the window-class-change event is delivered before
KeyguardManager's own internal state catches up (a dismiss animation, an OEM ordering quirk),
isStillLocked reads true, nothing fires, and wasShowingKeyguard never resets - this specific
unlock is missed permanently by this detector, with no retry or timeout. Only matters when
PrivacyMonitorService is dead, which is the one situation this detector exists to cover (it has
its own independent lock-detection path specifically so it doesn't depend on that service being
alive).

depends on: none    touches: accessibility/PrivacyAccessibilityService.kt (the unlock branch)
  [Verified in code - traced through this round; PrivacyAccessibilityServiceTest's existing 2
  tests do not cover this specific race, only the already-fixed false-positive one]

proves it is done: a Robolectric test that holds the keyguard shadow at "locked" through the
  window-change event, then flips it, and confirms the unlock still eventually gets caught (not
  silently dropped forever)

## A4  PendingLockWork.cancel() logs success even when nothing was cancelled

cancelUniqueWork() is called on every confirmed unlock, including the common case where nothing
is actually pending - it is a no-op then, but the log unconditionally prints "Cancelled pending
lock work due to a confirmed unlock" regardless. The returned Operation (WorkManager's own async
result) is never read, so a genuine cancel failure can never be logged either.

depends on: none    touches: util/PendingLockWork.kt (cancel())
  [Verified in code - traced through this round]

proves it is done: a test confirming the log/notify only claims a cancellation happened when
  something was actually pending, and that a failed Operation is surfaced rather than silently
  dropped

## A5  The hotspot check goes stale between when it's read and when it's used

hotspotActiveNow is sampled once in PrivacyActionWorker's lock branch, then used both for the
regularFeatures filter and, after a real disableFeatures() root/Shizuku round-trip, the
protection-modes loop's Airplane Mode decision. A hotspot starting or stopping in that gap gets
the stale verdict. Pre-existing from C1, not introduced or worsened by C2's restructure.

depends on: none    touches: worker/PrivacyActionWorker.kt (the lock branch's hotspot check)
  [Verified in code - traced through this round]

proves it is done: a live device test starting/stopping a real hotspot in that exact window (this
  machine's shell could not start one this session - SecurityException, missing
  MAINLINE_NETWORK_STACK - so this may need a rooted test device, not just adb shell)

## A6  Lock-delay warning text overclaims what camera protection does

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
