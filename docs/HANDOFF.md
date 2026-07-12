# Reclaimed Player: Project Handoff and Roadmap

## Product intent

Reclaimed Player turns an old Android phone into a purpose-built, distraction-light music
player. This is a personal project, not a product or general-purpose Android distribution.
The current target is a Pixel 6 running GrapheneOS.

The experience should combine the focus and directness of a classic iPod with useful modern
capabilities:

- Bluetooth audio and system media controls.
- Local music stored on the device.
- Jellyfin streaming over Tailscale.
- Explicit offline downloads.
- A full-device media-player shell rather than merely another app in a normal phone setup.

Music sources intentionally remain separate. On-device music, Jellyfin, and any future source
should preserve their identity instead of being merged into one ambiguous catalog.

## Repository and workspace

- Repository: `~/code/android-pod`
- Branch: `main`
- Initial baseline commit: `05e7d63 Initial Reclaimed Player proof of concept`
- The project uses Gradle from the command line; Android Studio is not required.

The repository uses `mise.toml` to provision Java 17, Android command-line tools, and `just`. After
installing mise, bootstrap a checkout and install the required Android SDK packages:

```sh
cd ~/code/android-pod
mise trust
mise install
mise exec -- just setup
mise exec -- just doctor
```

When mise is activated in the shell, use `just check`, `just devices`, and `just install` directly.
Otherwise prefix them with `mise exec --`. Each recipe sources the environment script, which
preserves mise-provided paths and retains the previous Homebrew paths as a fallback. Direct Gradle
or ADB commands remain supported after manually sourcing `scripts/android-env.sh` in a mise-managed
shell. The README's Development setup section is the canonical command reference and explains
first-time setup, shell activation, and every available recipe.

Routine development is emulator-first. `just emulator-setup` installs an optional Google APIs
Android 36 image and creates the repository's Pixel 6 AVD. `just emulator-start` launches it with a
window; `just emulator-start-headless` supports agent-driven checks. `just emulator-smoke` safely
targets only that named AVD at its fixed emulator serial, installs and launches the debug build,
checks for an app crash, and saves a screenshot plus UI hierarchy under the ignored
`app/build/reports/emulator-smoke/` directory. The script refuses to operate when the fixed serial
does not identify the project AVD, preventing an attached Pixel from becoming an accidental target.
`just emulator-check` starts the headless AVD when needed and runs build, lint, JVM tests, and the
smoke pass as one repeatable command. AVD app data persists across starts for state-restoration work.

Use the emulator for routine UI, navigation, permissions, controlled MediaStore fixtures, reachable
Jellyfin integration, and process-recreation work. It does not reproduce GrapheneOS or physical
hardware behavior. Default-Home behavior, Bluetooth/headset controls, audio focus, lock-screen and
screen-off playback, Tailscale transitions, reboot restoration, and real device storage/battery
behavior remain physical-Pixel acceptance requirements.

Physical-device APKs use the stable debug signing key at
`~/.config/reclaimed-player/reclaimed-player-debug.keystore`. The key is intentionally outside the
repository and must be backed up and copied between development computers. `just install` refuses
to install without it; `just signing-create` is only for creating the first key and must never be
used to replace a lost key after an APK has been installed.

Home Assistant integration deployment uses a separate Ed25519 key at
`~/.ssh/reclaimed_player_ha`. After its public key is authorized in HA's SSH app, `just ha-deploy`
stages and compiles the custom component, runs `ha core check` with automatic rollback, restarts
Core, and waits for the API to become healthy. The verified endpoint is `root@192.168.68.60:22`,
with its host key pinned in `~/.ssh/reclaimed_player_ha_lan_known_hosts`; defaults can be overridden
with the `HA_SSH_*` environment variables.

The app is registered as both a normal launcher activity and an Android Home candidate. It is
currently being used as the Pixel's default Home app. The README contains CLI commands for
selecting Reclaimed Player or restoring the GrapheneOS launcher.

## Current implementation

### Local music

