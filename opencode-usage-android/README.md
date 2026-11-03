# OC Usage — OpenCode Go limits on Android

A small Android app and home-screen widget that shows how much of your
**OpenCode Go** allowance you've used in each window:

- **5-hour** (rolling)
- **Weekly**
- **Monthly**

Each one shows the percent used, a bar that turns amber at 70% and red at 90%
or when the limit is hit, and when it resets.

## How it works

The app calls `GET https://opencode.ai/zen/go/v1/usage` with your Go API key
as a Bearer token. The response looks like:

```json
{"usage":{"rolling":{"status":"ok","percent":2,"resetsAt":"…Z"},"weekly":{…},"monthly":{…}}}
```

This endpoint isn't in OpenCode's public docs, so it could change. If numbers
ever look wrong, the console at <https://opencode.ai/console> is the
authoritative source.

The key is stored only in the app's private storage on your phone and is sent
only to `opencode.ai`.

## Install

Sideload `app-debug.apk`. You may need to allow "Install unknown apps" for your
browser or file manager. Requires Android 11+.

## Widget

Long-press the home screen → Widgets → **OC Usage**. The widget shows the last
numbers the app fetched and never contacts the server itself. Tap it to open
the app.

## Manual refresh only

The app never checks in the background. It contacts opencode.ai only when you
tap **Refresh**: not on launch, not when you save your key, and never from the
widget. Every screen shows when the numbers were fetched ("As of 14:05", or
with the date if older than today).

## Build

```sh
./gradlew assembleDebug   # needs JDK 17+ and the Android SDK (platform 35)
./gradlew testDebugUnitTest
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
