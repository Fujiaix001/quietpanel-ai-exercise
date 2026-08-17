#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"
VERSION="$(cat "${SCRIPT_DIR}/VERSION" | tr -d '[:space:]')"

mkdir -p "${DIST_DIR}"

# Ensure cargo is available
if ! command -v cargo &>/dev/null; then
    if [ -f "${HOME}/.cargo/bin/cargo" ]; then
        export PATH="${HOME}/.cargo/bin:${PATH}"
    else
        echo "Error: cargo is not installed. Please install rustup or cargo." >&2
        exit 1
    fi
fi

echo "=== Building QuietPanel Linux Bridge v${VERSION} (USB ADB Mode) ==="

cd "${SCRIPT_DIR}/bridge"

echo "Running Rust tests..."
cargo test

echo "Building release binary..."
cargo build --release

RELEASE_BIN="${SCRIPT_DIR}/bridge/target/release/QuietPanelBridge"
if [ ! -f "${RELEASE_BIN}" ]; then
    echo "Error: Build output ${RELEASE_BIN} not found!" >&2
    exit 1
fi

TARGET_VERSIONED="${DIST_DIR}/QuietPanelBridge-v${VERSION}-linux-adb"
TARGET_STABLE="${DIST_DIR}/QuietPanelBridge-linux-adb"

cp -f "${RELEASE_BIN}" "${TARGET_VERSIONED}"
cp -f "${RELEASE_BIN}" "${TARGET_STABLE}"
chmod +x "${TARGET_VERSIONED}" "${TARGET_STABLE}"

# Copy default config if not present
SETTINGS_PATH="${DIST_DIR}/QuietPanelBridge.json"
if [ ! -f "${SETTINGS_PATH}" ]; then
    cat <<EOF > "${SETTINGS_PATH}"
{
  "bluetooth_device": "68:DF:DD:0C:C1:AE",
  "phone_ip": "192.168.44.1",
  "enabledPages": [0, 1, 2, 3, 4, 5, 6],
  "weather": {
    "enabled": true,
    "location": "Taipei",
    "latitude": 25.0330,
    "longitude": 121.5654
  }
}
EOF
fi

echo "=== Build Complete ==="
echo "Binary output: ${TARGET_STABLE}"
ls -lh "${TARGET_STABLE}"
