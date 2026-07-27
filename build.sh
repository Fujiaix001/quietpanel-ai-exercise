#!/usr/bin/env bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Building QuietPanel Bridge (Rust release)..."
cd bridge
cargo test
cargo build --release
cd "$SCRIPT_DIR"

mkdir -p dist
cp bridge/target/release/QuietPanelBridge dist/QuietPanelBridge
chmod +x dist/QuietPanelBridge
chmod +x dist/Start-QuietPanel.sh 2>/dev/null || true
chmod +x dist/Install-Android.sh 2>/dev/null || true

echo "Build complete. Executable binary: dist/QuietPanelBridge"
