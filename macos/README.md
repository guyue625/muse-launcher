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

The first page load shows a centered spinner and loading message instead of a blank page. A smaller toolbar spinner stays visible during subsequent navigation and refreshes without hiding the existing page. Loading indicators stop when loading finishes or fails; a connection that takes longer than 30 seconds shows a timeout message with retry guidance.

New accounts use `127.0.0.1:7897` by default (the local Clash proxy). Existing accounts without a proxy are migrated to this default once; custom proxy addresses are preserved. Clear the proxy field to use the macOS system proxy instead; that choice persists after restarting. Per-account HTTP, HTTPS CONNECT, and SOCKS5 proxies are supported, but proxy credentials are not supported yet. If the page cannot connect, check that Clash is running and that the saved port matches its listening port.

It does not yet implement avatar/tag editing, automated updates, or the Android-only shortcut and automation features. The app is ad-hoc signed for local use. If Gatekeeper blocks a locally built copy, Control-click the app, choose **Open**, then confirm. Do not disable Gatekeeper globally.

## Regression checks

```sh
sh macos/tests/run.sh
```

These checks cover proxy defaults, configuration migration and persistence, and proxy address validation without contacting the network or changing your account data.
