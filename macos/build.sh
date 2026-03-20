#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

CONFIG="${1:-release}"
APP_NAME="GhIssue"
BUNDLE_DIR="$SCRIPT_DIR/build/${APP_NAME}.app"

echo "Building $APP_NAME ($CONFIG)..."
swift build -c "$CONFIG"

echo "Creating app bundle..."
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR/Contents/MacOS"
mkdir -p "$BUNDLE_DIR/Contents/Resources"

cp ".build/$CONFIG/$APP_NAME" "$BUNDLE_DIR/Contents/MacOS/$APP_NAME"
cp Info.plist "$BUNDLE_DIR/Contents/Info.plist"

echo "APPL????" > "$BUNDLE_DIR/Contents/PkgInfo"

echo "Built: $BUNDLE_DIR"
echo "Run with: open $BUNDLE_DIR"
