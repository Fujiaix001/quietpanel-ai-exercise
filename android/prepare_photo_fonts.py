"""Download and subset the third-page fonts.

Requires: python -m pip install fonttools
Storopia is never downloaded; pass a locally licensed source explicitly.
"""

import argparse
import tempfile
import urllib.request
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont


OPEN_FONTS = {
    "font_digital.ttf": "https://github.com/google/fonts/raw/main/ofl/dotgothic16/DotGothic16-Regular.ttf",
    "font_sans.ttf": "https://github.com/google/fonts/raw/main/ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf",
    "font_serif.ttf": "https://github.com/google/fonts/raw/main/ofl/notoserifjp/NotoSerifJP%5Bwght%5D.ttf",
    "font_rounded.ttf": "https://github.com/google/fonts/raw/main/ofl/zenmarugothic/ZenMaruGothic-Medium.ttf",
    "font_kai.ttf": "https://github.com/google/fonts/raw/main/ofl/kleeone/KleeOne-SemiBold.ttf",
    "font_heavy.ttf": "https://github.com/google/fonts/raw/main/ofl/delagothicone/DelaGothicOne-Regular.ttf",
    "font_orbitron.ttf": "https://github.com/google/fonts/raw/main/ofl/orbitron/Orbitron%5Bwght%5D.ttf",
    "font_audiowide.ttf": "https://github.com/google/fonts/raw/main/ofl/audiowide/Audiowide-Regular.ttf",
    "font_oxanium.ttf": "https://github.com/google/fonts/raw/main/ofl/oxanium/Oxanium%5Bwght%5D.ttf",
    "font_sairastencil.ttf": "https://github.com/google/fonts/raw/main/ofl/sairastencilone/SairaStencilOne-Regular.ttf",
    "font_zendots.ttf": "https://github.com/google/fonts/raw/main/ofl/zendots/ZenDots-Regular.ttf",
}
CJK_FONTS = {
    "font_digital.ttf", "font_sans.ttf", "font_serif.ttf",
    "font_rounded.ttf", "font_kai.ttf", "font_heavy.ttf",
}
LATIN_CHARS = "0123456789:./-_()[],+° ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
CJK_DATE_CHARS = "年月日時星期一二三四五六"
OUTPUT_DIR = Path(__file__).parent / "app" / "src" / "main" / "assets" / "fonts"
PRIVATE_OUTPUT_DIR = Path(__file__).parent / "app" / "src" / "private" / "assets" / "fonts"


def subset_font(source: Path, target: Path, characters: str) -> None:
    options = subset.Options()
    font = subset.load_font(str(source), options)
    sub = subset.Subsetter(options=options)
    sub.populate(text=characters)
    sub.subset(font)
    subset.save_font(font, str(target), options)

    cmap = TTFont(str(target)).getBestCmap() or {}
    missing = sorted(set(characters) - {chr(codepoint) for codepoint in cmap})
    if missing:
        target.unlink(missing_ok=True)
        raise ValueError(f"{source.name} lacks required glyphs: {''.join(missing)!r}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--storopia-source", type=Path,
                        help="optional local Storopia source; never downloaded")
    args = parser.parse_args()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    cache = Path(tempfile.gettempdir()) / "quietpanel_font_cache"
    cache.mkdir(parents=True, exist_ok=True)
    request_headers = {"User-Agent": "QuietPanel font subset builder"}
    for name, url in OPEN_FONTS.items():
        source = cache / name
        if not source.exists() or source.stat().st_size < 1_000:
            request = urllib.request.Request(url, headers=request_headers)
            with urllib.request.urlopen(request) as response, source.open("wb") as output:
                output.write(response.read())
        characters = LATIN_CHARS + (CJK_DATE_CHARS if name in CJK_FONTS else "")
        subset_font(source, OUTPUT_DIR / name, characters)
        print(f"{name}: {(OUTPUT_DIR / name).stat().st_size} bytes")

    if args.storopia_source:
        source = args.storopia_source.resolve(strict=True)
        PRIVATE_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        subset_font(source, PRIVATE_OUTPUT_DIR / "Storopia-Subset.ttf", LATIN_CHARS)
        print("Storopia-Subset.ttf generated for local testing; do not distribute.")
    elif not (PRIVATE_OUTPUT_DIR / "Storopia-Subset.ttf").exists():
        print("Storopia omitted; runtime will use the system fallback.")


if __name__ == "__main__":
    main()
