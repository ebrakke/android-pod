# Reclaimed Player

A purpose-built Android media appliance, initially targeting the Google Pixel 6 (`oriole`).

<img src="docs/images/classic-now-playing.png"
     alt="Classic mode showing a restored offline Jellyfin track"
     width="360">

## Development setup

[`mise`](https://mise.jdx.dev/getting-started.html) is the only development tool that must be
installed outside the repository. On macOS, install it with `brew install mise`. The checked-in
`mise.toml` then provides Java 17, Android command-line tools, and `just`; Gradle itself is provided
by the checked-in wrapper.

For a new checkout, run:

```sh
mise trust
mise install
mise exec -- just setup
mise exec -- just doctor
```

`just setup` presents the Android license prompts and installs platform-tools, the Android 36 SDK,
and build-tools 36.0.0. Run it again if the required SDK packages change. After pulling changes to
`mise.toml`, run `mise install` again to install any newly declared tool versions.

### Daily commands

If mise is [activated in your shell](https://mise.jdx.dev/getting-started.html#activate-mise), run
recipes directly:

```sh
just check
just devices
just install
```

Shell activation is optional. Without it, run the same recipes through mise so the repository's
tool versions and environment are active for that command:

```sh
mise exec -- just check
mise exec -- just devices
mise exec -- just install
```

Run `just` or `mise exec -- just` to list all recipes.

| Recipe | Purpose |
| --- | --- |
| `setup` | Accept Android licenses and install required SDK packages. |
| `doctor` | Verify Java, Android SDK, build-tools, and ADB availability. |
| `build` | Compile the debug APK. |
| `lint` | Run Android lint for the debug build. |
| `test` | Run debug unit tests. |
| `check` | Run build, lint, and unit tests together. |
| `devices` | List connected Android devices. |
| `install` | Install a debug APK while preserving app data. |
| `emulator-setup` | Install the optional API 36 emulator image and create the project Pixel 6 AVD. |
| `emulator-start` | Start the project emulator in a visible window. |
| `emulator-start-headless` | Start the project emulator without a window for automated work. |
| `emulator-install` | Build and install only on the project emulator. |
| `emulator-smoke` | Install, launch, check for crashes, and capture UI artifacts. |
| `emulator-check` | Start headlessly and run build, lint, tests, and the emulator smoke check. |
| `emulator-stop` | Stop only the project emulator. |
| `tasks` | List every available Gradle task. |
| `clean` | Remove generated build output. |

Every recipe loads `scripts/android-env.sh`. It preserves mise's environment while retaining the
old Homebrew paths as a fallback for direct Gradle or ADB use.

## Emulator-first development

Routine development can use a repository-managed Pixel 6 AVD on Apple Silicon or x86-64 hosts.
The AVD runs the Google APIs Android 36 image and has a fixed name and emulator serial so automated
commands cannot accidentally install onto or stop a connected physical phone.

Provision it once. The emulator system image is a separate, relatively large SDK download:

```sh
mise exec -- just emulator-setup
```

For interactive UI work, start the emulator with a visible window. For agent-driven or other
unattended checks, start it headlessly:

```sh
mise exec -- just emulator-start
# or
mise exec -- just emulator-start-headless
```

The normal verification loop can be run with one command. It starts the headless AVD if necessary
and leaves it running for subsequent iterations:

```sh
mise exec -- just emulator-check
```

`emulator-smoke` builds and installs the debug APK, grants the emulator's local-audio permission,
cold-launches `MainActivity`, suppresses the emulator's one-time immersive-mode tutorial, verifies
that the app process and activity remain alive, and fails if Android's crash buffer contains an app
crash. It writes a screenshot and UI hierarchy under `app/build/reports/emulator-smoke/`. These
generated artifacts are intentionally ignored by Git.

The AVD's app data persists between starts, which makes it useful for queue, download, and
process-recreation tests. Stop it when finished with:

```sh
mise exec -- just emulator-stop
```

The emulator is the default target for Compose UI, navigation, permissions, controlled MediaStore
fixtures, Jellyfin integration against a reachable test server, and playback-state development.
It is not a substitute for the Pixel 6 acceptance pass. GrapheneOS behavior, default-Home use,
Bluetooth/headset controls, real audio focus, lock-screen and screen-off playback, Tailscale state,
reboots, and device storage/battery behavior still require the physical phone. Physical-device
commands remain separate under `just devices` and `just install`.

The initial proof of concept is both a normal launchable activity and an Android Home candidate. This lets us test the shell without removing the existing launcher.

## Device workflow

```sh
mise exec -- just install
mise exec -- sh -c \
  '. scripts/android-env.sh && adb shell cmd package set-home-activity --user 0 dev.reclaimed.player/.MainActivity'
```

Restore the standard GrapheneOS launcher with:

```sh
mise exec -- sh -c \
  '. scripts/android-env.sh && adb shell cmd package set-home-activity --user 0 com.android.launcher3/.uioverrides.QuickstepLauncher'
```

## Current proof of concept

- Reads local audio through Android MediaStore.
- Groups the library by artist and album.
- Loads embedded album artwork through MediaStore album-art URIs.
- Creates ordered album queues in a Media3 `MediaSessionService`.
- Persists and restores complete queues, playback position, shuffle/repeat state, and paused or
  playing intent across service and process recreation.
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
