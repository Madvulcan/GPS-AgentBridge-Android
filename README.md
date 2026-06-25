# GPS AgentBridge Android

**Distance-based GPS relay for the [gps-agent-bridge](https://github.com/Madvulcan/gps-agent-bridge) desktop project.** Transmits NMEA 0183 (GGA + GSA + RMC) via UDP only when you've moved beyond a configurable threshold — saving battery versus fixed-interval polling.

**Companion app to [gps-agent-bridge](https://github.com/Madvulcan/gps-agent-bridge)** — the desktop side receives the NMEA data via `gpsd`, powers location-aware agent scripts, reverse geocoding, place search, and history logging.

## How it works

```
┌─────────────────┐     UDP NMEA     ┌─────────────────┐     JSON      ┌──────────┐
│  Android App    │ ──────────────► │  Linux Desktop   │ ──────────► │  Agent    │
│  (this repo)    │   port 2948     │  (gpsd daemon)   │   port 2947  │          │
└─────────────────┘                  └─────────────────┘              └──────────┘
```

Existing phone apps (`gpsdRelay`, `NMEA Send Location`) transmit at fixed intervals regardless of movement — this app replaces them with a **distance-triggered model**:

- **Internal polling** every ~30 seconds via FusedLocationProviderClient (system-managed, battery-efficient)
- **Network transmission** only when:
  - distance from last transmission ≥ threshold (default 500 m), **OR**
  - max interval elapsed (default 10 min, as a safety net)
- **Accuracy gate** — fixes worse than `minAccuracy` (default 20 m) are rejected before any logic runs
- **First fix always transmits** — you get data immediately after starting
- **Multi-target** — send to multiple desktop gpsd instances for redundancy
- **Dry-run mode** — exercise the engine without actually transmitting (for testing)
- **Auto-start on boot** (optional)
- **Battery optimization onboarding** — walks the user through disabling battery optimization, which is the #1 cause of streaming interruptions

## Screenshots

<img src="docs/screenshot-main.png" width="300" alt="Main screen showing streaming status, coordinates, and target list">

The main screen shows the current GPS status (streaming, waiting for fix, idle), live coordinates and altitude, the big START/STOP button, a test send button, and the list of configured destination servers with their send status.

## Download

A pre-built debug APK is available in the root of this repo: [`gps-agent-bridge-v1.0.0-debug.apk`](gps-agent-bridge-v1.0.0-debug.apk) (~18 MB).

A signed release build with R8 minification is also available: [`gps-agent-bridge-v1.0.0-release.apk`](gps-agent-bridge-v1.0.0-release.apk) (~2 MB).

Install via ADB:
```bash
adb install gps-agent-bridge-v1.0.0-release.apk    # signed release (recommended)
adb install gps-agent-bridge-v1.0.0-debug.apk      # debug build (for development)
```

Or transfer the file to your phone and install from the file manager.

> ⚠️ This is a debug build signed with a debug key. For production use, build an APK with your own signing key.

## Build from source

### Prerequisites

- Android Studio Ladybug (2024.2) or newer
- JDK 17
- Android SDK with compileSdk 35 (Android 15), minSdk 26 (Android 8.0)
- Physical Android device running 8.0+ for testing (emulators don't have real GPS)

### First-time setup

The `gradle-wrapper.jar` is not included in the repo (binary files in git). Regenerate it:

```bash
gradle wrapper --gradle-version 8.10.2
```

Or open the project in Android Studio — it will offer to generate the wrapper on first sync.

### Build the debug APK

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (~18 MB)

### Run unit tests

```bash
./gradlew :app:testDebugUnitTest
```

22 tests covering NMEA sentence formatting, checksum correctness, and transmission engine logic (distance/interval triggers, accuracy gate, dry-run mode, multi-target tracking).

### Install on a device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## End-to-end test with the desktop

1. Make sure `gpsd` is running on the desktop and listening on UDP 2948:
   ```bash
   ss -ulnp | grep 2948
   ```

2. Find the desktop's IP (Tailscale or LAN):
   ```bash
   tailscale ip -4    # Tailscale (recommended — works over any network)
   hostname -I        # LAN
   ```

3. Open GPS AgentBridge on the phone.
4. Walk through onboarding (location permission → battery optimization).
5. Settings → Destination servers → add `<desktop-ip>:2948`.
6. Tap **START**.
7. Verify on the desktop:
   ```bash
   gpsloc --human    # should show your phone's coordinates
   ```

**Troubleshooting:**
- If the phone shows "Waiting for GPS fix" — go outside or near a window. Cold-start GPS lock takes 30-60s.
- Try **Test send** from the main screen — sends a dummy packet to verify the network path independently of GPS.
- If test send works but real streaming doesn't, check that **min accuracy** isn't set too tight for your environment (indoor GPS often has 30-100m accuracy; raise it to 200m).
- If nothing reaches the desktop — check firewall rules. The desktop needs UDP 2948 open from the phone's IP range.

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/com/madvulcan/gpsagentbridge/
│   ├── App.kt                          — @HiltAndroidApp entry, notification channel
│   ├── MainActivity.kt                 — single-activity host for Compose navigation
│   ├── data/                           — Settings, ServerTarget, DataStore-backed repo
│   ├── nmea/NmeaGenerator.kt           — pure Kotlin, GGA+GSA+RMC + checksum
│   ├── net/UdpSender.kt                — fire-and-forget UDP, multi-target parallel send
│   ├── location/
│   │   ├── LocationEngine.kt           — FusedLocationProviderClient → Flow<GpsFix>
│   │   ├── TransmissionEngine.kt       — distance/interval logic + state machine
│   │   └── StreamingStateHolder.kt     — process-wide bridge from service → UI
│   ├── service/GpsStreamingService.kt  — foreground service (type: location), wake lock
│   ├── boot/BootReceiver.kt            — auto-start on BOOT_COMPLETED
│   ├── di/AppModule.kt                 — Hilt singleton module
│   ├── ui/
│   │   ├── AppNavigation.kt            — NavHost routes (main, settings, onboarding, about)
│   │   ├── StreamingViewModel.kt       — shared state for main + onboarding
│   │   ├── theme/                      — Material 3 colors + typography
│   │   └── screens/
│   │       ├── MainScreen.kt           — status card + START/STOP + test send + targets
│   │       ├── SettingsScreen.kt       — target editor + sliders + toggles
│   │       ├── OnboardingScreen.kt     — location + battery opt-out flow
│   │       └── AboutScreen.kt          — version + project description
│   └── util/
│       ├── PermissionHelper.kt         — location + background location helpers
│       └── BatteryOptimizationHelper.kt — battery opt-out intent + check
└── res/                                — strings, colors, themes, launcher icon
```

## Design decisions

| Area | Choice | Why |
|---|---|---|
| UI framework | Jetpack Compose | ~40% less code than XML layouts, modern Android default |
| Location source | FusedLocationProviderClient | Fuses GPS + Wi-Fi + cell + accelerometer; significantly lower battery than raw LocationManager, especially when stationary |
| Persistence | Preferences DataStore | Official successor to SharedPreferences; safer concurrent-write semantics |
| NMEA sentences | GGA + GSA + RMC | Covers gpsd's needs; GSA gives DOP for free. GSV skipped — bulky and rarely changes |
| Talker ID | `GP` (GPS-only) | Maximum gpsd compatibility. `GN` also accepted but `GP` is the safer default |
| Per-transmission datagram | Single UDP packet (3 sentences joined with `\r\n`) | One packet per event — efficient and atomic |
| DI | Hilt | Standard Android DI, KSP-compiled, works with Compose ViewModels out of the box |
| Max interval default | 10 min | Desktop's `location-updater` reads every 30s; 10 min is sufficient for the agent's use cases while saving battery |
| State bridge | StreamingStateHolder singleton | Works because Android keeps foreground service and UI in the same process. Simple and correct for this use case |
| Distance calculation | Pure-Kotlin haversine | No Android dependency — makes the engine fully unit-testable without mocking |

## Battery expectations

| Scenario | Fixed-interval apps (60s) | This app (distance-based) |
|---|---|---|
| Stationary (desk, overnight) | ~2-5%/hour | <0.2%/hour |
| Light movement (walking) | ~2-5%/hour | ~0.5-1%/hour |
| Driving | ~2-5%/hour | ~1-2%/hour |

Based on requirements doc §4.3 estimates. Not yet measured on real hardware — contributions welcome.

## Settings reference

| Setting | Default | Description |
|---|---|---|
| Distance threshold | 500 m | How far you must move before a transmission fires |
| Max interval | 10 min | Transmit even if you haven't moved (safety net) |
| Min accuracy | 20 m | Reject fixes with worse accuracy (prevents garbage data) |
| Dry run | Off | Run the engine without actually sending UDP packets |
| Detailed notification | Off | Show coordinates in the persistent notification |
| Auto-start on boot | Off | Start streaming automatically when the phone reboots |
| Destination servers | (none) | One or more `host:port` pairs for UDP NMEA transmission |

## Known limitations

- **No GPS-only mode** — FusedLocationProviderClient requires Google Play Services. If you need LineageOS/GrapheneOS support, file an issue — switching to raw `LocationManager` is straightforward but loses battery efficiency.
- **No location history on the phone** — The desktop's `location-history.jsonl` is the source of truth; this app is purely a sensor.
- **Debug APK only** — No release signing configuration yet. Build your own release APK for production.
- **StreamingStateHolder is a singleton** — Works because foreground service and UI share a process. If the service were ever moved to a separate process (unlikely), this would need to become a proper service binder.

## Changelog

### v1.0.0 (2026-06-25)

First public release — fully functional distance-based GPS relay:
- Distance-based NMEA relay via UDP to one or more gpsd targets
- FusedLocationProviderClient with 30s internal polling
- Configurable distance threshold, max interval, and min accuracy
- Multi-target support with per-target success/failure tracking
- Material 3 Compose UI with status card, START/STOP, test send
- Onboarding flow (location permission + battery optimization)
- Foreground service with persistent notification
- Auto-start on boot (optional)
- Dry-run mode for testing without network transmission
- 22 unit tests passing (NMEA generator + transmission engine)
- End-to-end verified: phone → Tailscale VPN → desktop gpsd

## License

MIT — see [LICENSE](LICENSE). Matches the [desktop repo](https://github.com/Madvulcan/gps-agent-bridge).
