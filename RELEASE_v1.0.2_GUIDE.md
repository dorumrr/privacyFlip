# Release Guide: Version 1.0.2

## Quick Reference for Releasing v1.0.2

### 1. Update Version Numbers

**File:** `app/build.gradle.kts` (Lines 14-15)

Change:
```kotlin
versionCode = 2
versionName = "1.0.1"
```

To:
```kotlin
versionCode = 3
versionName = "1.0.2"
```

---

### 2. Verify Changelog Files ✅

Already created:
- ✅ `fastlane/metadata/android/en-US/changelogs/2.txt` (v1.0.1)
- ✅ `fastlane/metadata/android/en-US/changelogs/3.txt` (v1.0.2)

---

### 3. Build Release APK

```bash
# Clean previous builds
./gradlew clean

# Build release APK
./gradlew assembleRelease

# Output will be at:
# app/build/outputs/apk/release/PrivacyFlip-v1.0.2-release.apk
```

---

### 4. Test on Device

```bash
# Install on connected device
adb install app/build/outputs/apk/release/PrivacyFlip-v1.0.2-release.apk

# Or uninstall first for clean install
adb uninstall io.github.dorumrr.privacyflip
adb install app/build/outputs/apk/release/PrivacyFlip-v1.0.2-release.apk
```

**Test Checklist:**
- [x] App icon displays correctly (no cropping, no white margins)
- [x] App launches successfully
- [x] Root permissions work
- [x] Privacy features toggle correctly
- [x] Background service works
- [x] Quick Settings tile works
- [x] Settings persist after restart

---

### 5. Verify F-Droid Compliance

```bash
# Check for Google tracking URLs
strings app/build/outputs/apk/release/PrivacyFlip-v1.0.2-release.apk | grep -E "(google\.com|googleapis|firebase|gms)" | head -10

# Should return empty or only benign AndroidX references
```

---

## Quick Command Summary

```bash
# 1. Update version in app/build.gradle.kts (manual edit)

# 2. Build
./gradlew clean assembleRelease

# 3. Test
adb install app/build/outputs/apk/release/PrivacyFlip-v1.0.2-release.apk

# 4. Commit and tag
git add app/build.gradle.kts fastlane/metadata/android/en-US/changelogs/
git commit -m "Release v1.0.2 - Improvements and fixes"
git tag -a v1.0.2 -m "Version 1.0.2 - Improvements and fixes"
git push origin main
git push origin v1.0.2

# 5. Create GitHub release (manual via web interface)
```
