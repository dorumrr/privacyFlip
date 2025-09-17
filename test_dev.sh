#!/bin/bash

# PrivacyFlip App Development Script
# Comprehensive development and testing tool for the PrivacyFlip Android app

set -e

# Auto-detect Android SDK paths
detect_android_sdk() {
    # Common Android SDK locations
    local sdk_paths=(
        "$ANDROID_HOME"
        "$ANDROID_SDK_ROOT"
        "$HOME/Library/Android/sdk"
        "$HOME/Android/Sdk"
        "$HOME/android-sdk"
        "/opt/android-sdk"
        "/usr/local/android-sdk"
    )

    for path in "${sdk_paths[@]}"; do
        if [ -n "$path" ] && [ -d "$path" ]; then
            echo "$path"
            return 0
        fi
    done

    return 1
}

# Set paths with auto-detection
if ANDROID_SDK=$(detect_android_sdk); then
    EMULATOR_PATH="$ANDROID_SDK/emulator/emulator"
    ADB_PATH="$ANDROID_SDK/platform-tools/adb"
else
    echo "❌ Android SDK not found!"
    echo "   Please set ANDROID_HOME or ANDROID_SDK_ROOT environment variable"
    echo "   Or install Android SDK to a standard location"
    exit 1
fi

# Function to find the most recent APK (debug or release)
find_apk() {
    # Look for release APK first (more recent builds)
    RELEASE_APK=$(find app/build/outputs/apk/release -name "*.apk" -type f 2>/dev/null | head -1)
    DEBUG_APK=$(find app/build/outputs/apk/debug -name "*.apk" -type f 2>/dev/null | head -1)

    if [ -f "$RELEASE_APK" ] && [ -f "$DEBUG_APK" ]; then
        # Both exist, use the newer one
        if [ "$RELEASE_APK" -nt "$DEBUG_APK" ]; then
            echo "$RELEASE_APK"
        else
            echo "$DEBUG_APK"
        fi
    elif [ -f "$RELEASE_APK" ]; then
        echo "$RELEASE_APK"
    elif [ -f "$DEBUG_APK" ]; then
        echo "$DEBUG_APK"
    else
        echo ""
    fi
}

echo "🔒 PrivacyFlip App Development Script"
echo "====================================="

# Function to check if emulator or device is available
check_emulator() {
    if $ADB_PATH devices | grep -E "(emulator|device)" | grep -v "List of devices" | grep -q "device"; then
        return 0
    else
        return 1
    fi
}

