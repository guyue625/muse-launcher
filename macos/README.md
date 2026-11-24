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

The app runs as a menu-bar-only app without a Dock icon. Closing its window hides the app in the background; idle browser pages may be released as described below. Click the muse menu-bar icon to open the menu, then choose **打开 muse 多开器**, **重新加载网页**, or **退出 muse 多开器**.

To limit memory growth across accounts, switching accounts releases inactive pages once they are safe to unload, instead of keeping three full pages resident. Closing or minimizing the window for three minutes also makes the selected page eligible for release. Reopening restores its last URL using the same persistent account store; cookies and login sessions remain. Inactive pages with drafts, selected attachments, pending requests, streaming replies, active media/capture, embedded frames, or visible progress indicators are kept, and checked again every 30 seconds. Active downloads prevent automatic page release. Memory-pressure notifications also trigger checks of inactive pages. A protected page may therefore keep memory in use until its activity finishes or its draft is cleared.

Unplayed videos use metadata preloading instead of downloading the full video in advance, including videos added dynamically by the chat. Explicit `preload="none"`, autoplay media, and media already playing retain their behavior. The foreground page is never automatically refreshed to force its memory down: the site's JavaScript, chat history, and media still determine its active memory use, so this is not a fixed per-account memory limit.

New accounts use `127.0.0.1:7897` by default (the local Clash proxy). Existing accounts without a proxy are migrated to this default once; custom proxy addresses are preserved. Clear the proxy field to use the macOS system proxy instead; that choice persists after restarting. Per-account HTTP, HTTPS CONNECT, and SOCKS5 proxies are supported, but proxy credentials are not supported yet. If the page cannot connect, check that Clash is running and that the saved port matches its listening port.

It does not yet implement avatar/tag editing, automated updates, or the Android-only shortcut and automation features. The app is ad-hoc signed for local use. If Gatekeeper blocks a locally built copy, Control-click the app, choose **Open**, then confirm. Do not disable Gatekeeper globally.

## Regression checks

```sh
sh macos/tests/run.sh
```

These checks cover proxy defaults, configuration migration and persistence, proxy address validation, and resource management guards in a separate, windowless WebKit instance. The WebKit checks use an in-memory data store and local HTML with mocked requests; they do not contact the network or change your account data or interact with your open windows.
