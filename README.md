# PrivacyFlip

**Automatic lock/unlock privacy control for rooted Android devices 🔐📱✨**

PrivacyFlip automatically manages your device's privacy features based on lock/unlock state. When you lock your device, it disables Wi-Fi, Bluetooth, mobile data, location services, and NFC. When you unlock, it intelligently restores the features you want back on.

<center>

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/en/packages/io.github.dorumrr.privacyflip/)

</center>


> 🎉 **Now available on F-Droid!** Get it from the official repository with automatic updates and verified builds.

[![F-Droid](https://img.shields.io/badge/F--Droid-Available-brightgreen)](https://f-droid.org/en/packages/io.github.dorumrr.privacyflip/)
[![Android](https://img.shields.io/badge/Android-5.0%2B-blue)](https://developer.android.com)
[![Root Required](https://img.shields.io/badge/Root-Required-red)](https://en.wikipedia.org/wiki/Rooting_(Android))
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
[![Architecture](https://img.shields.io/badge/Architecture-Traditional%20Views-green)](https://developer.android.com/guide/topics/ui/declaring-layout)
[![Dependencies](https://img.shields.io/badge/Google%20Dependencies-Zero-brightgreen)](https://f-droid.org/docs/Anti-Features/)

<div align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="PrivacyFlip by Doru Moraru" width="300" style="margin: 10px; border: 1px solid #222222"/>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="PrivacyFlip by Doru Moraru" width="300" style="margin: 10px; border: 1px solid #222222"/>
</div>

## 🔒 Features

### **Automatic Privacy Control**
- **Lock Detection**: Instantly disables privacy features when screen locks
- **Unlock Detection**: Waits for proper authentication (not just screen-on)
- **Smart Restoration**: Configurable feature re-enabling on unlock

### **Privacy Features Controlled**
- 📶 **Wi-Fi** - Disable/enable wireless connectivity
- 📱 **Bluetooth** - Control Bluetooth radio
- 📡 **Mobile data** - Manage cellular data connection
- 📍 **Location services** - Control GPS and location tracking
- 📳 **NFC** - Control Near Field Communication sensor

### **Customizable Timing**
- **Lock Delay**: 0-60 seconds before privacy actions trigger
- **Unlock Delay**: 0-60 seconds before features are restored
- **Instant Mode**: Set delays to 0 for immediate action

## 📱 Requirements

- **Android 5.0+** (API level 21 and newer)
- **Root access** (Magisk, SuperSU, or similar)
- **Rooted device** with su binary available

## 🏗️ Architecture & Dependencies

- **Zero Google Dependencies** - Complete F-Droid compliance
- **Pure AndroidX** - Modern Android development without Google services
- **Traditional Android Views** - Efficient UI with ViewBinding
- **Navigation Component** - Fragment-based navigation
- **MVVM Pattern** - Reactive architecture with LiveData

### **Key Dependencies**
- **[libsu](https://github.com/topjohnwu/libsu)** - Reliable root access management
- **AndroidX Core Libraries** - Modern Android framework components
- **Work Manager** - Background task scheduling
- **Navigation Component** - Fragment navigation (Google Material excluded)
- **ViewBinding** - Type-safe view references

## 🚀 Installation

### From F-Droid (Recommended) ✅
PrivacyFlip is now available on F-Droid!

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/en/packages/io.github.dorumrr.privacyflip/)

1. Install [F-Droid](https://f-droid.org/) if you haven't already
2. Search for "PrivacyFlip" in F-Droid or [open directly](https://f-droid.org/en/packages/io.github.dorumrr.privacyflip/)
3. Install and enjoy automatic updates

**Benefits of F-Droid:**
- ✅ Automatic updates
- ✅ Verified builds from source
- ✅ No tracking or analytics
- ✅ 100% FOSS (Free and Open Source Software)

### Manual Installation (Alternative)
1. Download the latest APK from [Releases](https://github.com/dorumrr/privacyflip/releases)
2. Enable "Unknown sources" in Android settings
3. Install the APK
4. Grant root permissions when prompted

### Build from source

#### **Standard Gradle Build**
```bash
git clone https://github.com/dorumrr/privacyflip.git
cd privacyflip
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/PrivacyFlip-v1.0.0-debug.apk
```

#### **Using Fastlane (Recommended)**
```bash
# Install Fastlane first: gem install fastlane
fastlane build_debug    # Build debug APK
fastlane build_release  # Build release APK
```

#### **Development Testing**
```bash
./test_dev.sh install   # Build and install on connected device
./test_dev.sh fresh     # Clean build with fresh install
```

### F-Droid Metadata
This repository includes complete Fastlane metadata structure in `fastlane/metadata/` for F-Droid compliance.

## 🔧 Usage

### **First launch**
1. Open PrivacyFlip
2. Grant root permissions when prompted
3. Configure your preferred privacy settings
4. Set lock/unlock delays if desired

### **Automatic operation**
Once configured, PrivacyFlip works automatically:
- Lock your device → Selected features will be disabled
- Unlock your device → Selected features will be restored

## 🛡️ Privacy & Security

### **Data collection**
- **Zero telemetry** - No data sent to external servers
- **Local storage only** - All settings stored on device
- **No network permissions** - App cannot access internet
- **Open source** - Full code transparency

### **Permissions**
- **Root access**: Required for system-level privacy control
- **Screen state monitoring**: For lock/unlock detection via broadcast receivers
- **Foreground service**: For persistent background monitoring
- **Notification**: For privacy change alerts (Android 13+)

## 🤝 Contributing

No contribution is too small - every improvement helps the privacy community! 🚀

- 🐛 **Found a bug?** Open an issue or submit a PR with the fix
- 💡 **Have an idea?** Create an issue to discuss new features
- 📝 **Improve documentation?** Fix typos, add examples, or clarify instructions
- 🔧 **Code improvements?** Optimise performance, add tests, or refactor

**Simple workflow:**
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes and test them
4. Commit your changes (`git commit -m 'Add amazing feature'`)
5. Push to your branch (`git push origin feature/amazing-feature`)
6. Open a Pull Request

**Development setup:**
```bash
git clone https://github.com/dorumrr/privacyflip.git
cd privacyflip
./gradlew build
./test_dev.sh install    # Build and install on device/emulator
./test_dev.sh fresh      # Clean build with fresh install
fastlane build_debug     # Alternative: Use Fastlane for builds
```

## 📄 License

This project is licensed under the GNU General Public License v3.0

## 👨‍💻 Author

Giving Privacy its due, by [Doru Moraru](https://github.com/dorumrr/privacyflip)

## 🙏 Acknowledgments

- Degoogled Android community for inspiration and feedback
- [libsu](https://github.com/topjohnwu/libsu) - Reliable root access library
- Pure Android Views - No Google dependencies
- F-Droid community for privacy-focused app distribution

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/dorumrr/privacyflip/issues)
- **F-Droid**: [App Page](https://f-droid.org/en/packages/io.github.dorumrr.privacyflip/)