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
    # Prefer release over debug
    RELEASE_APK=$(find app/build/outputs/apk/release -name "*.apk" -type f 2>/dev/null | head -1)
    DEBUG_APK=$(find app/build/outputs/apk/debug -name "*.apk" -type f 2>/dev/null | head -1)

    # Priority order: release > debug
    if [ -f "$RELEASE_APK" ]; then
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

# Guard against building a release from a tree nobody else can reproduce.
# A release APK has to be reproducible from what is published, because IzzyOnDroid rebuilds the
# tag from source and compares the result to the published APK byte for byte. Gradle compiles the
# WORKING TREE, not git - so an edited file, an untracked .kt, or a commit that never left this
# machine all end up inside the APK while being absent from the tag anyone else can fetch. The
# rebuild then differs and the check fails, and the only clue is a hash mismatch weeks later.
#
# Untracked files really do matter here: a new source file that was never `git add`ed compiles in.
# Files ignored by .gitignore are not reported - git status --porcelain already leaves them out -
# so build output and scratch files do not trip this.
check_release_tree_is_published() {
    if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        echo "⚠️  Not a git repository - cannot check whether this build is reproducible."
        return 0
    fi

    local dirty="" upstream="" unpushed="" confirm=""

    dirty=$(git status --porcelain 2>/dev/null || true)
    upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || true)
    if [ -n "$upstream" ]; then
        unpushed=$(git log --oneline "${upstream}..HEAD" 2>/dev/null || true)
    fi

    if [ -z "$dirty" ] && [ -z "$unpushed" ] && [ -n "$upstream" ]; then
        echo "✅ Working tree is clean and pushed to ${upstream}"
        return 0
    fi

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "🚫 RELEASE BLOCKED - this build would not be reproducible"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    if [ -n "$dirty" ]; then
        echo "  Uncommitted changes ($(printf '%s\n' "$dirty" | wc -l | tr -d ' ')):"
        printf '%s\n' "$dirty" | sed 's/^/    /'
        echo ""
    fi

    if [ -z "$upstream" ]; then
        echo "  This branch has no upstream, so nothing here has been pushed."
        echo "  Set one with: git push -u origin $(git rev-parse --abbrev-ref HEAD)"
        echo ""
    elif [ -n "$unpushed" ]; then
        echo "  Commits not pushed to ${upstream} ($(printf '%s\n' "$unpushed" | wc -l | tr -d ' ')):"
        printf '%s\n' "$unpushed" | sed 's/^/    /'
        echo ""
    fi

    echo "  Anyone rebuilding this version from git will NOT get this APK."
    echo "  Commit and push first, then tag the commit you build from."
    echo ""

    read -p "Continue anyway? (yes/no): " confirm || confirm=""
    if [ "$confirm" != "yes" ]; then
        echo "❌ Release build cancelled - nothing was built."
        exit 1
    fi

    echo "⚠️  Building from an unpublished tree at your request. Do not publish this APK."
    echo ""
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
    
    "update")
        echo "🔄 Update: Build → Reinstall → Launch (preserves app data)"
        echo ""

        # Check if emulator/device is running
        if ! check_emulator; then
            echo "❌ No device or emulator detected!"
            echo "   Please start an emulator or connect a device first"
            echo "   Run: ./dev.sh emulator"
            exit 1
        fi

        wait_for_device

        # Build debug APK (incremental, no clean)
        echo "🔨 Building debug APK..."
        ./gradlew assembleDebug
        echo "✅ Build complete!"
        echo ""

        # Install debug APK explicitly (not release)
        DEBUG_APK=$(find app/build/outputs/apk/debug -name "*.apk" -type f 2>/dev/null | head -1)
        if [ -z "$DEBUG_APK" ]; then
            echo "❌ Debug APK not found!"
            exit 1
        fi
        echo "📱 Installing PrivacyFlip APK (debug)..."
        echo "   APK: $(basename "$DEBUG_APK")"
        $ADB_PATH install -r "$DEBUG_APK"
        echo "✅ APK installed successfully!"
        
        launch_app
        ;;
    
    "build")
        echo "🔨 Building PrivacyFlip Debug APK..."
        ./gradlew assembleDebug
        echo "✅ Debug build complete!"
        echo "   APK: $(find app/build/outputs/apk/debug -name "*.apk" -type f | head -1)"
        ;;

    "screenshot")
        echo "📸 Taking Screenshot..."
        echo ""

        # Check if device/emulator is connected
        if ! check_emulator; then
            echo "❌ No device or emulator detected!"
            echo "   Please start an emulator or connect a device first"
            echo "   Run: ./dev.sh emulator"
            exit 1
        fi

        # Create screenshots directory if it doesn't exist
        SCREENSHOTS_DIR="screenshots"
        mkdir -p "$SCREENSHOTS_DIR"

        # Generate timestamp for filename
        TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
        SCREENSHOT_NAME="screenshot_${TIMESTAMP}.png"
        LOCAL_PATH="${SCREENSHOTS_DIR}/${SCREENSHOT_NAME}"

        echo "📱 Capturing screenshot from device..."

        # Take screenshot on device (saves to /sdcard/)
        DEVICE_PATH="/sdcard/${SCREENSHOT_NAME}"
        $ADB_PATH shell screencap -p "$DEVICE_PATH"

        if [ $? -ne 0 ]; then
            echo "❌ Failed to capture screenshot on device"
            exit 1
        fi

        echo "💾 Pulling screenshot to local machine..."
        $ADB_PATH pull "$DEVICE_PATH" "$LOCAL_PATH"

        if [ $? -ne 0 ]; then
            echo "❌ Failed to pull screenshot from device"
            exit 1
        fi

        # Clean up screenshot from device
        $ADB_PATH shell rm "$DEVICE_PATH" 2>/dev/null

        echo "✅ Screenshot saved: $LOCAL_PATH"
        echo ""

        # Open the screenshot
        echo "🖼️  Opening screenshot..."
        if [[ "$OSTYPE" == "darwin"* ]]; then
            # macOS
            open "$LOCAL_PATH"
        elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
            # Linux
            if command -v xdg-open &> /dev/null; then
                xdg-open "$LOCAL_PATH"
            elif command -v gnome-open &> /dev/null; then
                gnome-open "$LOCAL_PATH"
            else
                echo "   ⚠️  Could not auto-open screenshot. Please open manually: $LOCAL_PATH"
            fi
        else
            echo "   ⚠️  Auto-open not supported on this OS. Screenshot saved to: $LOCAL_PATH"
        fi

        echo ""
        echo "🎉 Done!"
        ;;

    "release")
        echo "🚀 PrivacyFlip Release Builder"
        echo "============================="
        echo ""

        # Refuse to build something nobody else could reproduce (see the function above).
        check_release_tree_is_published

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
        echo "✅ Clean complete!"
        echo ""

        # Build release APK with proper signing
        echo "🔨 Building release APK with proper signing key..."
        ./gradlew assembleRelease

        # Find the generated APK
        RELEASE_APK=$(find app/build/outputs/apk/release -name "*.apk" -type f 2>/dev/null | head -1)

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

            # Find apksigner tool
            APKSIGNER=""
            if [ -n "$ANDROID_HOME" ]; then
                APKSIGNER=$(find "$ANDROID_HOME/build-tools" -name "apksigner" 2>/dev/null | head -1)
            fi
            if [ -z "$APKSIGNER" ]; then
                APKSIGNER=$(find ~/Library/Android/sdk/build-tools -name "apksigner" 2>/dev/null | head -1)
            fi

            if [ -n "$APKSIGNER" ] && [ -f "$APKSIGNER" ]; then
                # Use apksigner to get certificate info
                CERT_INFO=$("$APKSIGNER" verify --print-certs "$RELEASE_APK" 2>&1)

                # Get certificate DN (owner)
                CERT_OWNER=$(echo "$CERT_INFO" | grep "certificate DN:" | head -1 | sed 's/.*certificate DN: //')

                # Get SHA256 fingerprint
                SHA256_FULL=$(echo "$CERT_INFO" | grep "SHA-256 digest:" | head -1 | sed 's/.*SHA-256 digest: //')
                SHA256_FDROID="$SHA256_FULL"  # Already in lowercase without colons

                # Get SHA1 fingerprint
                SHA1_FULL=$(echo "$CERT_INFO" | grep "SHA-1 digest:" | head -1 | sed 's/.*SHA-1 digest: //')

                echo "Certificate Owner:"
                echo "  $CERT_OWNER"
                echo ""
                echo "SHA-1 Fingerprint:"
                echo "  $SHA1_FULL"
                echo ""
                echo "SHA-256 Fingerprint:"
                echo "  $SHA256_FULL"
                echo ""
                echo "F-Droid AllowedAPKSigningKeys (SHA-256):"
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
            else
                echo "⚠️  apksigner tool not found - cannot verify signature"
                echo "   Install Android SDK build-tools to see signature information"
                echo ""
            fi

            # Get current commit info for the summary below. Tree state itself was
            # already checked and confirmed before the build ran, see the guard above.
            CURRENT_COMMIT=$(git rev-parse HEAD)
            CURRENT_COMMIT_SHORT=$(git rev-parse --short HEAD)

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
        echo "  update          - Quick update: build → reinstall → launch (preserves data)"
        echo "  build           - Build debug APK only"
        echo "  screenshot      - Take screenshot from device/emulator and open it"
        echo ""
        echo "📦 Release Commands:"
        echo "  release         - Build release APK for F-Droid/GitHub distribution"
        echo "  fdroid-scanner  - Scan APKs for F-Droid compliance (tracking URLs, etc.)"
        echo ""
        echo "🚀 Quick Start:"
        echo "  1. ./dev.sh check       # Verify your setup"
        echo "  2. ./dev.sh install     # Start emulator + install app (all-in-one)"
        echo ""
        echo "💡 Common Workflows:"
        echo "  Development:    ./dev.sh install         # Fresh install with clean build"
        echo "  Iteration:      ./dev.sh update          # Quick rebuild + reinstall"
        echo "  Release:        ./dev.sh release         # Build for distribution"
        echo "  F-Droid check:  ./dev.sh fdroid-scanner  # Verify F-Droid compliance"
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
