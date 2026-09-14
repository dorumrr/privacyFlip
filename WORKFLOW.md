# PrivacyFlip Permission & Privilege Workflow

## Overview
PrivacyFlip requires privileged access (Dhizuku, Shizuku, or Root) to control system-level privacy features. This document describes the user experience for each privilege scenario.

---

## Case 1: No Privilege Available

**User Story:** As a user without Shizuku or root, I need to understand why the app cannot function and what I need to do.

### Behavior:
- App starts and displays UI
- **System Requirements card shown** (reddish background). This is the only alert surface; the app has no separate alert card:
  - **Message:** "Privacy Flip requires Dhizuku, Shizuku (for non-rooted devices) or root access (via Magisk or similar) to control privacy features."
  - **Privileged Access:** "Not Available"
  - Action button: "INSTALL SHIZUKU OR ROOT DEVICE"
  - **Battery Optimization:** Shows current status
  - Action button: "OPEN BATTERY SETTINGS" (if optimization enabled)
- **Privacy Flip status card:** "Protection Inactive" (toggle disabled)
- **All feature switches disabled** (Wi-Fi, Bluetooth, Mobile Data, etc.)

### Expected State:
- `isRootAvailable = false`
- `isRootGranted = false`
- All toggles non-interactive

---

## Case 2: Shizuku Available

**User Story:** As a Shizuku user, I need to grant permission to PrivacyFlip so it can control privacy features, and the app should recover automatically if Shizuku restarts.

### 2A: First Launch - Permission Not Granted

#### Behavior:
- App starts and immediately requests Shizuku permission via system dialog
- **If user denies:**
  - **System Requirements card shown** (reddish background):
    - **Message:** "Click 'Grant Shizuku Permission' button to try again or uninstall and reinstall the app, then grant permission at first start."
    - **Privileged Access:** "Shizuku Available"
    - Action button: "GRANT SHIZUKU PERMISSION"
    - **Battery Optimization:** Shows current status
    - Action button: "OPEN BATTERY SETTINGS" (if optimization enabled)
  - **Privacy Flip status card:** "Protection Inactive" (toggle disabled)
  - **All feature switches disabled**

#### Expected State:
- `isRootAvailable = true`
- `isRootGranted = false`
- `privilegeMethod = SHIZUKU`

### 2B: Permission Granted - Battery Optimization Not Disabled

#### Behavior:
- User clicks "GRANT SHIZUKU PERMISSION" and grants permission
- **System Requirements card shown** (reddish background):
  - **Privileged Access:** "Shizuku Granted" (no action button)
  - **Battery Optimization:** "Optimization enabled"
  - Action button: "OPEN BATTERY SETTINGS"
- **Privacy Flip status card:** "Protection Inactive" (toggle enabled)
- **All feature switches enabled**

#### Expected State:
- `isRootAvailable = true`
- `isRootGranted = true`
- `isBatteryOptimizationDisabled = false`
- `privilegeMethod = SHIZUKU`

### 2C: Permission Granted - Battery Optimization Disabled

#### Behavior:
- User disables battery optimization
- **System Requirements card hidden** (all requirements met)
- **Privacy Flip status card:** "Protection Inactive" (toggle enabled)
- **All feature switches enabled**

#### Expected State:
- `isRootAvailable = true`
- `isRootGranted = true`
- `isBatteryOptimizationDisabled = true`
- `privilegeMethod = SHIZUKU`

### 2D: Shizuku Dies and Restarts

**CRITICAL REQUIREMENT:** When Shizuku service dies and restarts, PrivacyFlip must automatically restore itself if protection was active.

#### Behavior:
- Shizuku service stops (user stops it, device restarts, crashes, etc.)
- PrivacyFlip detects Shizuku is unavailable
- When Shizuku service restarts:
  - PrivacyFlip automatically re-requests permission (should be auto-granted)
  - **If protection was active before Shizuku died:**
    - Automatically re-enable protection
    - Restore all privacy feature states
  - **If protection was inactive:**
    - Remain inactive

#### Implementation Notes:
- Monitor Shizuku binder death/rebirth
- Persist protection state to SharedPreferences
- Register Shizuku binder listener to detect service restart
- Auto-restore on Shizuku availability

---

## Case 3: Root Available (with or without Sui)

**User Story:** As a rooted user, I need to grant root permission to PrivacyFlip so it can control privacy features.

### 3A: Root Only (No Sui) - Permission Not Granted

