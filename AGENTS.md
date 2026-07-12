# Reclaimed Player Agent Guide

## Mission and product boundaries

Reclaimed Player turns an old Android phone into a focused, full-device music player. It is a
personal appliance project, currently targeting a Pixel 6 (`oriole`) running GrapheneOS. Preserve
the direct, distraction-light feel of a classic iPod while keeping useful Android capabilities
such as Bluetooth controls, local music, Jellyfin streaming, and explicit offline downloads.

Keep music sources visibly distinct. Do not merge local music, Jellyfin, or future providers into
an ambiguous universal catalog. Do not add Spotify, YouTube Music, extensive theming, enterprise
kiosk provisioning, or hardware work unless the user changes the roadmap.

Read `docs/HANDOFF.md` before substantial work. It contains the current implementation state,
roadmap, acceptance tests, and device-testing checklist. Update it when a session materially
changes the architecture, roadmap status, or handoff instructions.

## Build and device workflow

Use the repository's environment setup before Gradle or ADB commands:

```sh
. scripts/android-env.sh
./gradlew assembleDebug
./gradlew lintDebug
./gradlew installDebug
adb devices -l
```

Gradle is the supported command-line workflow; Android Studio is not required. Run at least
`assembleDebug` and `lintDebug` after meaningful code changes. Install and test on the physical
Pixel when playback, media sessions, downloads, permissions, Home behavior, system bars, or input
handling changes. An offline emulator is not a substitute for the device acceptance pass.

The app may be the Pixel's default Home activity. Do not clear app data, uninstall the app, reboot
the phone, stop Tailscale, remove downloads, change launcher selection, or alter device-wide
settings unless the task requires it and the user has approved the disruptive action. A normal
debug APK upgrade preserves data and is safe. Leave playback paused after automated checks.

## Code map and architecture

- `MainActivity.kt`: shared app/navigation state, Touch UI, controller connection, and source
  coordination. It is intentionally due for decomposition; avoid making it a broader catch-all.
- `ClassicPlayerShell.kt`: Classic screens, menu mapping, and Click Wheel behavior.
- `playback/PlaybackService.kt`: the single Media3 player and `MediaSession` owner.
- `playback/PersistedPlaybackSession.kt`: versioned durable queue/playback schema.
- `library/`: local MediaStore models and queries.
- `jellyfin/JellyfinClient.kt`: Jellyfin API and authenticated media URL construction.
- `jellyfin/JellyfinSettingsStore.kt`: encrypted connection settings.
- `jellyfin/JellyfinMetadataCache.kt` and `JellyfinSyncWorker.kt`: cached catalog and refresh work.
- `jellyfin/JellyfinDownloadStore.kt`: DownloadManager records, offline files, artwork, and removal.

Keep `PlaybackService` as the sole player/session owner. UI code should communicate through the
Media3 controller rather than create another player. Persist stable source identities and rebuild
local/download/stream URIs when restoring; never persist expiring or environment-specific media
URLs as canonical identity. Prefer downloaded Jellyfin audio and artwork before network resources.

Avoid an elaborate provider abstraction until a second remote provider is actually being built.
Let the existing local/Jellyfin boundary guide small, testable extractions.

## Implementation expectations

- Preserve both Classic and Touch behavior; they share navigation and playback state.
- Preserve ordered queues and the selected start track when changing playback code.
- Keep background playback, notification metadata, Bluetooth controls, and locked-screen behavior
  working through Media3.
- Treat offline state as authoritative: verify files exist instead of trusting stale records.
- Keep persisted formats versioned and tolerant of invalid or older data.
- Do network and storage work off the main thread.
- Match existing Kotlin and Compose style. Prefer focused files and small domain types over adding
  more unrelated responsibilities to `MainActivity`.
- Do not commit credentials, tokens, device dumps, generated build output, or user library data.
  Jellyfin access tokens belong in Android Keystore-backed app storage only.

## Verification and handoff

For playback changes, exercise the relevant matrix from `docs/HANDOFF.md`: Classic and Touch,
local and Jellyfin, streamed and downloaded, screen on and locked, and connected/disconnected
network behavior where applicable. Record what was actually tested and call out untested device
conditions rather than claiming the whole matrix passed.

Before committing, inspect `git status`, preserve unrelated user changes, run `git diff --check`,
and review the final diff. Use a focused conventional commit message. When work is complete and a
commit is requested or appropriate for the handoff, push the resulting branch to `origin`; do not
leave a completed commit only on the local machine unless the user explicitly asks you not to push.

Keep README screenshots intentional and current. Store curated images under `docs/images/`; do not
commit incidental ADB captures or UI hierarchy dumps.

For OpenSCAD work, use `hardware/pixel6-ipod-case/render.sh`. Never invoke the host
`openscad` binary or `/Applications/OpenSCAD.app` from automation; the macOS Qt build crashes in
the agent execution environment and shows the user a crash dialog. The renderer intentionally uses
the pinned official Docker image and is also checked in GitHub Actions.
