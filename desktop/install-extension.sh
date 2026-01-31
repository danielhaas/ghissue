#!/usr/bin/env bash
# Install (or update) the ghissue GNOME Shell extension.
set -euo pipefail

UUID="ghissue@ghissue.github.com"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="${SCRIPT_DIR}/gnome-extension"
DEST_DIR="${HOME}/.local/share/gnome-shell/extensions/${UUID}"

mkdir -p "$DEST_DIR"
cp "$SRC_DIR/metadata.json" "$DEST_DIR/"
cp "$SRC_DIR/extension.js"  "$DEST_DIR/"
cp "$SCRIPT_DIR/resources/ghissue-icon.svg" "$DEST_DIR/"

echo "Extension files installed to $DEST_DIR"

# Enable the extension (safe to run even if already enabled)
if command -v gnome-extensions &>/dev/null; then
    gnome-extensions enable "$UUID" 2>/dev/null && \
        echo "Extension enabled." || \
        echo "Run 'gnome-extensions enable $UUID' after logging back in."
else
    echo "gnome-extensions CLI not found; enable manually in Extensions app."
fi

echo ""
echo "On Wayland you must log out and back in for the extension to load."
echo "On X11 you can press Alt+F2, type 'r', and press Enter to reload."
