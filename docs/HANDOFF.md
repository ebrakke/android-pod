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

Status: **functional one-way dock controller, Jellyfin-only**.

**User flow and behavior**

- Touch Now Playing offers **Continue on…** when the complete queue contains Jellyfin items and
  shuffle is off. The picker discovers native HA Sonos entities.
- A successful handoff clears the Sonos coordinator queue, appends the ordered album through
  opaque HA proxy URLs, starts the saved queue index, seeks to the phone position, and preserves
  playing or paused intent. The Pixel pauses only after HA reports success.
- The active handoff becomes an in-memory remote session. A focused coordinator polls Home
  Assistant once per second so Touch, Classic, and the persistent player show Sonos play state,
  title, artist, queue position, duration, and progress. Media selected externally on Sonos is
  shown without falsely claiming that it belongs to the phone queue.
- Play/Pause, Previous, Next, and seek route to Sonos. Previous restarts the current track when it
  is more than three seconds in and otherwise moves to the prior queue item. Volume routes from
  Touch buttons, physical Android volume keys, and Classic Now Playing wheel rotation.
- Touch Now Playing has direct Previous/Next buttons and a seek slider. Its content scrolls so the
  full remote-control and Continue on actions remain reachable on the Pixel 6 display.
- During an active handoff, Touch Now Playing names the connected Sonos room instead of showing
  **Continue on…** and offers **Disconnect**. Disconnect pauses Sonos before clearing remote mode,
  restores the paused local Media3 session and controls, and keeps remote mode active with an error
  if Home Assistant cannot confirm the pause. The Continue on detail screen offers the same action.
- Continue on is a child of Now Playing. Back navigation returns through Now Playing to the prior
  Music Sources destination instead of looping between the two playback screens.
- HA connection settings and the long-lived token are encrypted with Android Keystore. Token setup
  supports a portrait QR scanner; raw tokens are still accepted as a fallback.

**Architecture and important files**

- `app/src/main/java/dev/reclaimed/player/homeassistant/` owns encrypted HA settings, REST calls,
  QR parsing, Sonos models, and the Continue on Touch screen.
- `SonosSessionCoordinator.kt` owns the in-memory remote session, serialized commands, and polling;
  `MainActivity.kt` maps remote metadata back to the stable local queue and routes shared controls.
- `home-assistant/custom_components/reclaimed_player/` is custom integration version 0.4.0. It
  exposes `list_players`, `continue_on`, and coordinator-aware `control` response actions for play,
  pause, previous, next, seek, and volume.
- HA looks up Jellyfin container metadata with either `/Users/Me` user-token scope or an API-key
  fallback. Proxy paths carry the real audio extension and forward authentication and HTTP Range
  requests; grants are opaque and expire after 24 hours.
- Sonos group operations always resolve the first HA `group_members` entity as coordinator. Queue
  replacement must remain explicit `clear_playlist` plus `enqueue=add`; direct-URL
  `enqueue=replace` does not populate a Sonos queue.

**Development loop and live environment**

- `just ha-deploy` uploads the integration over pinned-key SSH to `root@192.168.68.60:22`, stages a
  rollback copy, runs `ha core check`, restarts Core, and waits for `http://192.168.68.60:8123/`.
- The deploy key is `~/.ssh/reclaimed_player_ha`; the pinned host-key file is
  `~/.ssh/reclaimed_player_ha_lan_known_hosts`. Override any target through `HA_SSH_*` variables.
- `just install` uses the stable Android signing key and targets exactly one connected physical
  device, ignoring running emulators. The current Pixel serial is `24131FDF600AER`.
- For live diagnosis, tail HA logs over SSH and inspect the phone with serial-qualified ADB. Sonos
  UPnP is also reachable on LAN port 1400; Kitchen is `192.168.68.62`.

**Verified July 12, 2026**

- QR authentication discovered the configured Sonos entities on the physical GrapheneOS Pixel 6.
- Den accepted a 12-track Sound & Color queue with track 1 selected and paused position near 1:07.
- Kitchen reported exactly 12 tracks, a Reclaimed Player `.mp3` proxy URI, and playback position
  advancing from 1:11 to 1:15. Remote Play/Pause left both Kitchen and the Pixel paused.
- Kitchen volume changed from 27 to 29 with Touch and returned to 27 through the Android hardware
  volume path. A transient port-1400 timeout was shown in the UI and succeeded on retry.
