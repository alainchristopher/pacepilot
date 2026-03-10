#!/usr/bin/env bash
# PacePilot — build APK and install on Karoo
# Usage: ./scripts/build_and_install.sh [wifi|usb]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
LOCAL_PROPS="$PROJECT_ROOT/local.properties"

# --- Check local.properties is configured ---
if grep -q "YOUR_GITHUB" "$LOCAL_PROPS"; then
    echo "❌  local.properties still has placeholder values."
    echo "    Edit $LOCAL_PROPS and set:"
    echo "    gpr.user=YOUR_GITHUB_USERNAME"
    echo "    gpr.key=YOUR_GITHUB_PAT  (needs read:packages scope)"
    echo "    Create a token at: https://github.com/settings/tokens/new"
    exit 1
fi

# --- Build ---
echo "🔨  Building PacePilot debug APK..."
cd "$PROJECT_ROOT"
./gradlew :app:assembleDebug

if [ ! -f "$APK_PATH" ]; then
    echo "❌  Build failed — APK not found at $APK_PATH"
    exit 1
fi

echo "✅  Build succeeded: $APK_PATH"

# --- Install ---
MODE="${1:-usb}"

if [ "$MODE" = "wifi" ]; then
    echo ""
    read -p "📡  Enter Karoo IP address (find in Karoo Settings > Wi-Fi): " KAROO_IP
    echo "🔗  Connecting to Karoo at $KAROO_IP:5555..."
    adb connect "$KAROO_IP:5555"
    sleep 2
fi

echo ""
echo "📱  Installing on Karoo..."
adb install -r "$APK_PATH"

echo ""
echo "✅  PacePilot installed!"
echo "    The extension will appear in Karoo → Extensions"
echo "    It activates automatically when a ride starts recording."
