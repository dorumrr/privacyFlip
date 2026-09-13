# PrivacyFlip - open work

Tier: 3

   [ ]  not started        [~]  in progress        [x]  done

   B. FOUND BY /phi:tidy'S WHOLE-REPO SWEEP (13 Sep)
   [x] B1   LogManager writes a log file nothing ever reads                    done 13 Sep - file-write path, LogFileRotator and Constants.Logging all removed
   [x] B2   Decide the permission-request flow's fate: real checks, or gone    done 13 Sep - removed; PermissionChecker.kt deleted whole
   [x] B3   Remove the 31 confirmed-dead methods, re-checked fresh             done 13 Sep - plus 2 cascades the plan named and 1 it did not (isSupported), see below
   [x] B4   Merge 3 identical executeWithFallbacks() into one                  done 13 Sep - now a PrivilegeExecutor default method, 3 new tests, watched red
   [x] B5   Merge 4 identical WorkManager-enqueue blocks into one              done 13 Sep - PrivacyActionWork owns the payload contract, 4 new tests, watched red
   [x] B6   Merge 3 identical "log to 2 sinks" trios into one                  done 13 Sep - DualLogger; 77 call sites left untouched
   [x] B7   Consolidate all 7 near-duplicate pairs                            done 14 Sep - all 7. Pair 2 verified on a real phone incl. Shizuku's duplicate callback
   [x] B8   Tie PrivacyManager's toggle map to the feature enum                done 13 Sep - built from an exhaustive when over the enum
   [x] B9   Give BasePrivacyToggle's default parser real coverage, or drop it  done 13 Sep - made abstract, the untested duplicate body deleted
   [x] B10  Cancel BaseTileService's coroutine scope on teardown               done 13 Sep - in onDestroy, 2 new tests, watched red
   [x] B11  Fold MainViewModel's 9 dead-end wrappers into their own switch     done 13 Sep
   [x] B12  Remove MainFragment's no-op privilege-error-alert pair             done 13 Sep - 2 functions, the include and the layout file
   [x] B13  Widen Samsung's NFC-override retry to every device                done 13 Sep - gate, UI, wording, README and both store listings; preference key kept
   [x] B14  BaseTileService's single-subclass shape                            done 13 Sep - no action needed, as decided

   FOUND WHILE IMPLEMENTING, NOT IN THE AUDITED PLAN
   [x] B15  isSupported()/FeatureSupport cascade                               done 13 Sep - B3's checkFeatureSupport() was their only caller; removing it alone
                                                                               would have left 5 new orphans, so the chain went with it. The real sensor
                                                                               gating is DeviceDetector.supportsSensorPrivacyToggle(), untouched and tested.

## B1  LogManager writes a log file nothing ever reads

util/LogManager.kt runs real disk I/O (a queue, a 1-second wake loop, file rotation) on every
privilege/ log call, writing app_logs.txt. Nothing in the repo ever reads that file - the log
screen you actually use reads a completely separate file, DebugLogHelper's.

Audit 13 Sep settled the branch: REMOVE the file-write path. The "give it a consumer" branch is
a trap - LogManager writes with no debugLogsEnabled gate, while the log screen's Clear and Share
act only on DebugLogHelper's file, so a user would tap Clear, be told "Logs cleared", and still
see LogManager lines. Anything the privilege layer should surface to the user later goes through
DebugLogHelper's own gated API (B7 pair 4 decides that), never a second file. LogFileRotator.kt
and Constants.Logging exist only for this path and go with it.

Command: /phi:fix LogManager.kt's app_logs.txt has zero readers anywhere in the repo (confirmed
by /phi:tidy, 13 Sep) but costs real disk I/O on every privilege-layer log call - remove the
file-write path (queue, 1s loop, rotation, file, LogFileRotator, Constants.Logging), keep the
android.util.Log output every call already makes

depends on: none    touches: util/LogManager.kt, util/LogFileRotator.kt, util/Constants.kt

