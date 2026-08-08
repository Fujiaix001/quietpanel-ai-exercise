package com.quietpanel.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** A small, non-animated daylight track for the optional weather extension. */
public final class DaylightProgressView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long sunriseAtMs = -1L;
    private long sunsetAtMs = -1L;
    private long nowAtMs;

    public DaylightProgressView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        trackPaint.setColor(Color.argb(105, 255, 255, 255));
        trackPaint.setStrokeWidth(Math.max(1.0f, density * 2.0f));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(Color.rgb(255, 198, 82));
        progressPaint.setStrokeWidth(Math.max(1.0f, density * 2.0f));
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setColor(Color.WHITE);
        nowAtMs = System.currentTimeMillis();
        setContentDescription("日照進度");
    }

    public void setTimes(long sunriseAtMs, long sunsetAtMs) {
        if (this.sunriseAtMs == sunriseAtMs && this.sunsetAtMs == sunsetAtMs) {
            return;
        }
        this.sunriseAtMs = sunriseAtMs;
        this.sunsetAtMs = sunsetAtMs;
        invalidate();
    }

    public void setNow(long nowAtMs) {
        this.nowAtMs = nowAtMs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float left = getPaddingLeft() + density * 6.0f;
        float right = getWidth() - getPaddingRight() - density * 6.0f;
        if (right <= left) {
            return;
        }
        float centerY = getHeight() * 0.5f;
        canvas.drawLine(left, centerY, right, centerY, trackPaint);

        if (sunriseAtMs <= 0L || sunsetAtMs <= sunriseAtMs) {
            return;
        }
        float progress = (nowAtMs - sunriseAtMs)
                / (float) (sunsetAtMs - sunriseAtMs);
        progress = Math.max(0.0f, Math.min(1.0f, progress));
        float currentX = left + (right - left) * progress;
        if (currentX > left) {
            canvas.drawLine(left, centerY, currentX, centerY, progressPaint);
        }
        canvas.drawCircle(currentX, centerY, density * 3.0f, dotPaint);
    }
}