- Reads Android `MediaStore` audio.
- Groups tracks into artists and albums.
- Reads album artwork.
- The test library includes the Darlahood music copied into the device's Music directory.

### Jellyfin

- Quick Connect authentication with a manual API-key fallback.
- Access tokens are encrypted with Android Keystore and are not stored in the repository.
- A selected Jellyfin Music library is browsable by artist, album, and track.
- Streams use authenticated Media3 requests.
- The configured device currently sees approximately 252 artists and 4,034 Jellyfin tracks.

### Metadata caching

- Jellyfin metadata uses an app-private, compressed binary snapshot.
- The UI loads the cached catalog before contacting the network.
- A foreground load refreshes metadata when it is older than 30 minutes.
- WorkManager schedules a network-constrained refresh every six hours.
- Failed refreshes retain the previous usable snapshot.

### Downloads

- Jellyfin albums can be downloaded into app-managed external storage.
- Playback prefers downloaded track files over streaming URLs.
- Authenticated album artwork is downloaded into the album directory as `cover.jpg` and is
  preferred by album, Downloads, notification, and Now Playing UI.
- A shared Downloads destination shows album count, track status, and actual disk usage.
- Touch mode provides per-album Play and Remove actions.
- Classic mode provides compact offline-album browsing; the album detail contains removal
  controls.
- Removing an album deletes its tracks, cover, DownloadManager records, and app records.

### Playback

- Playback lives in a Media3 `MediaSessionService`.
- Background playback, Android system controls, Bluetooth controls, and media notifications
  work.
- Albums create ordered queues and can begin at an individual track.
- Downloaded media and remote media share the same playback path.
- A versioned session snapshot restores the full queue, current index and position,
  deterministic shuffle order, repeat mode, and paused/playing intent after service or process
  recreation. Stable source identifiers are persisted; media and artwork URIs are resolved again
  at restore time, preferring Jellyfin downloads before authenticated streams.
- Now Playing shows authenticated/local artwork, elapsed time, duration, a live progress bar,
  and only the current play/pause state.

### Home Assistant and Sonos handoff

- Touch Now Playing offers **Continue on…** for active Jellyfin queues.
- Home Assistant connection settings and its long-lived token are stored in Android
  Keystore-backed encrypted app storage.
- `home-assistant/custom_components/reclaimed_player/` provides a manually installed custom
  integration that discovers native Sonos media-player entities and accepts stable Jellyfin track
  IDs, queue index, position, and playing intent.
- The integration rebuilds the Sonos queue through Home Assistant's native Sonos actions and
  proxies Jellyfin audio with authenticated range requests. Sonos receives opaque 24-hour stream
  URLs rather than the permanent Jellyfin credential.
- The Android player pauses only after Home Assistant reports that the handoff action succeeded.
- The current implementation is intentionally Jellyfin-only and one-way. Remote controls, reverse
  handoff, NFC/Qi dock triggers, Sonos grouping UI, durable proxy grants across HA restart, and
  shuffle-order transfer are not yet implemented.
- Installation and connection steps are documented in the README. Physical HA/Jellyfin/Sonos
  acceptance testing remains outstanding.
- On July 12, 2026, the current debug APK was installed and launched successfully on the physical
  GrapheneOS Pixel 6. Touch-mode Home Assistant settings were visually inspected, Reclaimed Player
  was restored as the default Home activity, and playback was left paused. Live Home Assistant,
  Jellyfin proxy, Sonos queue, seek, and position-handoff behavior were not exercised because HA
  was not configured yet.
- The device's historical APK used a lost debug signing key. With user approval, a one-time
  uninstall/reinstall established the stable external signing key described above. Two recorded
  downloaded-album directories (approximately 197 MB total) and their non-secret app records were
  restored; their in-app visibility still requires Jellyfin reconnection and verification.
