# QuietPanel iOS preview 0.1.1

This is a standalone armv7/iOS 9 integration build. It uses its own package,
bundle, executable, preferences and TCP port, so the verified LegacyPad Display
app remains installed and unchanged.

- Cydia package and bundle: `tw.codex.quietpanel`
- App and executable: `QuietPanel`
- Receiver port: `9001` (`LegacyPad Display` continues to use `9000`)

- Page 1 is a live Mac dashboard: CPU, memory, network throughput, system-disk
  usage, clock and connection status.
- Page 2 is the existing 1024×768 OpenDisplay receiver, used as a real extended
  Mac display.
- Swipe left or right to switch pages.
- Both pages share the verified USB/OpenDisplay connection. The Mac sends
  `quietState` metrics once per second.
- While page 1 is visible, the receiver keeps the virtual display connected but
  skips H.264 decoding. The matching Mac sender also skips encoding and video
  traffic. Returning to page 2 requests a fresh keyframe.

Build with the verified iPhoneOS 9.3 SDK and Theos toolchain:

```sh
./tests/run.sh
THEOS=/absolute/path/to/theos make clean package FINALPACKAGE=1
```

Version 0.1.1 fixes page visibility updates while the receiver is connected.
LegacyPad Display remains an independent app and rollback baseline.
