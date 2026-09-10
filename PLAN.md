# PrivacyFlip - open work

Tier: 3

   [ ]  not started        [~]  in progress        [x]  done

   A. FOUND DURING C2's ADVERSARIAL REVIEW (10 Sep), CONFIRMED BY /phi:debug (10 Sep)
   [x] A1  ACTION_SCREEN_ON's "not locked" branch never re-enables what it disabled   done 10 Sep - now enqueues the unlock-side re-enable
   [x] A2  Unlock-side re-enable can race ahead of and finish before the sensor disable   done 10 Sep - 3 parts, 2 found by this round's own review, see below
   [x] A3  Side-button unlock detection has a bounded, not permanent, blind spot      done 10 Sep - delayed re-check added for when no window event ever arrives
   [x] A4  PendingLockWork.cancel() logs success even when nothing was cancelled      done 10 Sep - now reads WorkManager's real Operation result
   [x] A5  The hotspot check goes stale between when it's read and when it's used     done 10 Sep - Airplane Mode gets a fresh re-check; found and fixed a notification-consistency bug in this same fix
   [x] A6  Lock-delay warning text overclaims what camera protection does             done 10 Sep - Doru chose: correct the wording, not build real detection

## A1  ACTION_SCREEN_ON's "not locked" branch never re-enables what it disabled

DONE 10 Sep. ScreenStateReceiver's ACTION_SCREEN_ON handler now calls triggerPrivacyAction(
isLocking=false,...) in its "not locked" branch, mirroring ACTION_USER_PRESENT exactly, so it
both cancels the pending lock job AND enqueues the unlock-side re-enable.

Watched red then green: ScreenStateReceiverTest's existing assertion (which proved the bug by
expecting 0 enqueued NAME_UNLOCK jobs) failed the moment the fix landed, since the code now
correctly enqueues 1 - updated the same test to assert the fix instead of the bug, watched it
pass, ran it 5 times to confirm no flakiness.

The remaining Inferred claim from the debug round stands unchanged: whether ACTION_USER_PRESENT
still fires on a device with no lock-screen security set ("None") was never independently tested,
only reasoned about. Since this fix makes ACTION_SCREEN_ON itself cover the re-enable now, that
open question matters less than it did - this path no longer depends on ACTION_USER_PRESENT to
recover from the pre-keyguard grace-period scenario.

depends on: none    touches: receiver/ScreenStateReceiver.kt (ACTION_SCREEN_ON branch)
  [Verified at runtime - ScreenStateReceiverTest, watched fail then pass, stable across 5 runs]

## A2  Unlock-side re-enable can race ahead of and finish before the sensor disable

DONE 10 Sep, 3 parts - the first was the planned fix, the other 2 were found by this round's own
adversarial review attacking A2 itself, not pre-existing or adjacent findings.

**Part 1: mutual exclusion.** PrivacyActionWorker gained `internal val sensorMutex = Mutex()`
(kotlinx.coroutines.sync), wrapped around both the lock-side sensor-disable block and the
unlock-side sensor-enable block, so the two privileged calls can never overlap with each other.

