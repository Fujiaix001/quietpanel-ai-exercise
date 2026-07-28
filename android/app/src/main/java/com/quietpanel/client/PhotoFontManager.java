package com.quietpanel.client;

import android.content.Context;
import android.graphics.Typeface;

import java.util.Arrays;

final class PhotoFontManager {
    static final int STYLE_STOROPIA = 12;

    private static final String[] NAMES = {
            "系統粗體",
            "DotGothic16",
            "Noto Sans JP",
            "Noto Serif JP",
            "Zen Maru Gothic",
            "Klee One",
            "Dela Gothic One",
            "Orbitron",
            "Audiowide",
            "Oxanium",
            "Saira Stencil One",
            "Zen Dots",
            "Storopia（測試）"
    };

    private static final String[] ASSET_PATHS = {
            null,
            "fonts/font_digital.ttf",
            "fonts/font_sans.ttf",
            "fonts/font_serif.ttf",
            "fonts/font_rounded.ttf",
            "fonts/font_kai.ttf",
            "fonts/font_heavy.ttf",
            "fonts/font_orbitron.ttf",
            "fonts/font_audiowide.ttf",
            "fonts/font_oxanium.ttf",
            "fonts/font_sairastencil.ttf",
            "fonts/font_zendots.ttf",
            "fonts/Storopia-Subset.ttf"
    };

    private static int cachedStyle = -1;
    private static Typeface cachedTypeface;
    private static Typeface storopiaDegreeTypeface;

    private PhotoFontManager() {
    }

    static String[] names() {
        return BuildConfig.INCLUDE_STOROPIA ? NAMES.clone()
                : Arrays.copyOf(NAMES, STYLE_STOROPIA);
    }

    static int normalize(int style) {
        int styleCount = BuildConfig.INCLUDE_STOROPIA ? ASSET_PATHS.length : STYLE_STOROPIA;
        return style >= 0 && style < styleCount ? style : 0;
    }

    static boolean usesEnglishDate(int style) {
        return normalize(style) >= 7;
    }

    static synchronized Typeface get(Context context, int style) {
        int normalized = normalize(style);
        if (cachedStyle == normalized && cachedTypeface != null) {
            return cachedTypeface;
        }

        Typeface fallback = fallback(normalized);
        String assetPath = ASSET_PATHS[normalized];
        if (assetPath == null) {
            cachedTypeface = fallback;
        } else {
            try {
                cachedTypeface = Typeface.createFromAsset(context.getAssets(), assetPath);
            } catch (RuntimeException ignored) {
                cachedTypeface = fallback;
            }
        }
        cachedStyle = normalized;
        return cachedTypeface;
    }

    /**
     * Storopia itself has no degree glyph. Orbitron's compact geometric ring
     * is a closer visual companion than the platform sans-serif fallback.
     */
    static synchronized Typeface storopiaDegreeFallback(Context context) {
        if (storopiaDegreeTypeface != null) {
            return storopiaDegreeTypeface;
        }
        try {
            storopiaDegreeTypeface = Typeface.createFromAsset(context.getAssets(),
                    "fonts/font_orbitron.ttf");
        } catch (RuntimeException ignored) {
            storopiaDegreeTypeface = Typeface.SANS_SERIF;
        }
        return storopiaDegreeTypeface;
    }

    private static Typeface fallback(int style) {
        switch (style) {
            case 3:
            case 5:
                return Typeface.SERIF;
            case 0:
                return Typeface.DEFAULT_BOLD;
            default:
                return Typeface.SANS_SERIF;
        }
    }
}
