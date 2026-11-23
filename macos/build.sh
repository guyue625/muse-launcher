#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUTPUT_DIR="$SCRIPT_DIR/build"
APP="$OUTPUT_DIR/muse 多开器.app"
EXECUTABLE="$APP/Contents/MacOS/MuseLauncher"
ARCH=$(uname -m)

case "$ARCH" in
    arm64|x86_64) ;;
    *) echo "Unsupported Mac architecture: $ARCH" >&2; exit 1 ;;
esac

if ! command -v xcrun >/dev/null 2>&1; then
    echo "Install Xcode Command Line Tools first: xcode-select --install" >&2
    exit 1
fi

SDK=$(xcrun --sdk macosx --show-sdk-path)
SWIFTC=$(xcrun --find swiftc)
BUILD_CACHE="$OUTPUT_DIR/.module-cache"
mkdir -p "$BUILD_CACHE"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$SCRIPT_DIR/Info.plist" "$APP/Contents/Info.plist"
ICONSET="$OUTPUT_DIR/MuseIcon.iconset"
mkdir -p "$ICONSET" "$APP/Contents/Resources"
for SIZE in 16 32 128 256 512; do
    sips -z "$SIZE" "$SIZE" "$SCRIPT_DIR/assets/muse-mascot-icon.png" --out "$ICONSET/icon_${SIZE}x${SIZE}.png" >/dev/null
    DOUBLE_SIZE=$((SIZE * 2))
    sips -z "$DOUBLE_SIZE" "$DOUBLE_SIZE" "$SCRIPT_DIR/assets/muse-mascot-icon.png" --out "$ICONSET/icon_${SIZE}x${SIZE}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/MuseIcon.icns"

CLANG_MODULE_CACHE_PATH="$BUILD_CACHE" "$SWIFTC" \
    -parse-as-library \
    -swift-version 5 \
    -target "$ARCH-apple-macosx14.0" \
    -sdk "$SDK" \
    -framework AppKit \
    -framework WebKit \
    "$SCRIPT_DIR"/*.swift \
    -o "$EXECUTABLE"

rm -rf "$OUTPUT_DIR/Muse Launcher.app"
codesign --force --deep --sign - "$APP"
echo "Built: $APP"