- The Touch navigation regression path was exercised end to end: Music Sources → Now Playing →
  Continue on → persistent Now Playing → Back returned to Music Sources.
- Integration 0.4.0 passed `ha core check`, restarted cleanly through `just ha-deploy`, and the
  stable-signing-key debug APK upgraded the physical GrapheneOS Pixel 6 without clearing app data.
- Office accepted the persisted 12-track Big Thief queue paused on **Little Arrow**. Touch Next
  moved to **Masterpiece** (`2 of 12`) and Previous returned to **Little Arrow** (`1 of 12`). The
  Touch slider sought to about 1:28, live progress advanced while playing, Previous restarted the
  track near zero, and Play/Pause left Office and the Pixel paused on track 1.
- Classic Previous/Next targeted the same Office session and moved between tracks 1 and 2 while
  paused. An externally active Office track also updated title, artist, duration, progress, and
  play state on the Pixel without being labeled as part of the phone queue.
- Bedroom accepted the paused 8-track queue on **Bulls**. Touch Now Playing showed **PLAYING ON
  BEDROOM**, **Playing on Bedroom**, and **Disconnect** instead of **Continue on…**. Sonos was
  started, Disconnect restored local Now Playing in its paused state, and a fresh HA discovery
  reported Bedroom paused. The Pixel and Bedroom were left paused.
- `mise exec -- just check` passed assemble, Android lint, and all six JVM tests. The HA Python
  helper test passed with `python3 -m unittest discover -s home-assistant/tests -v`.

**Known limitations and next work**

- The remote session is not persisted. An app or HA restart loses remote mode even though Sonos
  continues playing; reconnect/reverse-handoff recovery is still required.
- Queue editing still targets the paused local Media3 session. Disable it during remote mode or add
  an explicit Sonos queue contract before treating the phone as a complete remote queue editor.
- Polling relies on Home Assistant's Sonos entity metadata and reports transient network failures
  in the Continue on screen; disconnected/reconnected and grouped-coordinator changes need broader
  live acceptance coverage.
- Resume-at-position reverse handoff to the Pixel, shuffle-order transfer, Sonos grouping UI,
  durable proxy grants, NFC/Qi dock mapping, and Spotify/provider-specific handoff remain planned.
- Den, Kitchen, Office, and Bedroom have the coverage recorded above. Test the remaining targets,
  active-playing handoff from the outset, natural track transitions, expired grants, HA restart,
  grouping changes, and network loss.

### Interface modes

Classic, Hybrid, and Touch operate on the same navigation and playback state.

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
- Uses a fixed warm-neutral, ink, blue, and orange visual system defined in `TouchTheme.kt`; this is
  the product's Touch presentation rather than a user-selectable theming feature.
- The Touch home uses compact, source-specific cards and keeps the active queue visibly separate
  from On Device, Jellyfin, and Downloads. A dark persistent mini-player provides artwork,
  metadata, and a 48 dp play/pause target without obscuring the source list.
- Touch Now Playing uses a large artwork surface, live seek preview with elapsed/remaining time,
  accessible circular transport targets, and Sonos-specific status and volume treatment. The
  redundant mini-player is hidden on this full player screen and remains available elsewhere.
- A persistent, pill-shaped Case button switches to Hybrid without changing the current
  destination.

**Hybrid mode**

- Case-friendly direct touch uses the existing faceplate geometry: a compact upper viewport remains
  aligned with the display opening while the contextual Click Wheel stays aligned with the 48 mm
  circular opening. Hybrid is immersive like Classic and its preference survives process death.
- Upper-window rows support direct taps and vertical swipe detents in addition to wheel rotation.
  A persistent mini-player opens Now Playing from browsing screens.
- Hybrid Now Playing provides directly touchable seeking, compact metadata and artwork, remote
  Sonos status, Continue on, and disconnect actions. The wheel retains Menu/Back, Previous, Next,
  Play/Pause, center Select, circular scrolling, volume on Now Playing, and queue-action long press.
- Browsing, Queue, Jellyfin download/remove, and Sonos room/control actions reuse the existing
  Classic menu model. Connection failures link to full Touch; the Hybrid home also exposes explicit
  full Touch and Classic choices. Holding Menu moves Classic → Hybrid → Touch.
- `HybridPlayerComponents.kt` owns the aperture-specific viewport and Now Playing presentation;
  `ClassicPlayerShell.kt` continues to own the shared case menu and wheel interaction.

