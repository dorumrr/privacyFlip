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
- [ ] App icon displays correctly (no cropping, no white margins)
- [ ] App launches successfully
- [ ] Root permissions work
- [ ] Privacy features toggle correctly
- [ ] Background service works
- [ ] Quick Settings tile works
- [ ] Settings persist after restart

---

### 5. Verify F-Droid Compliance (Optional)

```bash
# Check for Google tracking URLs
strings app/build/outputs/apk/release/PrivacyFlip-v1.0.2-release.apk | grep -E "(google\.com|googleapis|firebase|gms)" | head -10

# Should return empty or only benign AndroidX references
```

---

### 6. Commit and Tag

```bash
# Stage all changes
git add app/build.gradle.kts
git add fastlane/metadata/android/en-US/changelogs/

# Commit
git commit -m "Release v1.0.2 - Icon improvements and maintenance"

# Create annotated tag
git tag -a v1.0.2 -m "Version 1.0.2 - Icon improvements and maintenance"

# Push to remote
git push origin main
git push origin v1.0.2
```

---

### 7. Create GitHub Release

1. Go to: https://github.com/dorumrr/privacyflip/releases/new
2. Choose tag: `v1.0.2`
3. Release title: `PrivacyFlip v1.0.2`
4. Description (copy from changelog):

```markdown
## Version 1.0.2 - Maintenance Release

### Changes
• Updated app icon with improved adaptive icon support
• Enhanced icon display across all Android versions and launchers
• Cleaned up unnecessary icon resources
• Performance improvements and bug fixes
• Maintained 100% F-Droid compliance with zero Google dependencies

### Installation
Download the APK below and install on your rooted Android device.

**Requirements:**
- Android 5.0+ (API 21)
- Root access (Magisk, SuperSU, or similar)

### F-Droid Status
This app is 100% F-Droid compliant with zero Google dependencies.

### Checksums
SHA256: [Will be generated after upload]
```

5. Upload APK: `app/build/outputs/apk/release/PrivacyFlip-v1.0.2-release.apk`
6. Click "Publish release"

---

### 8. Generate Checksums (After Release)

```bash
# SHA256
sha256sum app/build/outputs/apk/release/PrivacyFlip-v1.0.2-release.apk

# MD5 (optional)
md5sum app/build/outputs/apk/release/PrivacyFlip-v1.0.2-release.apk
```

Add checksums to the GitHub release description.

---

### 9. Update F-Droid (If Already Submitted)

If your app is already in F-Droid:
- F-Droid will automatically detect the new tag
- The build will be triggered automatically
- Monitor the build status on F-Droid's GitLab

If not yet in F-Droid:
- Wait for initial RFP approval
- Or submit merge request to fdroiddata repository

---

### 10. Announce Release (Optional)

Update:
- README.md badges (if version shown)
- Project documentation
- Social media / forums
- F-Droid RFP thread (if applicable)

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
git commit -m "Release v1.0.2 - Icon improvements and maintenance"
git tag -a v1.0.2 -m "Version 1.0.2 - Icon improvements and maintenance"
git push origin main
git push origin v1.0.2

# 5. Generate checksums
sha256sum app/build/outputs/apk/release/PrivacyFlip-v1.0.2-release.apk

# 6. Create GitHub release (manual via web interface)
```

---

## Files Modified for v1.0.2

1. `app/build.gradle.kts` - Version numbers updated
2. `fastlane/metadata/android/en-US/changelogs/2.txt` - Created (v1.0.1 changelog)
3. `fastlane/metadata/android/en-US/changelogs/3.txt` - Created (v1.0.2 changelog)

---

## F-Droid Compliance Verified ✅

- ✅ Zero Google dependencies
- ✅ No tracking or telemetry
- ✅ No network permissions
- ✅ Pure AndroidX implementation
- ✅ Open source (GPL-3.0)
- ✅ Complete metadata structure
- ✅ ProGuard rules for compliance

**Status:** Ready for release!

---

**Last Updated:** October 2, 2025