- Home Assistant token QR scanning was then verified on the physical Pixel: the scanned credential
  authenticated successfully and discovered the configured Sonos players. The scanner is forced to
  portrait after the first device pass exposed the library's landscape default. The first live
  Continue on attempt returned an opaque HTTP 500. Integration version 0.1.1 now prefers HA's
  internal URL but falls back to its external URL when creating Sonos-reachable proxy links, and
  returns stage-specific handoff failures to the Android UI. The next live attempt exposed Sonos
  UPnP error 714 because the opaque audio URL had no file extension. Version 0.1.2 looks up each
  Jellyfin track's container and exposes matching `.mp3`, `.flac`, and other Sonos-compatible proxy
  paths and content types. The first lookup incorrectly requested the invalid Jellyfin field
  `MediaSources`, producing HTTP 400; version 0.1.3 removes that unnecessary query parameter and
  includes bounded Jellyfin error detail in future failures. An automated physical-device retry
  then confirmed that the configured credential is a user token, for which Jellyfin also rejects
  the server-level `/Items/{id}` route. Version 0.1.4 discovers `/Users/Me` and uses the user-scoped
  item route, while retaining an unscoped query fallback for server API keys. That allowed the
  complete queue to be built, exposing that the selected Den entity is a grouped member and cannot
  receive `sonos.play_queue` directly. Version 0.1.5 resolves the first `group_members` entity—the
  coordinator, per HA Sonos topology—and targets the group coordinator automatically. The final
  automated physical-device retry succeeded with the 12-track Sound & Color queue on Den: HA
  rebuilt the queue, selected track 1, sought to roughly 1:07, preserved paused intent, and the
  Android UI reported success before pausing its local session. Active-playing intent and other
  Sonos targets remain untested. A Kitchen test then revealed that HA Sonos treats direct-URL
  `enqueue=replace` as standalone playback rather than clearing and populating the Sonos queue; the
  old 57-track queue remained active. Version 0.2.1 explicitly clears the coordinator playlist,
  adds every proxy URL to the empty queue, and only then calls `sonos.play_queue`. The follow-up
  physical Kitchen test verified 12 tracks through Sonos UPnP, a Reclaimed Player `.mp3` proxy URI,
  and a seek from 1:11 to 1:15 while playing. HA 0.2.0 also adds coordinator-aware remote
  Play/Pause; the Continue on screen, Touch controls, persistent bottom player, and Classic wheel
  transport route to Sonos for the active in-memory handoff session. The test finished with both
  the Pixel and Kitchen Sonos paused.
- HA 0.3.0 adds coordinator-aware `volume_up`/`volume_down` commands. Touch Now Playing and Continue
  on expose explicit volume buttons; physical Android volume keys and Classic Now Playing wheel
  rotation route through the same active Sonos session. Kitchen was verified from volume 27 to 29
  and restored to 27 with the physical-volume-key path. The first command encountered a transient
  Sonos port-1400 timeout; its error was shown in the UI, a retry succeeded, and subsequent success
  now clears the stale error.
- Touch navigation now treats Continue on as a child of Now Playing. Opening Now Playing from the
  persistent bar while on Continue on no longer overwrites the remembered Music Sources return
  destination and traps navigation in a two-screen loop.

### Interface modes

Both interfaces operate on the same navigation and playback state.

**Classic mode**

- Full-screen, iPod-inspired display and virtual Click Wheel.
- Circular dragging moves selection with haptic detents.
- Current sensitivity is 20 detents per revolution (`WHEEL_STEP_DEGREES = 18f`).
- Selection remains active throughout a continuous circular gesture.
- Center selects, Menu goes back, and holding Menu switches to Touch mode.
- Previous, Next, and Play/Pause retain their transport meanings.
- On Now Playing during device playback, wheel rotation changes system media volume and temporarily
  replaces the track progress/timing area with an iPod-style volume meter; it does not open
  Android's volume panel. An active Sonos handoff continues to route wheel volume remotely.
- Wheel segment buttons suppress rectangular Compose ripples and the whole wheel is circularly
  clipped. The center button retains circular pressed feedback.
- Menu rows use a fixed compact height so one-item lists do not expand to fill the display.

**Touch mode**

- Conventional direct-touch browsing and source management.
- Richer download controls and connection settings.
- A persistent Classic button switches back without changing the current destination.

