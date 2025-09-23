#!/bin/bash

# F-Droid Build & Scanner Script
# Usage: ./fdroid_scan.sh [build|scan|all] [specific-apk-path]
#
# Commands:
#   build  - Clean and build debug + release APKs
#   scan   - Scan existing APKs in app/build/outputs/apk/
#   all    - Build then scan (default)
#   [path] - Scan specific APK file

set -e

# Set up environment
export PATH="/Library/Frameworks/Python.framework/Versions/3.12/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🔍 F-Droid Build & Scanner${NC}"
echo "=========================="

# Function to clean build outputs
clean_build() {
    echo -e "${YELLOW}🧹 Cleaning build outputs...${NC}"
    ./gradlew clean
    rm -rf app/build/outputs/apk/
    echo -e "${GREEN}✅ Clean complete!${NC}"
    echo ""
}

# Function to build APKs
build_apks() {
    echo -e "${YELLOW}🔨 Building Debug APK...${NC}"
    ./gradlew assembleDebug
    echo -e "${GREEN}✅ Debug build complete!${NC}"
    echo ""

    echo -e "${YELLOW}� Building Release APK...${NC}"
    ./gradlew assembleRelease
    echo -e "${GREEN}✅ Release build complete!${NC}"
    echo ""
}

# Function to find all APKs
find_apks() {
    find app/build/outputs/apk/ -name "*.apk" -type f 2>/dev/null | sort
}

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

    echo -e "${BLUE}📱 Scanning: ${apk_name} (${apk_type})${NC}"
    echo "   Path: $apk_path"
    echo "   Size: $(du -h "$apk_path" | cut -f1)"
    echo ""

    # Run F-Droid scanner
    echo "Running F-Droid scanner..."
    if fdroid scanner "$apk_path"; then
        echo -e "${GREEN}✅ ${apk_name}: CLEAN - F-Droid compliant!${NC}"
    else
        echo -e "${RED}❌ ${apk_name}: Issues found!${NC}"
        return 1
    fi
    echo ""
}

# Function to scan all APKs
scan_all_apks() {
    local apks=($(find_apks))

    if [ ${#apks[@]} -eq 0 ]; then
        echo -e "${RED}❌ No APKs found in app/build/outputs/apk/${NC}"
        echo "   Run with 'build' or 'all' to build APKs first"
        exit 1
    fi

    echo -e "${BLUE}📋 Found ${#apks[@]} APK(s) to scan:${NC}"
    for apk in "${apks[@]}"; do
        echo "   - $(basename "$apk")"
    done
    echo ""

    local clean_count=0
    local total_count=${#apks[@]}

    for apk in "${apks[@]}"; do
        if scan_apk "$apk"; then
            ((clean_count++))
        fi
    done

    echo "=================================="
    echo -e "${BLUE}📊 F-Droid Scan Summary:${NC}"
    echo -e "   Clean APKs: ${GREEN}${clean_count}${NC}/${total_count}"

    if [ $clean_count -eq $total_count ]; then
        echo -e "${GREEN}🎉 All APKs are F-Droid compliant!${NC}"
        return 0
    else
        echo -e "${RED}⚠️  Some APKs have issues!${NC}"
        return 1
    fi
}

# Main command logic
COMMAND="${1:-all}"

case "$COMMAND" in
    "build")
        clean_build
        build_apks
        echo -e "${GREEN}🎯 Build complete! Use './fdroid_scan.sh scan' to scan APKs${NC}"
        ;;

    "scan")
        scan_all_apks
        ;;

    "all")
        clean_build
        build_apks
        scan_all_apks
        ;;

    "help"|"-h"|"--help")
        echo "F-Droid Build & Scanner Script"
        echo ""
        echo "Usage: $0 [command] [options]"
        echo ""
        echo "Commands:"
        echo "  build     - Clean and build debug + release APKs"
        echo "  scan      - Scan existing APKs in app/build/outputs/apk/"
        echo "  all       - Build then scan all APKs (default)"
        echo "  help      - Show this help message"
        echo ""
        echo "Examples:"
        echo "  $0                    # Build and scan all APKs"
        echo "  $0 build              # Only build APKs"
        echo "  $0 scan               # Only scan existing APKs"
        echo "  $0 path/to/app.apk    # Scan specific APK file"
        echo ""
        echo "Requirements:"
        echo "  - fdroidserver installed (pip3 install fdroidserver)"
        echo "  - Android SDK with ANDROID_HOME set"
        echo "  - Gradle project in current directory"
        ;;

    *)
        # If argument looks like a file path, scan it directly
        if [ -f "$COMMAND" ]; then
            echo -e "${BLUE}📱 Scanning specific APK: $(basename "$COMMAND")${NC}"
            echo ""
            scan_apk "$COMMAND"
        else
            echo -e "${RED}❌ Unknown command: $COMMAND${NC}"
            echo "Use '$0 help' for usage information"
            exit 1
        fi
        ;;
esac