## B2  Decide the permission-request flow's fate

permission/PermissionChecker.kt's getUngrantedPermissions() is hardcoded to always return
nothing, which makes MainActivity's generic permission-request dialog structurally unreachable.

DECIDED 13 Sep - Doru asked which way, investigated before answering: the app declares 2 real
Android runtime permissions, POST_NOTIFICATIONS and BLUETOOTH_CONNECT. Both already have their
OWN separate, working, context-specific request flows in MainFragment.kt
(notificationPermissionLauncher around line 391-395, bluetoothPermissionLauncher around line
999-1013, each fires when the relevant feature is turned on) - confirmed by reading both call
sites directly. The generic PermissionChecker/MainActivity chain covers nothing those two
don't already cover; every other manifest permission is either normal (no runtime prompt) or a
special-access permission granted via a Settings intent, not this mechanism. Answer: remove the
generic flow, it is genuinely superseded, not masking a live gap.

Command: /phi:remove permission/PermissionChecker.kt's generic permission-request chain
(getAllPermissionStatus, getUngrantedPermissions, getRequiredUngrantedPermissions,
isPermissionGranted, areAllRequiredPermissionsGranted, getPermissionsToRequest, all hardcoded or
unreachable) plus MainActivity.kt's permissionLauncher/pendingPermissionRequest wiring and
MainViewModel.kt's requestPermissions() - superseded by MainFragment's own per-feature launchers

depends on: none    touches: permission/PermissionChecker.kt, MainActivity.kt,
  ui/viewmodel/MainViewModel.kt    [Verified in code - read MainFragment's notification and
  Bluetooth permission launchers directly, confirmed both real requests already happen there]

## B3  Remove the 31 confirmed-dead methods

Zero callers anywhere in the repo, independently verified twice (once by me, once by an
adversarial reviewer who tried and failed to prove one wrong). Full list is in the /phi:tidy
report from this session. Must run after B2, and /phi:remove re-checks callers fresh anyway -
note B2 already removes several of these (the PermissionChecker methods), so B3's real
remaining scope is the other 27.

The full 31, so this step does not depend on a chat transcript (4 marked * are PermissionChecker's
and go with B2; 2 marked + live in code B1 deletes outright):
  privilege/CommandResult.kt: getOutputString(), hasOutput()
  privilege/PrivilegeMethod.kt: isRootLevel(), isAdbLevel(), isDeviceOwnerLevel(), isAvailable()
  privilege/PrivilegeManager.kt: getUid() - cascades to PrivilegeExecutor.getUid() and its 3
    implementations, which have no other caller
  privilege/SuiDetector.kt: isSuiAvailable()
  privacy/PrivacyManager.kt: getAvailableToggles(), getToggle(), checkFeatureSupport()
  util/LogFileRotator.kt: ROTATION_KEEP_SIZE +
  util/LogManager.kt: cleanup() +
  util/SingletonHolder.kt: SingletonHolderNoArg
  util/BatteryOptimizationManager.kt: logBatteryOptimizationStatus()
  util/ForegroundAppDetector.kt: isAppInForeground()
  util/PreferenceManager.kt: updateBatch(), isAppExempt()
  util/DebugLogHelper.kt: d()
  root/RootManager.kt: getDeviceInfo() + data class DeviceInfo, executeCommands(),
    redetectPrivilegeMethod() - cascades to privilege/PrivilegeManager.kt:145's
    redetectPrivilegeMethod(), whose only caller is this dead wrapper (audit 13 Sep)
  accessibility/PrivacyAccessibilityService.kt: isRunning()
  permission/PermissionChecker.kt: getAllPermissionStatus()*, isPermissionGranted()*,
    areAllRequiredPermissionsGranted()*, getPermissionsToRequest()*
  ui/viewmodel/MainViewModel.kt: updatePrivacyConfig(), toggleLockFeature(),
    toggleUnlockFeature() - cascades to _privacyConfig/privacyConfig and data/PrivacyFeature.kt's
    PrivacyConfig class, which nothing else uses
  data/PrivacyFeature.kt: getConnectivityFeatures()

