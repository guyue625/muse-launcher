# muse 多开器 for macOS

This is a separate native macOS app; it does not change the Android app.

## Build and run

Requirements: macOS 14 or later and Xcode Command Line Tools. No paid Apple Developer account is needed for personal use.

```sh
xcode-select --install
./macos/build.sh
open "macos/build/muse 多开器.app"
```

The app stores account names in `~/Library/Application Support/MuseLauncher/accounts.json`. Each account gets its own persistent WebKit website data store, so its muse.ai cookies and login session are separate. Removing an account also removes its WebKit data store. File uploads use the macOS file picker; downloads ask where to save each file. The Muse page requests the English (US) locale regardless of the Mac's display language; the app controls stay in Chinese.

The initial version inherits the macOS system proxy by default (including Clash's system proxy). An optional per-account HTTP, HTTPS CONNECT, or SOCKS5 proxy can be set in account settings; proxy credentials are not supported yet. It does not yet implement avatar/tag editing, automated updates, or the Android-only shortcut and automation features. The app is ad-hoc signed for local use. If Gatekeeper blocks a locally built copy, Control-click the app, choose **Open**, then confirm. Do not disable Gatekeeper globally.
