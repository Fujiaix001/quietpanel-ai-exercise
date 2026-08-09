# QuietPanel iOS 0.6.0

This is a standalone armv7/iOS 9 integration build. It uses its own package,
bundle, executable, preferences and TCP port, so the verified LegacyPad Display
app remains installed and unchanged.

The current device-tested source is build 21. For the complete Traditional
Chinese development summary—including hardware conditions, the paired Mac app,
architecture decisions, data-partition deployment, features, permissions,
performance work, licensing and rollback—see
[`docs/QuietPanel-iOS-armv7-Development-Summary.md`](../docs/QuietPanel-iOS-armv7-Development-Summary.md).

- Cydia package and bundle: `tw.codex.quietpanel`
- App and executable: `QuietPanel`
- Receiver port: `9001` (`LegacyPad Display` continues to use `9000`)

- Page 1 is a live Mac dashboard: two-minute CPU/memory history, network
  throughput, memory/disk capacity, uptime, load, clock and connection status.
- Page 2 is the existing 1024×768 OpenDisplay receiver, used as a real extended
  Mac display. A single tap maps through the aspect-fit video area to one Mac
  left click; QuietPanel Display needs macOS Accessibility permission.
- Page 3 is a local-photo clock. Its iPad settings can select multiple Photos
  albums, including Finder/iTunes-synced albums. Tap the screen to reveal the
  settings button for five seconds; hold the clock still for about 0.35 seconds
  before dragging it with one finger, or pinch to resize and move it. A direct
  horizontal gesture changes pages even over an enlarged clock; clock dragging
  never starts without the deliberate hold. Up to 20% of an enlarged clock may move beyond an edge while remaining
  recoverable. Position, size and the optional translucent panel are stored on
  the iPad. It includes 19 redistributable open fonts plus an optional local,
  non-distributable Storopia subset for independent time, date and weather type.
- Weather follows the Android edition's Open-Meteo payload and cache policy.
  The Mac fetches it hourly (ten-minute retry, hidden after six stale hours),
  then sends temperature, condition, optional location and daylight progress
  over USB so iOS 9 does not need a modern TLS connection.
- Page 4 reuses the photo clock as a guarded work page. Background touches and
  clock editing are disabled; only YouTube, full-screen capture and paste send
  native commands to the connected Mac.
- Page 5 is NASA Astronomy Picture of the Day. The Mac downloads, resizes and
  caches the image, then sends it over the existing USB connection.
- Swipe left or right to switch pages.
- The Mac controller chooses which pages appear and always keeps at least one
  enabled. All pages share the verified USB/OpenDisplay connection.
- Build 21 renders clock, date, weather and daylight text directly at their
  final pixel-aligned size instead of enlarging a smaller label backing store.
  This improves clarity on the iPad mini 1's 1× display without changing the
  selected fonts, layout, gestures or shadow settings.
- While the dashboard or data pages are visible, the receiver keeps the virtual display connected but
  skips H.264 decoding. The matching Mac sender also skips encoding and video
  traffic. Returning to page 2 requests a fresh keyframe.

Build with the verified iPhoneOS 9.3 SDK and Theos toolchain:

```sh
./tests/run.sh
THEOS=/absolute/path/to/theos make clean package FINALPACKAGE=1
```

LegacyPad Display remains an independent app and rollback baseline.
Weather data is provided by Open-Meteo under CC BY 4.0.

The Theos package is useful for development and Cydia testing. The verified
daily-use installation keeps the app bundle in an iOS data-partition app
container so the small jailbroken system partition is not consumed by the app
and its fonts. Do not hard-code a container UUID; preserve the previous bundle
before an atomic replacement.

Version 0.6.0 keeps those features while reducing steady-state work: each page
only runs the timers it renders, the photo clock updates on minute boundaries,
photo requests match the actual panel resolution, and H.264 frames go directly
into one CoreMedia decode buffer instead of several temporary copies. The iPad
also reports its exact page so the Mac can suspend unused metrics and cursor work.
