# Touch Blocker

Touch Blocker is an Android app for placing screen overlays over selected areas. Touches inside an overlay are consumed, which can help prevent accidental input on a damaged or difficult-to-use part of a display.

## Features

- Start and stop a foreground service that displays touch-blocking overlays.
- Draw and save a rectangular blocking zone in the zone selector.
- Store zones and settings with Android DataStore.
- Toggle protection from a Quick Settings tile.
- Optionally restore protection after a device reboot when it was active before shutdown.
- Show a heuristic alert when rapid, clustered touches are observed in the app.

## Requirements

- Android Studio with Android SDK Platform 34.
- JDK 17.
- Android device or emulator running Android 8.0 (API 26) or newer.
- User-granted **Display over other apps** permission to show blocking overlays.

The app targets Android 14 (API 34). Android 13 and newer may also ask for notification permission so the foreground service notification can be shown.

## Build

Open this folder in Android Studio and allow Gradle to sync, then run the `app` configuration on a device or emulator.

For a command-line debug build, use an installed Gradle version compatible with the Android Gradle Plugin configured by the project:

```sh
gradle :app:assembleDebug
```

The project currently contains Gradle wrapper properties but no `gradlew` launcher scripts, so the command above requires Gradle to be installed. The debug APK is normally written to `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Install and open the app.
2. Grant **Display over other apps** permission when prompted.
3. Open the zone selector to position and save a blocking region.
4. Turn on the blocker service. The service displays a persistent notification with a stop action.
5. Optionally add the Touch Blocker tile to Quick Settings or enable start-on-boot in the app.

When protection starts and there are no saved enabled zones, the service currently creates a full-screen blocking overlay. Stop the service from the app or its notification to remove the overlays.

## How touch detection works

The detector observes `MotionEvent`s delivered to the app's own activity. It looks for more than ten touch-down events in a one-second window within a spatial cluster and checks for a smooth move sequence before reporting a possible ghost-touch spike.

This is a heuristic, not a hardware diagnostic. It cannot monitor touches in other apps or system-wide, and rapid intentional taps may trigger an alert. The overlay blocker itself works independently of this detector.

## Project structure

```text
app/src/main/java/com/screentouch/touchblocker/
├── MainActivity.kt                 # Main Compose UI and activity touch events
├── GhostTouchBlockerService.kt     # Foreground service and overlay lifecycle
├── data/                           # Zone repository and user preferences
├── detector/                       # Touch spike heuristic
├── overlay/                        # View that consumes touches in a zone
├── receiver/                       # Boot restoration receiver
├── service/                        # Quick Settings tile
├── ui/                             # Zone selector and alert UI
└── util/                           # Overlay permission helpers
```

## Android permissions and services

The manifest declares overlay access, foreground-service permissions (including the Android 14 `specialUse` type), boot-completed reception, and notification permission. Overlay access is granted by the user in system settings rather than through a standard runtime permission dialog.

## Current project configuration

- `minSdk`: 26
- `targetSdk` / `compileSdk`: 34
- Kotlin: 1.9.24
- Android Gradle Plugin: 8.4.2
- JVM target: 17