**Part 2: correct ordering, not just exclusion.** Found by this round's own review: a mutex only
proves mutual exclusion, not real-world order. Each job does variable-latency setup (privilege
checks, singleton lookups) before ever reaching the mutex, so whichever one arrives first wins it
first - not necessarily the one whose real-world event happened first. A lock immediately undone
by a fast unlock could still see the unlock's enable finish before the lock's disable even reaches
the mutex, leaving sensors disabled after the real, later unlock. Closed with `lastLockAtMillis`,
the lock-side twin of the already-existing `lastUnlockAtMillis` (#C2 Part 1) - each side checks,
still inside the mutex and right before its own privileged call, whether the opposite action has
already been recorded as happening after this cycle started, and skips if so.

**Part 3: the #G1 pattern's missing twin.** Also found by this round's own review: nothing stopped
a 3rd unlock signal (any of this app's 3 independent unlock-detection paths noticing the same real
unlock) from REPLACE-enqueuing NAME_UNLOCK while a 2nd unlock job was still mid-way through
enableFeatures() - WorkManager's REPLACE cancels a running coroutine, the exact #G1 race already
guarded on the lock/disable side (sensorDisableInProgress) but never extended to the unlock/enable
side. Closed with the new `sensorEnableInProgress` flag, checked at all 3 unlock-enqueue call
sites the same way sensorDisableInProgress already guards the lock-enqueue sites.

Watched red then green twice: PrivacyActionWorkerSensorMutexTest's first case (mutual exclusion)
and second case (ordering) were each watched fail with the relevant check deliberately bypassed,
then pass once restored, each run 5 times to confirm stability. sensorEnableInProgress's guard is
Verified in code only, matching the exact evidence tier its own precedent (sensorDisableInProgress)
has always carried in this codebase - that flag is private-set and not writable from a test
without either a test-only setter or the full doWork() pipeline, neither of which exists here.

depends on: none    touches: worker/PrivacyActionWorker.kt (sensorMutex, lastLockAtMillis,
  sensorEnableInProgress, both sensor blocks), receiver/ScreenStateReceiver.kt,
  accessibility/PrivacyAccessibilityService.kt, service/PrivacyMonitorService.kt (the 3
  sensorEnableInProgress-guarded enqueue sites)    [Verified at runtime - the mutex and the
  ordering check, both watched fail then pass; Verified in code - sensorEnableInProgress's guard]

Not verified: no live device run of any of the 3 parts; ScreenStateReceiverTest checks that
  NAME_UNLOCK work was enqueued but not that it carries correct input Data - a real, low-risk,
  filed-not-fixed test-coverage gap (the call reuses the exact same, already-tested
  triggerPrivacyAction() shape ACTION_USER_PRESENT already uses correctly)

## A3  Side-button unlock detection has a bounded, not permanent, blind spot

DONE 10 Sep. PrivacyAccessibilityService gained scheduleUnlockRecheck(): when the "keyguard still
locked" race is hit, a Handler.postDelayed re-checks real keyguard state again after 500ms,
regardless of whether any further window-state-change event ever arrives - closing the gap the
self-heal-on-next-event behaviour (still true, unchanged) doesn't cover: a user who unlocks just
to glance and re-locks without navigating anywhere generates no further window event to trigger
the existing re-check.

Found and fixed along the way: Robolectric.setupService() only drives the plain Service lifecycle
(onCreate) - it never calls onServiceConnected(), the AccessibilityService-specific system binder
callback real Android always fires before any accessibility event can arrive. Every existing test
was silently running with isServiceRunning permanently false, which the new delayed-recheck guard
depends on - would have made the whole fix untestable, not a bug in the fix itself. Fixed by
widening onServiceConnected() from the inherited protected to public (idempotent, no real
downside) and adding a connectedService() test helper that calls it, matching real lifecycle order.

Watched red then green: the new test failed first (isServiceRunning false, guard short-circuited),
fixed the test's own setup gap, watched it pass, ran it 5 times to confirm no flakiness.

depends on: none    touches: accessibility/PrivacyAccessibilityService.kt (scheduleUnlockRecheck,
  onServiceConnected widened to public)    [Verified at runtime - PrivacyAccessibilityServiceTest,
  watched fail then pass, stable across 5 runs]

Not verified: no live device run; whether 500ms is the right delay for a real dismiss-animation
  lag on real hardware, versus this session's own reasoning about what "short enough to be
  irrelevant, long enough to clear a lag" means

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

DONE 10 Sep. cancel() now reads the Operation cancelUniqueWork() returns: a listener on its
result (a ListenableFuture, resolved asynchronously by WorkManager) logs whichever real outcome
actually happens - confirmed or failed - instead of the call site optimistically logging success
up front. Runs on an inline same-thread Executor (logging is cheap and thread-safe, no need to
hop threads); the call site itself stays non-blocking, matching all 4 of its callers (broadcast
receivers, an accessibility event handler - none of them coroutines).

Watched red then green: PendingLockWorkTest failed against the old (Operation-discarding) shape,
passed once the listener was added. Strengthened mid-round after this round's own adversarial
review correctly challenged that the first draft only ever cancelled a no-op (nothing pending) -
now enqueues a real NAME_LOCK job first, so the test exercises cancelling something genuine. A
real Operation FAILURE specifically still cannot be forced in Robolectric's test WorkManager (no
API for it) - that half stays Verified in code only, not overclaimed as tested.

depends on: none    touches: util/PendingLockWork.kt (cancel())
  [Verified at runtime - PendingLockWorkTest, watched fail then pass, stable across 5 runs;
  Verified in code - the failure-logging path specifically, not independently forced in a test]

## A5  The hotspot check goes stale between when it's read and when it's used

DONE 10 Sep, 2 parts - the second found by this round's own adversarial review attacking A5
itself, not pre-existing.

**Part 1: the planned fix.** The protection-modes loop now computes
`hotspotActiveForAirplaneMode`, a fresh call to connectionChecker.isHotspotActive(), right before
the Airplane Mode decision - only when Airplane Mode is actually configured, so this costs
nothing extra when it isn't. Replaces reusing the earlier hotspotActiveNow, which by this point
had already gone stale behind a real disableFeatures() root/Shizuku round-trip.

**Part 2: a notification-consistency bug this round's own fix introduced.** Splitting the check
into an early read (still used for the regularFeatures filter and its own notification) and a
late, fresh read (for Airplane Mode) opened a new gap: the early notification used to name
Airplane Mode as "kept on despite lock, hotspot is active" - but the LATE, fresh check could now
reach a different answer and actually enable Airplane Mode anyway, contradicting what the user
was just told. Fixed: the early notification no longer names Airplane Mode at all; the real
notification for Airplane Mode's fate now fires at the point the fresh check actually decides it,
so what the user is told always matches what the code does.

A reviewer's separate claim - that Battery Saver could be processed before Airplane Mode in the
same loop, staling the fresh check before it's even used - was checked and disproven:
PrivacyFeature's own enum declaration order (AIRPLANE_MODE before BATTERY_SAVER) makes that
ordering impossible, confirmed by reading how protectionModes is actually built.

depends on: none    touches: worker/PrivacyActionWorker.kt (the lock branch's hotspot check and
  its notification)    [Verified in code - both parts; no runtime test possible without mocking
  ConnectionStateChecker/RootManager, infrastructure this codebase does not have, same limitation
  already disclosed for A5 in the 10 Sep debug round]

Not verified: the live consequence - a real hotspot starting/stopping in that exact window - this
  machine's shell still cannot start one this session (unchanged from the debug round)

## A6  Lock-delay warning text overclaims what camera protection does

DONE 10 Sep. Asked Doru directly which path to take (correct the wording, or build real
camera-in-use detection) - PLAN.md's own text had flagged this as a product choice, not something
to guess at. Answer: correct the wording.

`lock_delay_warning_message` and the matching fastlane store-listing line no longer imply camera
skips the immediate trigger while in use - both now say plainly that microphone can skip it
during a genuine call (if that setting is on) while camera has no way to detect active use and
always triggers immediately, even mid video-call. README.md was checked too (full search, not
assumed clean) and already carried no such claim - no change needed there.

depends on: none    touches: res/values/strings.xml (lock_delay_warning_message),
  fastlane/metadata/android/en-US/full_description.txt    [Verified in code - the corrected text
  read back against ConnectionStateChecker's actual behaviour, they now agree]