# Function to wait for device to be ready
wait_for_device() {
    echo "⏳ Waiting for device to be ready..."
    $ADB_PATH wait-for-device
    
    # Wait for boot to complete
    while [ "$($ADB_PATH shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
        echo "   Still booting..."
        sleep 2
    done
    echo "✅ Device is ready!"
}

# Function to install APK
install_apk() {
    APK_PATH=$(find_apk)

    if [ -z "$APK_PATH" ]; then
        echo "❌ No APK found!"
        echo "   Available options:"
        echo "   - Run './test_dev.sh build' for debug APK"
        echo "   - Run './test_dev.sh release' for release APK"
        exit 1
    fi

    APK_TYPE="debug"
    if [[ "$APK_PATH" == *"release"* ]]; then
        APK_TYPE="release"
    fi

    echo "📱 Installing PrivacyFlip APK ($APK_TYPE)..."
    echo "   APK: $(basename "$APK_PATH")"
    $ADB_PATH install -r "$APK_PATH"
    echo "✅ APK installed successfully!"
}

# Function to launch app
launch_app() {
    echo "🚀 Launching PrivacyFlip app..."
    $ADB_PATH shell am start -n "io.github.dorumrr.privacyflip/.MainActivity"
    echo "✅ App launched!"
}

# Function to show logs
show_logs() {
    echo "📋 Showing app logs (Ctrl+C to stop):"
    $ADB_PATH logcat -s "PrivacyFlip"
}

# Main menu
case "${1:-menu}" in
    "emulator")
        echo "🎮 Starting Android Emulator..."

        # Try to find an available AVD
        available_avds=$($EMULATOR_PATH -list-avds 2>/dev/null || echo "")
        if [ -z "$available_avds" ]; then
            echo "❌ No Android Virtual Devices (AVDs) found!"
            echo "   Create an AVD using Android Studio or:"
            echo "   avdmanager create avd -n test_device -k 'system-images;android-34;google_apis;x86_64'"
            exit 1
        fi

        # Use the first available AVD
        first_avd=$(echo "$available_avds" | head -n1)
        echo "   Using AVD: $first_avd"

        # Start emulator with graphics acceleration options to prevent black screen
        echo "   Starting with software graphics (fixes black screen issues)..."
        $EMULATOR_PATH -avd "$first_avd" -gpu swiftshader_indirect -no-snapshot-load &
        echo "   Emulator starting in background..."
        echo "   Wait 1-2 minutes for it to fully boot, then run: ./test_dev.sh install"
        echo ""
        echo "   💡 If you still see a black screen, try:"
        echo "      ./test_dev.sh emulator-safe  # Uses software rendering"
        ;;
    
    "install")
        if ! check_emulator; then
            echo "❌ No emulator or device detected"
            echo "   Connect a device or run: ./test_dev.sh emulator"
            exit 1
        fi

        echo "🔨 Building latest debug version..."
        ./gradlew assembleDebug
        echo "✅ Build complete!"

        wait_for_device
        install_apk
        launch_app
        ;;
    
    "launch")
        if ! check_emulator; then
            echo "❌ No emulator or device detected"
            exit 1
        fi
        launch_app
        ;;
    
    "logs")
        if ! check_emulator; then
            echo "❌ No emulator or device detected"
            exit 1
        fi
        show_logs
        ;;

    "screenshot"|"screen")
        if ! check_emulator; then
            echo "❌ No emulator or device detected"
            exit 1
        fi
        echo "📸 Taking screenshot..."
        timestamp=$(date +%Y%m%d_%H%M%S)
        filename="screenshot.png"
        $ADB_PATH exec-out screencap -p > "$filename"
        echo "✅ Screenshot saved as: $filename"
        ;;

    "status")
        echo "📋 PrivacyFlip Build Status"
        echo "=========================="

        APK_PATH=$(find_apk)
        if [ -n "$APK_PATH" ]; then
            APK_TYPE="debug"
            if [[ "$APK_PATH" == *"release"* ]]; then
                APK_TYPE="release"
            fi
            echo "✅ APK available for installation:"
            echo "   Type: $APK_TYPE"
            echo "   File: $(basename "$APK_PATH")"
            echo "   Path: $APK_PATH"
            echo "   Size: $(du -h "$APK_PATH" | cut -f1)"
            echo "   Modified: $(stat -f "%Sm" -t "%Y-%m-%d %H:%M:%S" "$APK_PATH" 2>/dev/null || date -r "$APK_PATH" 2>/dev/null || echo "Unknown")"
        else
            echo "❌ No APK available"
            echo "   Run './test_dev.sh build' or './test_dev.sh release' first"
        fi

        # Check if releases directory exists
        if [ -d "releases" ] && [ "$(ls -A releases 2>/dev/null)" ]; then
            echo ""
            echo "📦 Release artifacts:"
            ls -la releases/
        fi
        ;;

    "build")
        echo "🔨 Building PrivacyFlip Debug APK..."
        ./gradlew assembleDebug
        echo "✅ Debug build complete!"
        echo "   APK: $(find app/build/outputs/apk/debug -name "*.apk" -type f | head -1)"
        ;;

    "release")
        echo "🚀 PrivacyFlip Release Builder"
        echo "============================="

        # Get version info from build.gradle.kts
        VERSION_NAME=$(grep 'versionName = ' app/build.gradle.kts | head -1 | sed 's/.*versionName = "\(.*\)".*/\1/')
        VERSION_CODE=$(grep 'versionCode = ' app/build.gradle.kts | head -1 | sed 's/.*versionCode = \(.*\)/\1/')

        echo "📋 Building release for:"
        echo "   App: PrivacyFlip"
        echo "   Version: $VERSION_NAME (build $VERSION_CODE)"
        echo ""

        # Clean previous builds
        echo "🧹 Cleaning previous builds..."
        ./gradlew clean

        # Build release APK
        echo "🔨 Building release APK..."
        ./gradlew assembleRelease

        # Find the generated APK
        RELEASE_APK=$(find app/build/outputs/apk/release -name "*.apk" -type f | head -1)

        if [ -f "$RELEASE_APK" ]; then
            echo "✅ Release build successful!"
            echo ""
            echo "📦 Release APK:"
            echo "   File: $(basename "$RELEASE_APK")"
            echo "   Path: $RELEASE_APK"
            echo "   Size: $(du -h "$RELEASE_APK" | cut -f1)"
            echo ""

            # Create releases directory if it doesn't exist
            mkdir -p releases

            # Copy APK to releases directory with proper naming
            RELEASE_NAME="PrivacyFlip-v${VERSION_NAME}-release.apk"
            cp "$RELEASE_APK" "releases/$RELEASE_NAME"

            echo "📁 Copied to releases directory:"
            echo "   releases/$RELEASE_NAME"
            echo ""

            # Show APK info
            echo "ℹ️  APK Information:"
            if command -v aapt >/dev/null 2>&1; then
                aapt dump badging "$RELEASE_APK" | grep -E "package|application-label|platformBuildVersionName" || true
            else
                echo "   (Install Android SDK build-tools for detailed APK info)"
            fi

            echo ""
            echo "🎯 Ready for GitHub Release!"
            echo "   Upload: releases/$RELEASE_NAME"
            echo ""
            echo "📝 Suggested release notes:"
            echo "   ## PrivacyFlip v$VERSION_NAME"
            echo "   "
            echo "   ### What's New"
            echo "   - [Add your changes here]"
            echo "   "
            echo "   ### Installation"
            echo "   1. Download \`$RELEASE_NAME\`"
            echo "   2. Enable \"Install from unknown sources\" in Android settings"
            echo "   3. Install the APK"
            echo "   4. Grant root permission when prompted"
            echo "   "
            echo "   ### Requirements"
            echo "   - Android 5.0+ (API 21+)"
            echo "   - Root access (Magisk, SuperSU, etc.)"

        else
            echo "❌ Release build failed!"
            echo "   APK not found in expected location"
            exit 1
        fi
        ;;

    "clean-build")
        echo "🧹 Clean build (clears cache and rebuilds)..."
        ./gradlew clean
        ./gradlew assembleDebug
        echo "✅ Clean build complete!"
        ;;

    "uninstall")
        if ! check_emulator; then
            echo "❌ No emulator or device detected"
            exit 1
        fi

        echo "🗑️ Uninstalling PrivacyFlip app..."
        # Uninstall both old and new package names to handle app renames
        $ADB_PATH uninstall io.github.dorumrr.privacyflip 2>/dev/null || echo "   Current version not found"
        echo "✅ App uninstalled!"
        ;;
    
    "full")
        echo "🔄 Full test cycle: Build → Install → Launch"
        ./gradlew assembleDebug

        if ! check_emulator; then
            echo "🎮 Starting emulator..."

            # Try to find an available AVD
            available_avds=$($EMULATOR_PATH -list-avds 2>/dev/null || echo "")
            if [ -z "$available_avds" ]; then
                echo "❌ No Android Virtual Devices (AVDs) found!"
                echo "   Create an AVD using Android Studio first"
                exit 1
            fi

            # Use the first available AVD
            first_avd=$(echo "$available_avds" | head -n1)
            echo "   Using AVD: $first_avd"
            $EMULATOR_PATH -avd "$first_avd" -gpu swiftshader_indirect -no-snapshot-load &
            echo "   Waiting for emulator to boot..."
            sleep 30
        fi

        wait_for_device
        install_apk
        launch_app
        ;;

    "fresh")
        echo "🆕 Fresh install: Clean → Build → Uninstall → Install → Launch"
        echo "   This fixes UI caching issues"

        # Clean build
        ./gradlew clean
        ./gradlew assembleDebug

        if ! check_emulator; then
            echo "❌ No emulator or device detected"
            echo "   Connect a device or run: ./test_dev.sh emulator"
            exit 1
        fi

        wait_for_device

        # Uninstall old versions (both old and new package names)
        echo "🗑️ Removing old app versions..."
        $ADB_PATH uninstall io.github.dorumrr.privacyflip 2>/dev/null || echo "   Current version not found"

        # Install fresh
        install_apk
        launch_app
        ;;
    
    "emulator-safe")
        echo "🎮 Starting Android Emulator (Safe Mode - Software Rendering)..."

        # Try to find an available AVD
        available_avds=$($EMULATOR_PATH -list-avds 2>/dev/null || echo "")
        if [ -z "$available_avds" ]; then
            echo "❌ No Android Virtual Devices (AVDs) found!"
            echo "   Create an AVD using Android Studio or:"
            echo "   avdmanager create avd -n test_device -k 'system-images;android-34;google_apis;x86_64'"
            exit 1
        fi

        # Use the first available AVD
        first_avd=$(echo "$available_avds" | head -n1)
        echo "   Using AVD: $first_avd"

        # Start emulator with safe graphics settings
        echo "   Starting with software rendering (slower but more compatible)..."
        $EMULATOR_PATH -avd "$first_avd" -gpu software -no-snapshot-load -no-boot-anim &
        echo "   Emulator starting in background..."
        echo "   This mode is slower but should fix black screen issues"
        echo "   Wait 2-3 minutes for it to fully boot, then run: ./test_dev.sh install"
        ;;

    "studio")
        echo "🎨 Opening project in Android Studio..."
        open -a "Android Studio" .
        ;;

    "check")
        echo "🔍 Checking development environment..."
        echo ""
        echo "Android SDK: $ANDROID_SDK"
        echo "ADB: $ADB_PATH"
        echo "Emulator: $EMULATOR_PATH"
        echo ""

        if [ -f "$ADB_PATH" ]; then
            echo "✅ ADB found"
        else
            echo "❌ ADB not found"
        fi

        if [ -f "$EMULATOR_PATH" ]; then
            echo "✅ Emulator found"
        else
            echo "❌ Emulator not found"
        fi

        echo ""
        echo "Available AVDs:"
        $EMULATOR_PATH -list-avds 2>/dev/null || echo "   No AVDs found"

        echo ""
        echo "Connected devices:"
        $ADB_PATH devices
        ;;

    *)
        echo "Usage: $0 [command]"
        echo ""
        echo "Commands:"
        echo "  check         - Check development environment setup"
        echo "  emulator      - Start Android emulator (auto-selects first AVD)"
        echo "  emulator-safe - Start emulator with software rendering (fixes black screen)"
        echo "  build         - Build the APK"
        echo "  release       - Build release APK (for distribution)"
        echo "  clean-build   - Clean build (clears cache, fixes UI issues)"
        echo "  install       - Build, install and launch the latest APK"
        echo "  uninstall     - Remove app from device/emulator"
        echo "  launch        - Launch the PrivacyFlip app"
        echo "  logs          - Show app logs"
        echo "  screenshot    - Take a screenshot of the current screen"
        echo "  status        - Show build status and available APKs"
        echo "  full          - Build, install, and launch (complete test)"
        echo "  fresh         - Clean build + fresh install (fixes UI issues)"
        echo "  studio        - Open project in Android Studio"
        echo ""
        echo "Quick start for new developers:"
        echo "  1. ./test_dev.sh check       # Verify your setup"
        echo "  2. ./test_dev.sh emulator    # Start emulator"
        echo "  3. ./test_dev.sh install     # Install & launch app"
        echo ""
        echo "Or for complete test:"
        echo "  ./test_dev.sh full"
        echo ""
        echo "Requirements:"
        echo "  - Android SDK with ANDROID_HOME or ANDROID_SDK_ROOT set"
        echo "  - At least one Android Virtual Device (AVD) created"
        echo "  - Or a physical Android device connected via USB"
        echo ""
        echo "Troubleshooting:"
        echo "  - Black emulator screen? Try: ./test_dev.sh emulator-safe"
        echo "  - Old/wrong UI design? Try: ./test_dev.sh clean-build"
        echo "  - Emulator won't start? Check: ./test_dev.sh check"
        echo "  - App won't install? Ensure device is connected and authorized"
        ;;
esac
