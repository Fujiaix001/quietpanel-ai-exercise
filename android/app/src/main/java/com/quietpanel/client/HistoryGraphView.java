package com.quietpanel.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public final class HistoryGraphView extends View {
    private static final int SAMPLE_CAPACITY = 300;

    private final float[] samples = new float[SAMPLE_CAPACITY];
    private final Paint gridPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final boolean percentScale;
    private int nextSample;
    private int sampleCount;

    public HistoryGraphView(Context context, int lineColor, boolean percentScale) {
        super(context);
        this.percentScale = percentScale;

        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;

        gridPaint.setColor(Color.argb(70, 143, 152, 163));
        gridPaint.setStrokeWidth(Math.max(1.0f, density));

        linePaint.setAntiAlias(true);
        linePaint.setColor(lineColor);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.0f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setColor(Color.rgb(143, 152, 163));
        textPaint.setTextSize(9.0f * scaledDensity);
    }

    public void addSample(double value) {
        samples[nextSample] = (float) Math.max(0.0, value);
        nextSample = (nextSample + 1) % SAMPLE_CAPACITY;
        if (sampleCount < SAMPLE_CAPACITY) {
            sampleCount++;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float left = 2.0f * density;
        float right = width - 2.0f * density;
        float top = 13.0f * density;
        float bottom = height - 3.0f * density;
        float graphHeight = Math.max(1.0f, bottom - top);

        for (int i = 0; i <= 2; i++) {
            float y = top + graphHeight * i / 2.0f;
            canvas.drawLine(left, y, right, y, gridPaint);
        }

        float maximum = percentScale ? 100.0f : dynamicMaximum();
        canvas.drawText("5 MIN", left, 10.0f * density, textPaint);
        String scale = percentScale ? "100%" : formatScale(maximum);
        canvas.drawText(scale, right - textPaint.measureText(scale), 10.0f * density, textPaint);

        if (sampleCount < 2) {
            return;
        }

        linePath.reset();
        int oldest = (nextSample - sampleCount + SAMPLE_CAPACITY) % SAMPLE_CAPACITY;
        for (int i = 0; i < sampleCount; i++) {
            float value = samples[(oldest + i) % SAMPLE_CAPACITY];
            float x = right - (sampleCount - 1 - i) * (right - left) / (SAMPLE_CAPACITY - 1);
            float y = bottom - Math.min(1.0f, value / maximum) * graphHeight;
            if (i == 0) {
                linePath.moveTo(x, y);
            } else {
                linePath.lineTo(x, y);
            }
        }
        canvas.drawPath(linePath, linePaint);
    }

    private float dynamicMaximum() {
        float maximum = 0.1f;
        int oldest = (nextSample - sampleCount + SAMPLE_CAPACITY) % SAMPLE_CAPACITY;
        for (int i = 0; i < sampleCount; i++) {
            maximum = Math.max(maximum, samples[(oldest + i) % SAMPLE_CAPACITY]);
        }
        return maximum * 1.1f;
    }

    private String formatScale(float value) {
        if (value >= 10.0f) {
            return Math.round(value) + " MB/s";
        }
        return String.format(java.util.Locale.US, "%.1f MB/s", value);
    }
}
