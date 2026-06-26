

### v1.3.0 (2026-06-26)

Deep sleep battery optimization with significant motion sensor:
- **Significant motion wake-up** -- When stationary + screen off for >5 minutes, GPS is turned **completely off** and the phone hardware significant motion sensor is armed instead. This sensor costs <0.01%/hour and fires when the device is physically moved (picked up, walked with, car door closes). On trigger, GPS snaps back to active 30s polling immediately.
- **Four-state polling model** -- ACTIVE (30s, moving/screen on) -> SETTLING (2min, stationary/screen on) -> IDLE (5min, stationary/screen off 2-5min) -> SLEEP (GPS off, motion sensor, stationary/screen off >5min).
- **No safety net needed** -- The distance threshold (500m) and max interval timer only fire when GPS fixes arrive. In SLEEP state, no fixes come in, so no transmissions happen. The motion sensor ensures GPS wakes when the user actually moves.
- **AdaptivePollingController** rewritten with PollingState enum and TriggerEventListener for significant motion.
- **GpsStreamingService** now dynamically starts/stops GPS collection based on the controller state changes.
- Expected battery: <0.01%/hour when asleep (was ~0.2%/hour with 5min polling).
# Changelog

### v1.2.0 (2026-06-25)

Battery optimization with adaptive GPS polling:
- **Fixed: text input cursor bug** — First character of IP address was pushed to end when typing in the host field. Classic Compose TextField recomposition bug: the DataStore Flow emit during editing reset cursor position. Fixed by using local mutable state for text fields and adding a stable `id` field to `ServerTarget` so the `remember` key doesn't change during edits.
- **Adaptive polling interval** — When stationary (all recent fixes within 5m), GPS polling gradually backs off from 30s → 2min → 5min. On movement, snaps back to 30s immediately. Reduces GPS radio duty cycle by 3-5x when stationary — the single biggest battery improvement available.
- **Screen-off throttle** — When the phone screen is off for >2 minutes, GPS polling slows to 5min intervals. Screen on → snaps back to active polling. Catches the common case of pocketing your phone while streaming.
- **AdaptivePollingController** — New component that combines movement detection and screen state to dynamically adjust the `LocationEngine` polling interval. The service restarts location collection whenever the interval changes.
- **ScreenStateReceiver** — New broadcast receiver for `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF`, registered dynamically during streaming only.
- **LocationEngine.updateInterval()** — New interface method allowing dynamic interval changes without recreating the engine.
- Both standard and fdroid flavors rebuilt with adaptive polling support.

### v1.1.0 (2026-06-25)

F-Droid compatibility with dual build flavors:
- Added `standard` flavor (with Google Play Services / FusedLocationProvider) and `fdroid` flavor (raw LocationManager, no Google dependencies)
- `LocationEngine` refactored from concrete class to interface with two implementations
- `FusedLocationEngine` (standard) — sensor fusion, better battery, faster indoor fixes
- `RawLocationEngine` (fdroid) — pure Android LocationManager, fully FLOSS
- Flavor-specific Hilt modules for dependency injection
- Both APKs signed with release key, R8 minified
- Standard: ~2 MB, F-Droid: ~1.5 MB

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

### v1.3.0 (2026-06-26)

Deep sleep battery optimization with significant motion sensor:
- **Significant motion wake-up** — When stationary + screen off for >5 minutes, GPS is turned **completely off** and the phone's hardware significant motion sensor is armed instead. This sensor costs <0.01%/hour and fires when the device is physically moved (picked up, walked with, car door closes). On trigger, GPS snaps back to active 30s polling immediately.
- **Four-state polling model** — ACTIVE (30s, moving/screen on) → SETTLING (2min, stationary/screen on) → IDLE (5min, stationary/screen off 2-5min) → SLEEP (GPS off, motion sensor, stationary/screen off >5min).
- **No safety net needed** — The distance threshold (500m) and max interval timer only fire when GPS fixes arrive. In SLEEP state, no fixes come in, so no transmissions happen. The motion sensor ensures GPS wakes when the user actually moves.
- **AdaptivePollingController** rewritten with `PollingState` enum and `TriggerEventListener` for significant motion.
- **GpsStreamingService** now dynamically starts/stops GPS collection based on the controller's state changes.
- Expected battery: <0.01%/hour when asleep (was ~0.2%/hour with 5min polling).

### v1.2.0 (2026-06-25)