Command: /phi:remove the dead methods listed above under B3 - re-verify callers fresh against
the tree as it stands, not the 13 Sep snapshot

depends on: B1, B2    touches: 20 files across privilege/, privacy/, util/, root/, accessibility/,
  ui/viewmodel/, data/

## B4  Merge 3 identical executeWithFallbacks() into one

privilege/RootExecutor.kt, DhizukuExecutor.kt, ShizukuExecutor.kt each implement this
byte-for-byte identically - confirmed twice, held under adversarial review both times.

Command: /phi:fix privilege/RootExecutor.kt:117-133, DhizukuExecutor.kt:231-247,
ShizukuExecutor.kt:257-273 implement executeWithFallbacks() byte-for-byte identically - merge
into one shared implementation (PrivilegeExecutor interface default method) all 3 inherit

depends on: none    touches: privilege/RootExecutor.kt, DhizukuExecutor.kt, ShizukuExecutor.kt,
  PrivilegeExecutor.kt

## B5  Merge 4 identical WorkManager-enqueue blocks into one

receiver/ScreenStateReceiver.kt, service/PrivacyMonitorService.kt, and 2 sites in
accessibility/PrivacyAccessibilityService.kt build the same OneTimeWorkRequestBuilder+workDataOf
shape. Caution: no existing test checks the enqueued Data payload contents at any of these 4
sites, and PrivacyAccessibilityService's 2nd site isn't exercised by any test at all - write a
test asserting the payload first, or a merge could silently swap which value maps to which key
with nothing turning red.

Command: /phi:fix the WorkManager-enqueue boilerplate in receiver/ScreenStateReceiver.kt:149-167,
service/PrivacyMonitorService.kt:244-262, accessibility/PrivacyAccessibilityService.kt:209-226
and :265-280 is identical except for argument values - write a test asserting the enqueued Data
payload at all 4 sites first, then merge into one shared helper

depends on: none    touches: receiver/ScreenStateReceiver.kt, service/PrivacyMonitorService.kt,
  accessibility/PrivacyAccessibilityService.kt

## B6  Merge 3 identical "log to 2 sinks" trios into one

worker/PrivacyActionWorker.kt, receiver/ScreenStateReceiver.kt, privacy/PrivacyManager.kt each
have their own logDebug/logWarning/logError trio doing the same Log.X-then-DebugLogHelper.X
pattern. util/PendingLockWork.kt looks similar but was checked and correctly excluded - it only
has 2 of the 3 methods, with a different signature.

Command: /phi:fix worker/PrivacyActionWorker.kt:130-143, receiver/ScreenStateReceiver.kt:18-31,
privacy/PrivacyManager.kt:29-42 each hand-write the same logDebug/logWarning/logError-to-2-sinks
trio - merge into one shared implementation all 3 can call

depends on: none    touches: worker/PrivacyActionWorker.kt, receiver/ScreenStateReceiver.kt,
  privacy/PrivacyManager.kt

## B7  Consolidate all 7 near-duplicate pairs

DECIDED 13 Sep - Doru: all 7. Pairs 4 (LogManager/DebugLogHelper) and 7 (PendingLockWork vs the
log-trio B6 merges) may already be resolved or reshaped by B1 and B6 landing first - check each
against the current code before starting, don't assume the 13 Sep description still matches.

1. CameraToggle <-> MicrophoneToggle - same sensor-privacy command, differ only by sensor ID
2. DhizukuExecutor <-> ShizukuExecutor - near-identical permission-request flow, already
   slightly out of sync. Audit 13 Sep: this one decides whether the app gets privileged access,
   and neither binder can be driven from a JVM test - so the ONLY acceptable shape is a shared
   flow class (the caching, the 30s timeout, the continuation null-guard, the post-response
   double-check) tested red-then-green against a fake backend, with each executor keeping its
   own thin SDK hooks byte-for-byte. A refactor that cannot be tested that way stays filed, not
   done. The 30s timeout must be an injectable parameter: this repo has no
   kotlinx-coroutines-test, so a hard-coded 30s would cost 30 real seconds per test run
