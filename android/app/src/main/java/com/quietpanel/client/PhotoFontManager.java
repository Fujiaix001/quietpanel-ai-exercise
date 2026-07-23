package com.quietpanel.client;

import android.content.Context;
import android.graphics.Typeface;

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

    private PhotoFontManager() {
    }

    static String[] names() {
        return NAMES.clone();
    }

    static int normalize(int style) {
        return style >= 0 && style < ASSET_PATHS.length ? style : STYLE_STOROPIA;
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