**Touch verification (Pixel 6 API 36 emulator, July 12, 2026)**

- `mise exec -- just emulator-check` passed: debug assemble, lint, JVM tests, install, cold launch,
  crash check, screenshot, and UI hierarchy capture.
- The Home, Now Playing, Queue, and On Device artist screens were inspected at the AVD's native
  1080 x 2400 resolution using the persisted one-track MediaStore fixture and playback session.
- Home/Touch mode persistence, the mini-player route to Now Playing, Now Playing back navigation,
  the Queue route, and local source browsing were exercised. Physical-Pixel checks remain pending,
  including font scaling, real album art, Jellyfin-heavy lists, and active Sonos state.

**Hybrid verification (Pixel 6 API 36 emulator, July 12, 2026)**

- `mise exec -- just emulator-check` passed assemble, lint, JVM tests, install, cold launch, crash
  check, screenshot, and UI hierarchy capture. A final `assembleDebug lintDebug testDebugUnitTest`
  pass and emulator smoke reinstall also passed after the UI was split into its focused file.
- Full Touch → Case opened Hybrid, a direct upper-row tap opened Now Playing, an upward viewport
  swipe moved selection from item 1 to item 6, and the full Touch escape returned successfully.
  Hybrid restored after a force-stop/relaunch with system bars hidden.
- The direct seek slider moved the paused 30-second fixture to 0:15. The emulator was left paused.
- The stable-key debug APK upgraded the physical GrapheneOS Pixel 6 without clearing data. Hybrid
  rendered the real 252-artist/4,034-track catalog, downloaded-album summary, 8-track queue, artwork,
  and compact Now Playing state. Automated upper-window taps and swipes worked, and Continue on
  discovered nine HA Sonos entities without starting a handoff. The Pixel was left paused on the
  Hybrid home screen.
- Hands-on validation with the printed faceplate remains required for aperture-edge taps, circular
  wheel gestures, accidental touches, one-handed reach, download/remove actions, direct seeking,
  and an actual Sonos handoff/disconnect cycle.

Mode preference survives process death. Classic and Hybrid hide system bars with transient
edge-swipe access; Touch restores them.

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

### 8. Build iHome-style household handoff — one-way dock controller implemented

- [x] Continue a full ordered Jellyfin album on Den, Kitchen, and Office through HA's native Sonos
  entities.
- [x] Preserve the selected queue index, playback position, and paused intent on those paths.
- [x] Route Play/Pause and incremental volume through the active Sonos group in Touch and Classic.
- [x] Poll authoritative Sonos state and show remote metadata, progress, and play state.
- [x] Route Previous, Next, and seek remotely in Touch; route shared transport controls in Classic.
- [ ] Show the authoritative Sonos volume level instead of only incremental volume controls.
- [ ] Define and implement what local queue editing does after handoff.
- [ ] Persist enough session identity to recover remote mode after Android process recreation.
- [ ] Verify remaining Sonos targets, initially-playing intent, transitions, network loss, grant
  expiry, and HA restart behavior.
- Persist handoff session identity in Home Assistant and implement reverse handoff to the Pixel.
- Add an NFC/Qi dock identity that maps each physical mount to a Sonos player or group.
- Debounce tag removal and use charging state as a corroborating dock signal.
- Keep provider identities explicit; Home Assistant is an output coordinator, not a merged catalog.

## Suggested first task for the next session

Recover the active Sonos session after Android process recreation without introducing a provider
framework. Persist only stable HA entity and queue identity, verify Sonos still owns the expected
Reclaimed Player queue before restoring remote mode, and provide an explicit escape when it does
not. Preserve the local/Jellyfin boundary and leave Sonos playback paused after device acceptance.

## Device-testing checklist

Before the physical-device pass, run the repeatable local baseline:

- `just emulator-check` for compilation, lint, JVM tests, and install/launch/crash verification.
- Review its generated screenshot when UI changed.

JVM tests cover Home Assistant QR token parsing and Sonos state/progress parsing; Python tests cover
the pure Jellyfin stream URL helper. Add focused tests as remote session recovery and source
coordination evolve; do not treat the smoke launch as behavioral coverage.

For each meaningful playback change, test on the physical Pixel:

- Classic and Touch mode.
- Local and Jellyfin music.
- Streamed and downloaded Jellyfin tracks.
- Screen on and screen locked.
- Bluetooth controls where relevant.
- Tailscale connected and disconnected.
- Build plus `lintDebug` before handoff.