#### Behavior:
- App starts and requests root permission via Magisk/SuperSU dialog
- **If user denies:**
  - **System Requirements card shown** (reddish background):
    - **Message:** "Privacy Flip could not confirm root access. Open Magisk, allow Privacy Flip, then tap 'Check Again'."
    - **Privileged Access:** "Root Available"
    - Action button: "CHECK AGAIN" (drops the cached non-root shell and re-runs `su`, so a grant made in Magisk since the app started is actually picked up; a working root shell is never dropped)
    - **Battery Optimization:** Shows current status
    - Action button: "OPEN BATTERY SETTINGS" (if optimization enabled)
  - **Privacy Flip status card:** "Protection Inactive" (toggle disabled)
  - **All feature switches disabled**

#### Expected State:
- `isRootAvailable = true`
- `isRootGranted = false`
- `privilegeMethod = ROOT`

### 3B: Root Only - Permission Granted

#### Behavior:
- Same as Shizuku Case 2B/2C
- No automatic restoration needed (root doesn't die like Shizuku)

#### Expected State:
- `isRootAvailable = true`
- `isRootGranted = true`
- `privilegeMethod = ROOT`

### 3C: Root + Sui - Fully Automated

**User Story:** As a Sui user (Magisk module), the app should work with minimal user interaction.

#### Behavior:
- App starts and automatically detects Sui
- Sui permission is typically auto-granted (no dialog)
- **If battery optimization disabled:**
  - **All cards hidden except Privacy Flip status**
  - App is fully functional immediately
- **If battery optimization enabled:**
  - **System Requirements card shown** (reddish background):
    - **Privileged Access:** "Sui Granted" (no action button)
    - **Battery Optimization:** "Optimization enabled"
    - Action button: "OPEN BATTERY SETTINGS"

#### Expected State:
- `isRootAvailable = true`
- `isRootGranted = true`
- `privilegeMethod = SUI`

---

## Summary Matrix

There is one card, not two. It always carries the red background, so "shown" is the only alarm the app has.

| Scenario | System Req Card | Privileged Access Status | Battery Opt Status | Toggles Enabled |
|----------|-----------------|-------------------------|-------------------|-----------------|
| No privilege | ✅ Shown | "Not Available" + Install button | Shown + button if needed | ❌ Disabled |
| Shizuku denied | ✅ Shown | "Shizuku Available" + Grant button | Shown + button if needed | ❌ Disabled |
| Shizuku granted, battery not disabled | ✅ Shown | "Shizuku Granted" (no button) | "Enabled" + button | ✅ Enabled |
| Shizuku granted, battery disabled | ❌ Hidden | N/A | N/A | ✅ Enabled |
| Root denied | ✅ Shown | "Root Available" + "CHECK AGAIN" button | Shown + button if needed | ❌ Disabled |
| Root granted, battery not disabled | ✅ Shown | "Root Granted" (no button) | "Enabled" + button | ✅ Enabled |
| Root granted, battery disabled | ❌ Hidden | N/A | N/A | ✅ Enabled |
| Sui granted, battery not disabled | ✅ Shown | "Sui Granted" (no button) | "Enabled" + button | ✅ Enabled |
| Sui granted, battery disabled | ❌ Hidden | N/A | N/A | ✅ Enabled |

---

## Critical Implementation Requirements

### 1. Shizuku Auto-Restoration
- **Must** detect when Shizuku service dies and restarts
- **Must** automatically restore protection state if it was active
- **Must** persist protection state across Shizuku restarts

### 2. Permission Request Timing
- Shizuku: Request on first launch, show dialog immediately
- Root: Request on first launch, show Magisk/SuperSU dialog immediately
- Sui: Auto-granted, no user interaction needed

### 3. UI State Consistency
- System Requirements card visibility depends on BOTH privilege status AND battery optimization
- The System Requirements card is the only alert surface. It carries the explanatory message itself, and appears whenever privilege is denied or unavailable
- All toggles disabled when `isRootGranted = false`

### 4. Battery Optimization
- Always check and display status
- Only hide System Requirements card when BOTH privilege granted AND battery optimization disabled
- Provide easy access to battery settings

---

## Edge Cases

### Shizuku App Uninstalled
- Treat as "No privilege available"
- System Requirements card shows "Not Available" with the install message

### Root Access Revoked
- Treat as "No privilege available"
- System Requirements card shows "Not Available" with the install message

### User Denies with "Don't Ask Again"
- The System Requirements card carries the instructions for whichever method is in use
- Shizuku/Dhizuku/Sui: the Grant button still attempts the request and fails gracefully
- Root: the user allows Privacy Flip in Magisk, then taps "CHECK AGAIN", which drops the cached non-root shell so `su` runs again and the new grant is seen

### Device Reboot
- Shizuku: May need to restart Shizuku service, then app auto-restores
- Root: Should work immediately after reboot
- Sui: Should work immediately after reboot

