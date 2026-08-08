# QuietPanel iOS preview 0.3.0

This is the first armv7/iOS 9 integration build. It upgrades the verified
LegacyPad Display receiver without changing its package or bundle identifier,
so OpenDisplay keeps the same install identity and display arrangement.

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

Version 0.3.0 is a preview. The existing 0.2.1 and 0.2.2 packages remain the
rollback baseline.
