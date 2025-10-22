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

# Main menu
case "${1:-menu}" in
    "emulator")
        echo "🎮 Starting Android Emulator..."

        # Try to find Pixel 9a Android 14 AVD first
        available_avds=$($EMULATOR_PATH -list-avds 2>/dev/null || echo "")
        if [ -z "$available_avds" ]; then
            echo "❌ No Android Virtual Devices (AVDs) found!"
            echo "   Create an AVD using Android Studio or:"
            echo "   avdmanager create avd -n Pixel_9a_API_34 -k 'system-images;android-34;google_apis;x86_64' -d 'pixel_9a'"
            exit 1
        fi

        # Try to find Pixel 9a Android 14 (API 34) AVD
        target_avd=""
        while IFS= read -r avd; do
            if echo "$avd" | grep -qi "pixel.*9a"; then
                target_avd="$avd"
                break
            elif echo "$avd" | grep -qi "pixel_9a"; then
                target_avd="$avd"
                break
            fi
        done <<< "$available_avds"

        # If not found, use first available AVD
        if [ -z "$target_avd" ]; then
            target_avd=$(echo "$available_avds" | head -n1)
            echo "   ⚠️  Pixel 9a Android 14 AVD not found, using: $target_avd"
        else
            echo "   ✅ Found Pixel 9a AVD: $target_avd"
        fi

        # Start emulator with graphics acceleration options (redirect output to suppress logs)
        echo "   Starting emulator..."
        $EMULATOR_PATH -avd "$target_avd" -gpu swiftshader_indirect -no-snapshot-load > /dev/null 2>&1 &
        echo "   Emulator starting in background..."
        echo "   Wait 1-2 minutes for it to fully boot, then run: ./dev.sh install"
        ;;
    
    "install")
        echo "🆕 Fresh install: Uninstall → Clean Build → Install → Launch"
        echo "   This ensures a clean state and fixes UI caching issues"
        echo ""

        # Check if emulator is running, if not start it
        if ! check_emulator; then
            echo "🎮 No emulator detected, starting one..."

            # Try to find Pixel 9a Android 14 AVD first
            available_avds=$($EMULATOR_PATH -list-avds 2>/dev/null || echo "")
            if [ -z "$available_avds" ]; then
                echo "❌ No Android Virtual Devices (AVDs) found!"
                echo "   Create an AVD using Android Studio first"
                exit 1
            fi

            # Try to find Pixel 9a Android 14 (API 34) AVD
            target_avd=""
            while IFS= read -r avd; do
                if echo "$avd" | grep -qi "pixel.*9a"; then
                    target_avd="$avd"
                    break
                elif echo "$avd" | grep -qi "pixel_9a"; then
                    target_avd="$avd"
                    break
                fi
            done <<< "$available_avds"

            # If not found, use first available AVD
            if [ -z "$target_avd" ]; then
                target_avd=$(echo "$available_avds" | head -n1)
                echo "   ⚠️  Pixel 9a Android 14 AVD not found, using: $target_avd"
            else
                echo "   ✅ Found Pixel 9a AVD: $target_avd"
            fi

            echo "   Starting emulator: $target_avd"
            $EMULATOR_PATH -avd "$target_avd" -gpu swiftshader_indirect -no-snapshot-load > /dev/null 2>&1 &
            echo "   Waiting for emulator to boot..."
            sleep 30
        fi

        wait_for_device

        # Uninstall old version
        echo "🗑️  Uninstalling old app version..."
        $ADB_PATH uninstall io.github.dorumrr.privacyflip 2>/dev/null || echo "   No previous version found"
        echo ""

        # Clean build
        echo "🧹 Cleaning previous builds..."
        ./gradlew clean
        echo ""

        # Build fresh debug APK
        echo "🔨 Building fresh debug APK..."
        ./gradlew assembleDebug
        echo "✅ Build complete!"
        echo ""

        # Install and launch
        install_apk
        launch_app
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
        echo ""

        # Check if keystore.properties exists
        if [ ! -f "keystore.properties" ]; then
            echo "❌ ERROR: keystore.properties not found!"
            echo ""
            echo "You need to create a release keystore first:"
            echo "1. Generate keystore:"
            echo "   keytool -genkey -v -keystore ~/privacyflip-release.keystore \\"
            echo "     -alias privacyflip -keyalg RSA -keysize 2048 -validity 10000"
            echo ""
            echo "2. Create keystore.properties file with:"
            echo "   storeFile=/path/to/privacyflip-release.keystore"
            echo "   storePassword=YOUR_PASSWORD"
            echo "   keyAlias=privacyflip"
            echo "   keyPassword=YOUR_PASSWORD"
            echo ""
            exit 1
        fi

        # Get version info from build.gradle.kts
        VERSION_NAME=$(grep 'versionName = ' app/build.gradle.kts | head -1 | sed 's/.*versionName = "\(.*\)".*/\1/')
        VERSION_CODE=$(grep 'versionCode = ' app/build.gradle.kts | head -1 | sed 's/.*versionCode = \(.*\)/\1/')

        echo "📋 Building release for:"
        echo "   App: PrivacyFlip"
        echo "   Version: $VERSION_NAME (versionCode: $VERSION_CODE)"
        echo ""

        # Clean previous builds (complete clean)
        echo "🧹 Cleaning previous builds..."
        ./gradlew clean
        rm -rf app/build/outputs/apk/release/*
        echo "✅ Clean complete!"
        echo ""

        # Build release APK with proper signing
        echo "🔨 Building release APK with proper signing key..."
        ./gradlew assembleRelease

        # Find the generated APK
        RELEASE_APK=$(find app/build/outputs/apk/release -name "*.apk" -type f | head -1)

        if [ -f "$RELEASE_APK" ]; then
            echo ""
            echo "✅ Release build successful!"
            echo ""
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "📦 RELEASE APK INFORMATION"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo ""
            echo "File: $(basename "$RELEASE_APK")"
            echo "Path: $RELEASE_APK"
            echo "Size: $(du -h "$RELEASE_APK" | cut -f1)"
            echo ""

            # Get APK signature information
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "🔐 APK SIGNATURE INFORMATION"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo ""

            # Extract and display certificate info
            CERT_INFO=$(unzip -p "$RELEASE_APK" META-INF/*.RSA 2>/dev/null | keytool -printcert 2>&1)

            # Get SHA256 fingerprint
            SHA256_FULL=$(echo "$CERT_INFO" | grep "SHA256:" | sed 's/.*SHA256: //')
            SHA256_FDROID=$(echo "$SHA256_FULL" | tr -d ':' | tr '[:upper:]' '[:lower:]')

            # Get SHA1 fingerprint
            SHA1_FULL=$(echo "$CERT_INFO" | grep "SHA1:" | sed 's/.*SHA1: //')

            # Get certificate owner
            CERT_OWNER=$(echo "$CERT_INFO" | grep "Owner:" | sed 's/Owner: //')

            echo "Certificate Owner:"
            echo "  $CERT_OWNER"
            echo ""
            echo "SHA-1 Fingerprint:"
            echo "  $SHA1_FULL"
            echo ""
            echo "SHA-256 Fingerprint:"
            echo "  $SHA256_FULL"
            echo ""
            echo "F-Droid AllowedAPKSigningKeys (SHA-256 without colons, lowercase):"
            echo "  $SHA256_FDROID"
            echo ""

            # Verify it's NOT the debug key
            if echo "$CERT_OWNER" | grep -q "CN=Android Debug"; then
                echo "⚠️  WARNING: APK is signed with DEBUG KEY!"
                echo "   This should NOT happen for F-Droid releases!"
                echo "   Check your keystore.properties configuration."
                echo ""
            else
                echo "✅ APK is signed with PROPER RELEASE KEY (not debug key)"
                echo ""
            fi

            # Get current git status
            CURRENT_COMMIT=$(git rev-parse HEAD)
            CURRENT_COMMIT_SHORT=$(git rev-parse --short HEAD)
            GIT_STATUS=$(git status --porcelain)

            if [ -n "$GIT_STATUS" ]; then
                echo "⚠️  WARNING: You have uncommitted changes!"
                echo "   Commit your changes before creating a release."
                echo ""
            fi

            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "📝 NEXT STEPS FOR F-DROID RELEASE"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo ""
            echo "⚠️  IMPORTANT: Complete these steps IN ORDER!"
            echo ""
            echo "1️⃣  UPDATE VERSION (if needed):"
            echo "   - Edit app/build.gradle.kts"
            echo "   - Update versionCode and versionName"
            echo "   - Create changelog file: fastlane/metadata/android/en-US/changelogs/{versionCode}.txt"
            echo ""
            echo "2️⃣  COMMIT ALL CHANGES:"
            echo "   git add ."
            echo "   git commit -m \"Release v${VERSION_NAME}: [describe changes]\""
            echo ""
            echo "3️⃣  CREATE AND PUSH TAG:"
            echo "   git tag -d v${VERSION_NAME}  # Delete old tag if exists (local)"
            echo "   git push origin :refs/tags/v${VERSION_NAME}  # Delete old tag if exists (remote)"
            echo "   git tag -a v${VERSION_NAME} -m \"Version ${VERSION_NAME}\""
            echo "   git push origin main"
            echo "   git push origin v${VERSION_NAME}"
            echo ""
            echo "4️⃣  CREATE GITHUB RELEASE:"
            echo "   - Go to: https://github.com/dorumrr/privacyflip/releases/new"
            echo "   - Tag: v${VERSION_NAME}"
            echo "   - Title: PrivacyFlip v${VERSION_NAME}"
            echo "   - Upload APK from: $RELEASE_APK"
            echo "   - Add release notes"
            echo ""
            echo "5️⃣  UPDATE F-DROID METADATA (f-droid-data repository):"
            echo ""
            echo "   In metadata/io.github.dorumrr.privacyflip.yml:"
            echo ""
            echo "   Builds:"
            echo "     - versionName: ${VERSION_NAME}"
            echo "       versionCode: ${VERSION_CODE}"
            echo "       commit: [COMMIT_HASH_AFTER_STEP_2]"
            echo "       subdir: app"
            echo "       gradle:"
            echo "         - yes"
            echo ""
            echo "   AllowedAPKSigningKeys: ${SHA256_FDROID}"
            echo ""
            echo "   CurrentVersion: ${VERSION_NAME}"
            echo "   CurrentVersionCode: ${VERSION_CODE}"
            echo ""
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "📋 QUICK REFERENCE"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo ""
            echo "APK Location: $RELEASE_APK"
            echo "Version: ${VERSION_NAME}"
            echo "Version Code: ${VERSION_CODE}"
            echo "Current Commit: ${CURRENT_COMMIT_SHORT} (will change after step 2)"
            echo "AllowedAPKSigningKeys: ${SHA256_FDROID}"
            echo ""
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo ""

        else
            echo "❌ Release build failed!"
            echo "   APK not found in expected location"
            exit 1
        fi
        ;;

    "fdroid-scanner")
        echo "🔍 F-Droid Scanner"
        echo "=================="
        echo ""

        # Check if fdroidserver is installed
        if ! command -v fdroid >/dev/null 2>&1; then
            echo "❌ fdroidserver not installed!"
            echo ""
            echo "Install with:"
            echo "  pip3 install fdroidserver"
            echo ""
            exit 1
        fi

        # Function to scan single APK
        scan_apk() {
            local apk_path="$1"
            local apk_name=$(basename "$apk_path")
            local apk_type="unknown"

            if [[ "$apk_path" == *"debug"* ]]; then
                apk_type="debug"
            elif [[ "$apk_path" == *"release"* ]]; then
                apk_type="release"
            fi

            echo "📱 Scanning: ${apk_name} (${apk_type})"
            echo "   Path: $apk_path"
            echo "   Size: $(du -h "$apk_path" | cut -f1)"
            echo ""

            local has_issues=false

            # Run F-Droid scanner
            echo "Running F-Droid scanner..."
            if ! fdroid scanner "$apk_path" 2>&1; then
                echo "❌ F-Droid scanner found issues!"
                has_issues=true
            fi

            # Additional URL scanning
            echo ""
            echo "Checking for tracking URLs..."

            # Extract and scan APK contents
            local temp_dir=$(mktemp -d)
            echo "   Extracting APK contents..."

            if unzip -o -q "$apk_path" -d "$temp_dir" 2>/dev/null; then
                echo "   ✅ APK extracted successfully"
            else
                echo "   ⚠️  APK extraction failed, using direct scan"
            fi

            # Check for Google tracking URLs
            echo "   Scanning extracted files..."
            local google_urls=""
            if [ -d "$temp_dir" ]; then
                google_urls=$(find "$temp_dir" -type f \( -name "*.dex" -o -name "*.arsc" \) -exec strings {} \; 2>/dev/null | grep -E "(issuetracker\.google\.com|googleapis\.com|google\.com/.*track)" | head -5)
            fi

            # Fallback to direct APK scan
            if [ -z "$google_urls" ]; then
                echo "   Fallback: Direct APK scan..."
                google_urls=$(strings "$apk_path" 2>/dev/null | grep -E "(issuetracker\.google\.com|googleapis\.com|google\.com/.*track)" | head -3)
            fi

            if [ -n "$google_urls" ]; then
                echo "🚩 Found tracking URLs:"
                echo "$google_urls" | while read -r url; do
                    echo "   • $url"
                done
                has_issues=true
            else
                echo "✅ No tracking URLs found"
            fi

            # Cleanup
            rm -rf "$temp_dir"

            echo ""
            if [ "$has_issues" = false ]; then
                echo "✅ ${apk_name}: CLEAN - F-Droid compliant!"
                return 0
            else
                echo "❌ ${apk_name}: Issues found!"
                return 1
            fi
            echo ""
        }

        # Find all APKs
        apks=($(find app/build/outputs/apk/ -name "*.apk" -type f 2>/dev/null | sort))

        if [ ${#apks[@]} -eq 0 ]; then
            echo "❌ No APKs found in app/build/outputs/apk/"
            echo "   Run './test_dev.sh build' or './test_dev.sh release' first"
            exit 1
        fi

        echo "📋 Found ${#apks[@]} APK(s) to scan:"
        for apk in "${apks[@]}"; do
            echo "   - $(basename "$apk")"
        done
        echo ""

        clean_count=0
        total_count=${#apks[@]}

        for apk in "${apks[@]}"; do
            if scan_apk "$apk"; then
                ((clean_count++))
            fi
        done

        echo "=================================="
        echo "📊 F-Droid Scan Summary:"
        echo "   Clean APKs: ${clean_count}/${total_count}"

        if [ $clean_count -eq $total_count ]; then
            echo "🎉 All APKs are F-Droid compliant!"
        else
            echo "⚠️  Some APKs have issues!"
            exit 1
        fi
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
        echo "📱 Development Commands:"
        echo "  check           - Check development environment setup"
        echo "  emulator        - Start Android emulator (prefers Pixel 9a Android 14)"
        echo "  install         - Fresh install: uninstall → clean build → install → launch"
        echo "  build           - Build debug APK only"
        echo ""
        echo "📦 Release Commands:"
        echo "  release         - Build release APK for F-Droid/GitHub distribution"
        echo "  fdroid-scanner  - Scan APKs for F-Droid compliance (tracking URLs, etc.)"
        echo ""
        echo "🚀 Quick Start:"
        echo "  1. ./test_dev.sh check       # Verify your setup"
        echo "  2. ./test_dev.sh install     # Start emulator + install app (all-in-one)"
        echo ""
        echo "💡 Common Workflows:"
        echo "  Development:    ./test_dev.sh install         # Fresh install with clean build"
        echo "  Release:        ./test_dev.sh release         # Build for distribution"
        echo "  F-Droid check:  ./test_dev.sh fdroid-scanner  # Verify F-Droid compliance"
        echo ""
        echo "📋 Requirements:"
        echo "  - Android SDK with ANDROID_HOME or ANDROID_SDK_ROOT set"
        echo "  - At least one Android Virtual Device (AVD) created"
        echo "  - Recommended: Pixel 9a Android 14 (API 34) AVD"
        echo "  - For fdroid-scanner: pip3 install fdroidserver"
        echo ""
        echo "🔧 Troubleshooting:"
        echo "  - Emulator won't start? Run: ./test_dev.sh check"
        echo "  - App won't install? Ensure device is connected and authorized"
        echo "  - UI issues? Run: ./test_dev.sh install (does clean build)"
        ;;
esac
