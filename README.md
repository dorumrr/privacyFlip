# PrivacyFlip

**Automatic lock/unlock privacy control for Android devices 🔐📱✨**

**PrivacyFlip** automatically manages your Android device's privacy features based on lock/unlock state. When you lock your device, it can disable **Wi-Fi**, **Bluetooth**, **mobile data**, **location** services, **NFC**, and even **camera/microphone sensors**. When you unlock, it intelligently restores the features you want back on.

**Works with Shizuku or Root** - Choose your preferred privilege method!

<div>
  <a href="https://f-droid.org/en/packages/io.github.dorumrr.privacyflip/" target="_blank" rel="noopener"><img height="50" src="https://f-droid.org/badge/get-it-on.png"></a> 
  <a href="https://apt.izzysoft.de/fdroid/index/apk/io.github.dorumrr.privacyflip" target="_blank" rel="noopener"><img height="50" src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png"></a> 
  <a href="https://www.buymeacoffee.com/ossdev"><img height="50" src="https://cdn.buymeacoffee.com/buttons/v2/arial-yellow.png" /></a>
</div>

## 📸 Screenshots

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
- 📷 **Camera** - Disable/enable camera sensor privacy (Android 12+)
- 🎤 **Microphone** - Disable/enable microphone sensor privacy (Android 12+)
- ✈️ **Airplane Mode** - Enable airplane mode on lock (opt-in, disables all radios)
- 🔋 **Battery Saver** - Enable battery saver mode on lock (opt-in)

### **Advanced Features**
- **Smart Disable Options**:
  - **"Only if unused/not connected"** - Don't disable WiFi, Bluetooth, or Location if actively in use
  - **"Only if not already enabled"** - Prevent connection resets by not re-enabling features that are already on
  - **"Only if not manually set"** - Respect manually enabled protection modes on unlock

- **App Exemptions** - Exclude specific apps from privacy actions when in foreground
- **Samsung NFC Auto-Retry** - Opt-in feature to combat Samsung payment framework NFC override (for Galaxy S, Note, Z series)
- **Accessibility Service** - Experimental support for side/power button instant-lock (opt-in, requires Accessibility permission)
- **Advanced Detection** - Multi-tier Bluetooth connection detection prevents unwanted headphone disconnects

### **Customizable Timing**
- **Lock Delay**: 0-60 seconds (granular), 2 minutes, or 5 minutes before privacy actions trigger
- **Unlock Delay**: 0-60 seconds (granular), 2 minutes, or 5 minutes before features are restored
- **Instant Mode**: Set delays to 0 for immediate action
- **Note**: Camera and microphone ignore custom delays and trigger immediately, due to Android limitations

## 📱 Requirements

**Minimum:** Android 7.0+ (API level 24)
**Camera/Microphone Features:** Android 12+ (API level 31)

**Choose your privilege method:**

### **Option 1: Shizuku** (No root required!)
- **Shizuku** app installed and running
- **ADB privileges** via USB debugging or wireless ADB
- **No root required** - works with ADB-level permissions

### **Option 2: Root Access** (Recommended for rooted devices)
- **Root access** via Magisk, SuperSU, or similar
- **Best performance** with UID 0 privileges

### **Option 3: Dhizuku** (Device Owner method)
- **Device Owner** or **Profile Owner** status
- **No root required**, no ADB needed after initial setup
- **Persistent privileges** - survives reboots
- **Best for**: Enterprise devices, work profiles, privacy-focused setups

### **Option 4: Sui** (Best of both worlds)
- **Rooted device** with Magisk installed
- **Sui Magisk** module installed
- **Best user experience** - no permission prompts, automatic startup

### **Privilege Detection Priority**
1. **Sui** - Magisk module providing Shizuku API with root (best UX)
2. **Root** - Traditional root access via Magisk/SuperSU
3. **Dhizuku** - Device Owner method (no root or ADB needed after setup)
4. **Shizuku** - ADB privileges via Shizuku app

## 🤝 Contributing

Help make this app better. No contribution is too small!

### How to Contribute

1. **Fork the repository**
2. **Create a feature branch** (`git checkout -b feature/amazing-feature`)
3. **Make your changes**
4. **Commit your changes** (`git commit -m 'Add some amazing feature'`)
5. **Push to the branch** (`git push origin feature/amazing-feature`)
6. **Open a Pull Request**

All contributions are **valued** and **appreciated**!

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 💖 Support Development

PrivacyFlip protects your privacy. You can protect its future!

[![DONATE](https://img.shields.io/badge/DONATE-FFD700?style=for-the-badge&logoColor=white)](https://buymeacoffee.com/ossdev)

---

*Late nights for brighter days*

Created by Doru Moraru