3. privilege.CommandResult <-> root.CommandResult - same 4 fields, hand-copied in 3 places
4. LogManager <-> DebugLogHelper - re-check after B1, may be moot if B1 removes LogManager
5. PrivacyFlipTileService <-> PrivacyFlipWidgetToggleReceiver - same toggle sequence
6. DebugNotificationHelper <-> PrivacyMonitorService - near-identical notification-channel setup.
   Audit 13 Sep: the two channels differ on purpose (IMPORTANCE_LOW vs DEFAULT, vibration and
   lights flags), so the shared helper takes those as parameters - a merge that flattens them
   changes what the user sees and is wrong
7. PendingLockWork's log pair <-> B6's merged trio - re-check after B6, may already fit in

DONE 13 Sep, 6 of 7:
1. CameraToggle/MicrophoneToggle -> new SensorPrivacyToggle base; each subclass is now its
   feature name and id only.
3. The two CommandResult classes -> RootManager returns privilege.CommandResult directly; its
   own copy and the hand-written field copying at 3 sites are gone, and with them the exitCode
   default that disagreed (-1 vs 0/1).
4. LogManager/DebugLogHelper -> resolved by B1: LogManager no longer writes a file at all, so
   there is no second file-backed logging system left to consolidate.
5. Tile/widget toggle -> PrivacyFlipWidget.toggleGlobalPrivacy(), called by both.
6. Notification channels -> NotificationChannels.create(); the two channels' real differences
   (LOW vs DEFAULT, vibration/lights) are parameters, and null means "keep Android's default",
   which is what the service channel relied on.
7. PendingLockWork's log pair -> now uses DualLogger per call, like ScreenStateReceiver.

FILED, NOT DONE - NEEDS YOUR YES:
2. DhizukuExecutor/ShizukuExecutor permission flow. Two reasons, both from rules this run was
   given: it is squarely on the always-ask list (it decides whether this app gets privileged
   access at all), and this plan's own condition for doing it was a shared flow proven
   red-then-green against a fake backend. Reading them side by side, they are less alike than
   "near line-for-line" suggested: Shizuku registers a permanent listener and resumes a stored
   continuation, Dhizuku passes an inline callback per request, and Shizuku has pre-checks
   (isPreV11, shouldShowRequestPermissionRationale) Dhizuku has none of. Unifying them means
   designing an abstraction over how each SDK delivers its result, on the one path that, if
   broken, stops every feature in the app - and neither binder can be driven from a test here.
   Worth doing, but it is your call, not one to make unattended.

Command: /phi:fix (pair 2 only, once you say go) DhizukuExecutor.kt:104-184 and
ShizukuExecutor.kt's requestPermission() share a caching/timeout/continuation flow around
different SDKs - extract it behind an injectable timeout and per-SDK hooks, proven red-then-green
against a fake backend before either real executor is switched over

depends on: B4, B5, B6    touches: varies per pair

## B8  Tie PrivacyManager's toggle map to the feature enum