Mode preference survives process death. Classic mode hides system bars with transient edge-swipe
access; Touch mode restores them.

### Pixel 6 enclosure prototype

- `hardware/pixel6-ipod-case/` contains a parametric OpenSCAD model and ready-to-slice STL exports
  for a two-part iPod-style enclosure.
- The lower cradle leaves the camera bar, USB-C/speaker area, top microphone, and right-side buttons
  accessible. A removable snap-on faceplate exposes only the Classic display and Click Wheel areas.
- Both STL solids pass OpenSCAD's manifold check and are oriented for support-free FDM printing.
- `hardware/pixel6-ipod-case/render.sh` regenerates both STLs and the assembly preview headlessly
  with the pinned official OpenSCAD Docker image. GitHub Actions verifies that checked-in renders
  stay synchronized with the parametric source; automation must never invoke the macOS app binary.
- The model uses Google's nominal 158.6 x 74.8 x 8.9 mm Pixel 6 envelope. The second revision uses
  physical-fit feedback: a squarer 4.5 mm internal corner radius, a camera-bar relief based on a
  10.25 mm top offset and 22 mm bar height, and a 48 mm wheel opening placed 15 mm below the display
  window. Revised fit, touch, thermal, and drop testing remain outstanding.

## Important source files

- `MainActivity.kt`: application state, shared navigation, Touch UI, controller connection, and
  source coordination. It is currently large and should be split before many more features land.
- `ClassicPlayerShell.kt`: Classic UI, virtual wheel, and Classic menu mapping.
- `PlaybackService.kt`: Media3 player and media session ownership.
- `JellyfinClient.kt`: Jellyfin HTTP API and media URL construction.
- `JellyfinSettingsStore.kt`: encrypted connection settings.
- `JellyfinMetadataCache.kt`: atomic binary metadata snapshot.
- `JellyfinSyncWorker.kt`: durable periodic metadata refresh.
- `JellyfinDownloadStore.kt`: audio/artwork downloads, offline resolution, status, and removal.
- `LocalMusicRepository.kt`: MediaStore query path.

## Next milestone: daily-driver reliability

Do not add Spotify, YouTube Music, extensive theming, or hardware work yet. The concept is proven;
the next milestone should make the basic listening loop trustworthy.

### 1. Persist the queue and playback state — implemented, acceptance in progress

Persist and restore:

- The full queue and its source-specific media identifiers.
- Current queue index.
- Current playback position.
- Shuffle state and deterministic shuffle order.
- Repeat mode.
- Whether the restored session should remain paused or resume.

It should survive UI closure, playback-service destruction, Android process death, and reboot.
Restoration must resolve downloaded Jellyfin files first and fall back to authenticated streams.

Implementation and the downloaded-Jellyfin process-death pass are complete. On the Pixel, a
12-track downloaded album restored paused at index 2 and 6.355 seconds with the complete queue,
correct metadata, and local artwork. Remaining acceptance coverage: local MediaStore playback,
streamed-only Jellyfin with Tailscale connected, downloaded restoration with Tailscale stopped,
shuffle/repeat and actively-playing intent, and reboot restoration.

