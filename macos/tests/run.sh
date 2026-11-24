#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE_DIR=$(dirname "$SCRIPT_DIR")
OUTPUT_DIR=$(mktemp -d)
trap 'rm -rf "$OUTPUT_DIR"' EXIT

xcrun swiftc -parse-as-library -swift-version 5 \
    -target "$(uname -m)-apple-macosx14.0" \
    -sdk "$(xcrun --sdk macosx --show-sdk-path)" \
    "$SOURCE_DIR/Accounts.swift" "$SCRIPT_DIR/AccountsTests.swift" \
    -o "$OUTPUT_DIR/AccountsTests"
"$OUTPUT_DIR/AccountsTests"

xcrun swiftc -parse-as-library -swift-version 5 \
    -target "$(uname -m)-apple-macosx14.0" \
    -sdk "$(xcrun --sdk macosx --show-sdk-path)" \
    -framework AppKit -framework WebKit \
    "$SOURCE_DIR/BrowserResources.swift" "$SCRIPT_DIR/BrowserResourcesTests.swift" \
    -o "$OUTPUT_DIR/BrowserResourcesTests"
"$OUTPUT_DIR/BrowserResourcesTests"