privacy/PrivacyManager.kt:50-60 hand-builds a map from the 9-value PrivacyFeature enum with no
compile-time tie for that specific map (a 10th feature would break the build elsewhere, in
PreferenceManager.kt's exhaustive when-blocks, but not here).

Command: /phi:fix privacy/PrivacyManager.kt:50-60's toggle map has no compile-time tie to the
PrivacyFeature enum - give it one (e.g. build it from a when-expression over the enum instead of
hand-written assignments) so a future feature can't be silently missing from it

depends on: none    touches: privacy/PrivacyManager.kt

## B9  Give BasePrivacyToggle's default parser real coverage, or drop it

privacy/BasePrivacyToggle.kt:102-115's default parseStatusOutput() is invoked in production code
but every one of the 9 real subclasses overrides it, so it has zero real coverage today. Audit
13 Sep: no subclass calls super.parseStatusOutput() either - the 5 that want a generic parse call
util/StatusParsingUtils.parseStandardOutput() instead, and this default body is a weaker copy of
that same function (fewer patterns). So "add a test for it" would be testing a duplicate; the
right option is to make it abstract and delete the copy.

Command: /phi:fix privacy/BasePrivacyToggle.kt:102-115's default parseStatusOutput() body never
executes in production (all 9 subclasses override it, none calls super) and duplicates
StatusParsingUtils.parseStandardOutput() - make it abstract and delete the body, so a future
subclass that forgets to override it fails to compile instead of silently getting an untested
parser

depends on: none    touches: privacy/BasePrivacyToggle.kt

## B10  Cancel BaseTileService's coroutine scope on teardown

tile/BaseTileService.kt:22 creates a CoroutineScope per tile instance; neither it nor its one
subclass cancels it in any teardown method. This is the first test in the tile/ package -
service/ and accessibility/ both already have the "call the lifecycle method directly, assert
the consequence" pattern this would reuse, tile/ does not yet.

Audit 13 Sep corrected the fix: NOT onStopListening()/onTileRemoved(). A TileService instance
stays alive across many listen/stop cycles (every time the Quick Settings panel opens and
closes), and a cancelled CoroutineScope stays cancelled - every later serviceScope.launch{}
would silently never run, so the tile would stop updating after the first panel close. The
instance is only truly finished in onDestroy(), so that is where the scope gets cancelled.

Command: /phi:fix tile/BaseTileService.kt:22's serviceScope is never cancelled - cancel it in
onDestroy() (not onStopListening, which fires every time the panel closes while the instance
lives on), with a test proving a coroutine launched before onDestroy() does not resume after it

depends on: none    touches: tile/BaseTileService.kt, tile/PrivacyFlipTileService.kt

## B11  Fold MainViewModel's 9 dead-end wrappers into their own switch

DECIDED 13 Sep - Doru asked me to judge by actual use, investigated: MainFragment's real UI
code (its checkbox listeners, e.g. line 218-219, 1087-1092) calls only the generic
`viewModel.updateFeatureSetting(feature, ...)`, passing the feature dynamically - one code path
for all 9 features. That generic function's own `when` block is the only caller of each of the
9 named wrappers (updateWifiSettings...updateBatterySaverSettings) - nothing else, anywhere,
calls any of them directly. They are pure indirection between the switch and the shared
updateFeatureSettings() helper, going nowhere else. Answer: fold each wrapper's 2-line body
directly into its own `when` branch, remove the 9 functions.

Command: /phi:fix ui/viewmodel/MainViewModel.kt:903-946's 9 update<Feature>Settings functions
are called only from updateFeatureSetting()'s own when block (confirmed 13 Sep, no other
caller anywhere) - fold each one's body into its when branch directly, remove the 9 functions

depends on: none    touches: ui/viewmodel/MainViewModel.kt:889-960

## B12  Remove MainFragment's no-op privilege-error-alert pair

DECIDED 13 Sep - Doru asked me to check code and behaviour before deciding, investigated:
setupPrivilegeErrorAlert() (MainFragment.kt:198) does nothing at all.
updatePrivilegeErrorAlert() (line 481) only force-hides a view, and its own comment says why -
"Alert removed - System Requirements card now shows all privilege status information." Confirmed
that card (setupSystemRequirementsCard()/updateSystemRequirementsCard()) is called right
alongside this pair and is the real, live replacement. The view itself
(binding.privilegeErrorAlert, from res/layout/card_privilege_error_alert.xml, included in
fragment_main.xml) is permanently View.GONE with no click listeners. Answer: safe to remove -
the 2 no-op functions, their call sites, the XML include, and the now-unused layout file.

