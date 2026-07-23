package com.quietpanel.client;

import android.graphics.Bitmap;
import android.graphics.Color;

final class PhotoEffects {
    static final class Analysis {
        final int dominantColor;
        final float focusX;
        final float focusY;

        Analysis(int dominantColor, float focusX, float focusY) {
            this.dominantColor = dominantColor;
            this.focusX = focusX;
            this.focusY = focusY;
        }
    }

    private PhotoEffects() {
    }

    static Analysis analyze(Bitmap bitmap, boolean adaptiveColor, boolean smartFocus) {
        if (bitmap == null || (!adaptiveColor && !smartFocus)) {
            return new Analysis(Color.BLACK, 0.5f, 0.5f);
        }

        Bitmap sample = null;
        try {
            sample = Bitmap.createScaledBitmap(bitmap, 20, 20, false);
            int[] pixels = new int[400];
            sample.getPixels(pixels, 0, 20, 0, 0, 20, 20);

            int color = adaptiveColor ? dominantColor(pixels) : Color.BLACK;
            float[] focus = smartFocus ? focusPoint(pixels) : new float[] { 0.5f, 0.5f };
            return new Analysis(color, focus[0], focus[1]);
        } catch (RuntimeException ignored) {
            return new Analysis(Color.BLACK, 0.5f, 0.5f);
        } finally {
            if (sample != null && sample != bitmap && !sample.isRecycled()) {
                sample.recycle();
            }
        }
    }

    private static int dominantColor(int[] pixels) {
        long red = 0;
        long green = 0;
        long blue = 0;
        for (int pixel : pixels) {
            red += Color.red(pixel);
            green += Color.green(pixel);
            blue += Color.blue(pixel);
        }
        return Color.rgb((int) (red / pixels.length),
                (int) (green / pixels.length), (int) (blue / pixels.length));
    }

    private static float[] focusPoint(int[] pixels) {
        long weightedX = 0;
        long weightedY = 0;
        long totalWeight = 0;
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                int pixel = pixels[y * 20 + x];
                int brightness = (Color.red(pixel) * 299
                        + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000;
                int weight = Math.abs(brightness - 128);
                weightedX += (long) x * weight;
                weightedY += (long) y * weight;
                totalWeight += weight;
            }
        }
        if (totalWeight == 0) {
            return new float[] { 0.5f, 0.5f };
        }
        return new float[] {
                clamp((float) weightedX / totalWeight / 19.0f),
                clamp((float) weightedY / totalWeight / 19.0f)
        };
    }

    private static float clamp(float value) {
        return Math.max(0.2f, Math.min(0.8f, value));
    }
}
