package com.quietpanel.client;

import android.content.Context;
import android.graphics.Typeface;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class PhotoFontManager {
    static final int STYLE_STOROPIA = 12;
    static final int STYLE_FEN_YUAN = 13;
    static final int STYLE_STOROPIA_SYNTHETIC_BOLD = 14;
    static final int STYLE_IANSUI = 15;

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
            "Storopia（測試）",
            "LittleClock 粉圓體",
            "Storopia（合成粗體，測試）",
            "芫荽 Iansui"
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
            "fonts/Storopia-Subset.ttf",
            "fonts/font_huninn.ttf",
            "fonts/Storopia-Subset.ttf",
            "fonts/font_iansui.ttf"
    };

    private static final Map<Integer, Typeface> CACHE = new HashMap<Integer, Typeface>();
    private static Typeface storopiaDegreeTypeface;

    private PhotoFontManager() {
    }

    static String[] names() {
        if (BuildConfig.INCLUDE_STOROPIA) {
            return NAMES.clone();
        }
        String[] publicNames = Arrays.copyOf(NAMES, STYLE_STOROPIA + 2);
        publicNames[STYLE_STOROPIA] = NAMES[STYLE_FEN_YUAN];
        publicNames[STYLE_STOROPIA + 1] = NAMES[STYLE_IANSUI];
        return publicNames;
    }

    static int normalize(int style) {
        if ((style == STYLE_STOROPIA || style == STYLE_STOROPIA_SYNTHETIC_BOLD)
                && !BuildConfig.INCLUDE_STOROPIA) {
            return 0;
        }
        return style >= 0 && style < ASSET_PATHS.length ? style : 0;
    }

    static int optionIndex(int style) {
        int normalized = normalize(style);
        if (!BuildConfig.INCLUDE_STOROPIA) {
            if (normalized == STYLE_FEN_YUAN) return STYLE_STOROPIA;
            if (normalized == STYLE_IANSUI) return STYLE_STOROPIA + 1;
        }
        return normalized;
    }

    static int styleAtOptionIndex(int optionIndex) {
        if (!BuildConfig.INCLUDE_STOROPIA && optionIndex == STYLE_STOROPIA) {
            return STYLE_FEN_YUAN;
        }
        if (!BuildConfig.INCLUDE_STOROPIA && optionIndex == STYLE_STOROPIA + 1) {
            return STYLE_IANSUI;
        }
        return normalize(optionIndex);
    }

    static boolean isStoropiaStyle(int style) {
        int normalized = normalize(style);
        return normalized == STYLE_STOROPIA || normalized == STYLE_STOROPIA_SYNTHETIC_BOLD;
    }

    static boolean usesEnglishDate(int style) {
        int normalizedStyle = normalize(style);
        return normalizedStyle >= 7
                && normalizedStyle != STYLE_FEN_YUAN
                && normalizedStyle != STYLE_IANSUI;
    }

    static synchronized Typeface get(Context context, int style) {
        int normalized = normalize(style);
        Typeface cached = CACHE.get(normalized);
        if (cached != null) {
            return cached;
        }

        int sourceStyle = normalized == STYLE_STOROPIA_SYNTHETIC_BOLD
                ? STYLE_STOROPIA : normalized;
        Typeface fallback = fallback(sourceStyle);
        String assetPath = ASSET_PATHS[sourceStyle];
        Typeface result = fallback;
        if (assetPath == null) {
            result = fallback;
        } else {
            try {
                result = Typeface.createFromAsset(context.getAssets(), assetPath);
            } catch (RuntimeException ignored) {
                result = fallback;
            }
        }
        if (normalized == STYLE_STOROPIA_SYNTHETIC_BOLD) {
            result = Typeface.create(result, Typeface.BOLD);
        }
        CACHE.put(normalized, result);
        return result;
    }

    /**
     * Storopia itself has no degree glyph. Orbitron's compact geometric ring
     * is a closer visual companion than the platform sans-serif fallback.
     */
    static synchronized Typeface storopiaDegreeFallback(Context context, boolean syntheticBold) {
        if (storopiaDegreeTypeface == null) {
            try {
                storopiaDegreeTypeface = Typeface.createFromAsset(context.getAssets(),
                        "fonts/font_orbitron.ttf");
            } catch (RuntimeException ignored) {
                storopiaDegreeTypeface = Typeface.SANS_SERIF;
            }
        }
        return syntheticBold
                ? Typeface.create(storopiaDegreeTypeface, Typeface.BOLD)
                : storopiaDegreeTypeface;
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