Reference: [Media3 player state](https://developer.android.com/media/media3/session/player) and
[background playback/resumption](https://developer.android.com/media/media3/session/background-playback).

### 2. Build an On-The-Go queue

Classic behavior:

- Holding Select on a song or album opens Play Next and Add to Queue actions.
- Add a compact Queue destination.
- Now Playing should show the current queue position, such as `3 of 13`.

Touch behavior:

- View the full queue.
- Reorder and remove entries.
- Clear the queue.
- Toggle shuffle, repeat one, and repeat all.

Queue edits must not interrupt the current track. Media3 already supports inserting, moving,
and removing playlist items safely:
[Media3 playlists](https://developer.android.com/media/media3/exoplayer/playlists).

### 3. Harden playback lifecycle behavior

Explicitly test and define:

- Pause when wired or Bluetooth headphones disconnect.
- Calls, alarms, navigation prompts, and competing audio focus.
- Bluetooth reconnection behavior.
- Headset buttons and Bluetooth metadata.
- Jellyfin streaming while the screen is off and Wi-Fi is allowed to sleep.
- Buffering and playback errors shown meaningfully in both interfaces.

The MediaSession foundation is correct and should remain the single player/session:
[MediaSession controls](https://developer.android.com/media/media3/session/control-playback).

### 4. Improve large-library navigation

- Add velocity-based wheel acceleration without losing precise slow movement.
- Show an alphabet overlay while rapidly scrolling artist lists.
- Use a distinct haptic transition when crossing letters.
- Remember the selected row when backing out of an artist or album.
- Add Touch-mode search scoped to the current source.

The current 252-artist Jellyfin catalog should be the performance and usability test case.

### 5. Make offline state authoritative

Run an airplane-mode acceptance pass and ensure:

- Downloaded tracks and artwork never require the network.
- Partial albums are visibly distinct from complete albums.
- Failed tracks can be retried individually.
- Missing or corrupted files are detected rather than trusting stale records.
- The UI shows free space and estimated download size before starting.
- A Wi-Fi-only download policy is available.

The platform DownloadManager implementation is adequate for the current scope. If requirements
grow to include pause/resume policies, a detailed download index, or multiple remote providers,
consider migrating to Media3's persistent `DownloadService` architecture:
[Media3 offline downloads](https://developer.android.com/media/media3/exoplayer/downloading-media).

### 6. Decide appliance behavior

- Screen timeout behavior while Now Playing is visible.
- Whether active playback should wake directly into Now Playing.
- Whether anything should auto-resume after reboot.
- Low-battery behavior.
- Screen burn-in protection for long-running Now Playing displays.
- Precise Home-button behavior from deep navigation.
- A clear, reliable escape hatch to GrapheneOS and system settings.

True enterprise lock-task/kiosk provisioning remains intentionally deferred. Default-Home plus
immersive Classic mode is sufficient for now.

### 7. Perform a focused architecture split

Before adding another music provider, split the current implementation into:

- Shared application/navigation state.
- Playback and queue controller.
- Source repositories.
- Download coordinator.
- Classic screens and controls.
- Touch screens.

Do not create an elaborate universal provider framework prematurely. Let local music and Jellyfin
define the first useful boundary; reconsider it only when a second remote provider is actually
being implemented.

### 8. Build iHome-style household handoff — initial Continue on slice implemented

- Verify a full ordered Jellyfin album can continue on each target Sonos player.
- Verify the selected queue index and playback position are preserved closely enough for a natural
  handoff, and that paused intent remains paused.
- Add remote Sonos Now Playing state and transport/volume controls in Touch mode.
- Persist handoff session identity in Home Assistant and implement reverse handoff to the Pixel.
- Add an NFC/Qi dock identity that maps each physical mount to a Sonos player or group.
- Debounce tag removal and use charging state as a corroborating dock signal.
- Keep provider identities explicit; Home Assistant is an output coordinator, not a merged catalog.

## Suggested first task for the next session

Finish the remaining playback-persistence acceptance matrix listed in milestone 1. Rebooting the
Pixel and stopping Tailscale are disruptive and should be confirmed with the user immediately
before testing. Fix any restoration gaps found, rerun `assembleDebug` and `lintDebug`, and then
begin the On-The-Go queue milestone.

## Device-testing checklist

Before the physical-device pass, run the repeatable local baseline:

- `just emulator-check` for compilation, lint, JVM tests, and install/launch/crash verification.
- Review its generated screenshot when UI changed.

The current project has no JVM or instrumented test sources yet, so the Gradle test task reports
`NO-SOURCE`. Add focused tests as state and source coordination are extracted from Android UI and
service code; do not treat the smoke launch as behavioral coverage.

For each meaningful playback change, test on the physical Pixel:

- Classic and Touch mode.
- Local and Jellyfin music.
- Streamed and downloaded Jellyfin tracks.
- Screen on and screen locked.
- Bluetooth controls where relevant.
- Tailscale connected and disconnected.
- Build plus `lintDebug` before handoff.