Command: /phi:remove MainFragment.kt's setupPrivilegeErrorAlert()/updatePrivilegeErrorAlert()
pair (confirmed 13 Sep to be pure no-ops, superseded by the System Requirements card) plus the
privilegeErrorAlert include in fragment_main.xml and res/layout/card_privilege_error_alert.xml

depends on: none    touches: ui/fragment/MainFragment.kt, res/layout/fragment_main.xml,
  res/layout/card_privilege_error_alert.xml

## B13  Widen Samsung's NFC-override retry to every device

Doru's own context (13 Sep): the Samsung-specific override-retry in NFCToggle.kt was built for
real reported Samsung NFC issues, but he's since heard it may not work properly on other devices
either. Investigated: the actual retry mechanism (disable, wait 500ms, check if NFC silently
came back on, retry up to 3 times) uses no Samsung-specific API at all - the ONLY Samsung-specific
part is the gate, DeviceDetector.isSamsungWithPaymentOverride(), which is a Build.MODEL string
match (SM-S/N/F/Z/G prefixes) that skips this check entirely on every non-Samsung device. Other
manufacturers' own payment/wallet frameworks could plausibly override an NFC disable the same
way Samsung's does - the mechanism itself doesn't care why NFC came back on, only that it did.

DECIDED 13 Sep - Doru: yes, all devices. This is a real behaviour change (every device now gets
the 500ms check-and-retry after disabling NFC, not just Samsung ones), not just a cleanup.

Audit 13 Sep widened the scope - the gate is not only in NFCToggle. The same Samsung check hides
the auto-retry setting itself: MainFragment.kt:230-231 shows samsungNfcAutoRetryContainer only
on Samsung models, and res/layout/card_screen_lock_config.xml:112-156 labels it "Samsung
Auto-Retry" with a description naming Samsung Wallet/Pay. NFCToggle.kt's own user-facing result
messages say "Samsung payment override detected. Enable 'Samsung Auto-Retry'...". On a
non-Samsung device after this change, all of that would be untrue or invisible. So: show the
setting on every device, reword the label, description and result messages to be
manufacturer-neutral (a payment/wallet app re-enabling NFC), keep the stored preference key
"samsung_nfc_auto_retry" exactly as-is so nobody who already turned it on loses the setting,
and remove DeviceDetector.isSamsungDevice()/isSamsungWithPaymentOverride(), which then have no
caller left. The docs say "Samsung" too and go stale the same moment: README.md:47,
fastlane/metadata/android/en-US/full_description.txt:14, and the Russian line at
fastlane/metadata/android/ru/full_description.txt:12 - all three change in the same step (the
Russian one gets the plainest neutral wording and is flagged for Doru to confirm). Two more
audit notes: the container's XML default is visibility="gone" (card_screen_lock_config.xml:125),
so dropping only the Kotlin gate would leave the control hidden for everyone, including the
Samsung users who already turned it on - the visibility must be set to VISIBLE outright. And
the "override persists" result message must not assert a wallet override as fact: on a slow NFC
controller the state could simply lag, so say what was observed ("NFC still reports enabled
after N retries") rather than why.

Command: /phi:fix NFCToggle.kt:64's override-retry only runs on Samsung-model-matched devices
via DeviceDetector.isSamsungWithPaymentOverride(), and MainFragment.kt:230 hides the auto-retry
setting behind the same check, but the retry mechanism itself is manufacturer-agnostic - drop
the gate in both places, show the setting everywhere, make its label/description/result
messages manufacturer-neutral, keep the stored preference key unchanged, remove the two
now-unused DeviceDetector Samsung functions

depends on: none    touches: privacy/NFCToggle.kt, util/DeviceDetector.kt,
  ui/fragment/MainFragment.kt:230-236, res/layout/card_screen_lock_config.xml:112-156

## B14  BaseTileService's single-subclass shape

DECIDED 13 Sep - Doru: ok, no action needed. tile/BaseTileService.kt:15 stays abstract with its
one real subclass (PrivacyFlipTileService) - used, just no second subclass yet to justify the
split further.

depends on: none    touches: none - no change
