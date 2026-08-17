#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
BIN_PATH="${DIST_DIR}/QuietPanelBridge-linux-adb"

if [ ! -f "${BIN_PATH}" ]; then
    BIN_PATH="${SCRIPT_DIR}/bridge/target/release/QuietPanelBridge"
fi

if [ ! -f "${BIN_PATH}" ]; then
    echo "QuietPanelBridge binary not found. Running build-linux.sh first..."
    "${SCRIPT_DIR}/build-linux.sh"
    BIN_PATH="${DIST_DIR}/QuietPanelBridge-linux-adb"
fi

# Ensure adb is accessible
if ! command -v adb &>/dev/null; then
    if [ -f "${HOME}/.local/bin/adb" ]; then
        export PATH="${HOME}/.local/bin:${PATH}"
    elif [ -f "${HOME}/Android/Sdk/platform-tools/adb" ]; then
        export PATH="${HOME}/Android/Sdk/platform-tools:${PATH}"
    fi
fi

if command -v adb &>/dev/null; then
    echo "ADB found at: $(command -v adb)"
    echo "Attached Android devices:"
    adb devices || true
else
    echo "Warning: adb not found in PATH or standard locations."
    echo "Install via: sudo pacman -S android-tools"
fi

echo "Starting QuietPanel Linux Bridge (ADB mode)..."
cd "$(dirname "${BIN_PATH}")"
exec "${BIN_PATH}" "$@"
