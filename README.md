# Reclaimed Player

A purpose-built Android media appliance, initially targeting the Google Pixel 6 (`oriole`).

## CLI setup

```sh
. scripts/android-env.sh
./gradlew assembleDebug
./gradlew installDebug
```

The initial proof of concept is both a normal launchable activity and an Android Home candidate. This lets us test the shell without removing the existing launcher.

## Device workflow

```sh
. scripts/android-env.sh
./gradlew installDebug
adb shell cmd package set-home-activity --user 0 dev.reclaimed.player/.MainActivity
```

Restore the standard GrapheneOS launcher with:

```sh
adb shell cmd package set-home-activity --user 0 com.android.launcher3/.uioverrides.QuickstepLauncher
```

## Current proof of concept

- Reads local audio through Android MediaStore.
- Groups the library by artist and album.
- Loads embedded album artwork through MediaStore album-art URIs.
- Creates ordered album queues in a Media3 `MediaSessionService`.
- Supports background playback and system/Bluetooth media controls.
- Serves as the device's default Home activity.
- Separates local and remote catalogs behind a source-first home screen.
- Offers persisted Classic and Touch interfaces over the same navigation and playback state.
- Provides a full-screen virtual Click Wheel with haptic menu navigation, transport controls,
  volume control on Now Playing, and a long-press Menu shortcut back to Touch mode.
- Authenticates to Jellyfin with Quick Connect and browses a selected music library.
- Opens Jellyfin from an app-private compressed metadata snapshot, refreshes stale snapshots
  in the background, and schedules a network-constrained sync every six hours.
- Streams Jellyfin albums with authenticated Media3 requests.
- Downloads Jellyfin albums into app-managed storage and prefers offline copies during playback.
- Downloads authenticated Jellyfin album artwork with each album and prefers the local cover offline.
- Provides a shared Downloads manager with offline album browsing, status, disk usage,
  playback, and removal controls in both Classic and Touch modes.
