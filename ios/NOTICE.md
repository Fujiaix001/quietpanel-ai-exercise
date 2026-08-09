# Source notice

The OpenDisplay-compatible receiver in `main.m` is derived from LegacyPad
Display 0.2.2 and OpenDisplay 1.16.0. Those components are licensed under
GPL-3.0; the corresponding license is included as `LICENSE`.

This preview keeps the verified transport framing, H.264 decoder and cursor
renderer, and adds the QuietPanel page container plus display visibility
control. Its package, bundle, executable, preferences and TCP port are separate
from LegacyPad Display.

The public photo-clock build bundles 19 fonts under the SIL Open Font License
1.1. Names, upstream sources and the full license text are installed with the
app as `Fonts/LICENSES-PhotoFonts.txt`. A local private build may also reuse the
owner's Storopia test subset; that ignored file must not enter a public release.
