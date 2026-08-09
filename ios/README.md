# QuietPanel iOS 0.3.1

This is a standalone armv7/iOS 9 integration build. It uses its own package,
bundle, executable, preferences and TCP port, so the verified LegacyPad Display
app remains installed and unchanged.

- Cydia package and bundle: `tw.codex.quietpanel`
- App and executable: `QuietPanel`
- Receiver port: `9001` (`LegacyPad Display` continues to use `9000`)

- Page 1 is a live Mac dashboard: two-minute CPU/memory history, network
  throughput, memory/disk capacity, uptime, load, clock and connection status.
- Page 2 is the existing 1024×768 OpenDisplay receiver, used as a real extended
  Mac display.
- Page 3 is a local-photo clock. Its iPad settings can select multiple Photos
  albums, including Finder/iTunes-synced albums; tap a photo to advance. It also
  includes the Android edition's 13 redistributable open fonts for independent
  time, date and weather typography.
- Weather follows the Android edition's Open-Meteo payload and cache policy.
  The Mac fetches it hourly (ten-minute retry, hidden after six stale hours),
  then sends temperature, condition, optional location and daylight progress
  over USB so iOS 9 does not need a modern TLS connection.
- Page 4 is NASA Astronomy Picture of the Day. The Mac downloads, resizes and
  caches the image, then sends it over the existing USB connection.
- Swipe left or right to switch pages.
- The Mac controller chooses which pages appear and always keeps at least one
  enabled. All pages share the verified USB/OpenDisplay connection.
- While page 1 is visible, the receiver keeps the virtual display connected but
  skips H.264 decoding. The matching Mac sender also skips encoding and video
  traffic. Returning to page 2 requests a fresh keyframe.

Build with the verified iPhoneOS 9.3 SDK and Theos toolchain:

```sh
./tests/run.sh
THEOS=/absolute/path/to/theos make clean package FINALPACKAGE=1
```

LegacyPad Display remains an independent app and rollback baseline.
Weather data is provided by Open-Meteo under CC BY 4.0.

Version 0.3.1 rebuilds the iPad listener after USB loss and explicitly closes
and resumes it across app background/foreground transitions.
