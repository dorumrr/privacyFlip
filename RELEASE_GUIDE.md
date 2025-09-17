# PrivacyFlip Release Guide

This guide explains how to create and publish releases for PrivacyFlip.

## 🚀 Building Release APKs

### Method 1: Using the Development Script (Recommended)
```bash
./test_dev.sh release
```

This command will:
- Extract version info from `app/build.gradle.kts`
- Clean previous builds
- Build the release APK
- Copy it to `releases/` directory with proper naming
- Provide release notes template

### Method 2: Manual Gradle Build
```bash
./gradlew clean assembleRelease
```

## 📦 APK Naming Convention

APKs are automatically named using this format:
```
PrivacyFlip-v{VERSION}-{BUILD_TYPE}.apk
```

Examples:
- `PrivacyFlip-v1.0.0-release.apk`
- `PrivacyFlip-v1.1.0-debug.apk`

## 🏷️ Creating GitHub Releases

### Step 1: Prepare the Release
1. Update version in `app/build.gradle.kts`:
   ```kotlin
   versionCode = 2
   versionName = "1.1.0"
   ```

2. Build the release APK:
   ```bash
   ./test_dev.sh release
   ```

3. Test the APK thoroughly on different devices

### Step 2: Create the GitHub Release

1. Go to your repository on GitHub
2. Click **"Releases"** in the right sidebar
3. Click **"Create a new release"**
4. Fill in the release form:
   - **Tag version**: `v1.0.0` (must start with 'v')
   - **Release title**: `PrivacyFlip v1.0.0`
   - **Description**: Use the template below
5. **Attach the APK**: Drag and drop `releases/PrivacyFlip-v1.0.0-release.apk`
6. Click **"Publish release"**

### Step 3: Release Notes Template

```markdown
## PrivacyFlip v1.0.0

### 🎉 What's New
- Initial release of PrivacyFlip
- Root-based privacy control for Android devices
- Screen lock/unlock automation
- Quick Settings tile integration
- Material 3 design with dark theme support

### 📱 Features
- **WiFi Control**: Auto-disable WiFi on screen lock
- **Bluetooth Control**: Auto-disable Bluetooth on screen lock  
- **Location Control**: Auto-disable location services on screen lock
- **Mobile Data Control**: Auto-disable mobile data on screen lock
- **Panic Mode**: Instantly disable all privacy features
- **Timer Settings**: Configurable delays for feature activation
- **Notifications**: Privacy status notifications

### 🔧 Installation
1. Download `PrivacyFlip-v1.0.0-release.apk`
2. Enable "Install from unknown sources" in Android settings
3. Install the APK
4. Grant root permission when prompted
5. Grant notification permission (Android 13+)

### 📋 Requirements
- **Android**: 5.0+ (API 21+)
- **Root Access**: Magisk, SuperSU, or similar
- **Permissions**: Notification access (Android 13+)

### 🐛 Known Issues
- None reported

### 🔗 Links
- [Source Code](https://github.com/dorumrr/privacyflip)
- [Issues & Bug Reports](https://github.com/dorumrr/privacyflip/issues)
- [Documentation](https://github.com/dorumrr/privacyflip/blob/main/README.md)

---
**Full Changelog**: https://github.com/dorumrr/privacyflip/commits/v1.0.0
```

## 🔄 Version Management

### Semantic Versioning
PrivacyFlip follows [Semantic Versioning](https://semver.org/):
- **MAJOR** (1.x.x): Breaking changes
- **MINOR** (x.1.x): New features, backwards compatible
- **PATCH** (x.x.1): Bug fixes, backwards compatible

### Version Update Checklist
- [ ] Update `versionCode` in `app/build.gradle.kts`
- [ ] Update `versionName` in `app/build.gradle.kts`
- [ ] Update version in `Constants.kt` (APP_NAME_VERSION)
- [ ] Build and test release APK
- [ ] Create GitHub release with proper tag
- [ ] Upload APK to release
- [ ] Write comprehensive release notes

## 📋 Manual Release Process

PrivacyFlip uses manual releases for better control and quality assurance:
- Build and test releases locally using `./test_dev.sh release`
- Create GitHub releases manually with proper release notes
- Upload APK as release asset after thorough testing
- Maintain full control over release timing and content

This would streamline the release process and ensure consistency.
