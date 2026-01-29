# Latch

**Latch** is a car-first Android Auto radio app for **your own** Icecast / Liquidsoap streams.

No browsing. No algorithm. No subscription.  
Pick a station, it latches on and plays.

## What it does

- Shows your stations in Android Auto as a simple list
- Tap a station to play
- Tap the same station again to stop
- Discovers stations from an Icecast `status-json.xsl` endpoint
- Caches the discovered station list so it still works if discovery fails

## What it intentionally does NOT do

- No album art
- No seeking / skipping
- No “recommendations”
- No account, no tracking

## Requirements

- Android phone with Android Auto
- Your phone must be able to reach your streams (LAN, VPN, Tailscale, etc.)
- An Icecast server (direct Icecast or via Liquidsoap -> Icecast)
- HTTPS is recommended

## Configuration

Currently, Latch is configured by editing constants in:

`app/src/main/java/.../RadioMediaService.kt`

Look for:

- `DIRECTORY_URL` (Icecast status JSON)
- `STREAM_BASE` (base URL where the mount points live)

Example:

- `DIRECTORY_URL = https://example.yourdomain/radio/status-json.xsl`
- `STREAM_BASE   = https://example.yourdomain/radio`

## Development

Open the project in Android Studio and run it on a device with USB debugging enabled.

Android Auto integration is handled by the MediaBrowserService + MediaSession.
The phone UI is just a helper screen.

## Roadmap (probable)

- Move config into a simple Settings screen (no code edits required)
- Favorites / pinning
- Optional grouping (e.g., “Mellow”, “Heavy”) without needing server-side categories
- Consider migration from MediaPlayer to ExoPlayer for maximum stream robustness

## License

MIT (see LICENSE).
