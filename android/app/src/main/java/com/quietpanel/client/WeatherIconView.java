package com.quietpanel.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public final class WeatherIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path moonPath = new Path();
    private final Path cloudPath = new Path();
    private final Path boltPath = new Path();
    private int weatherCode;
    private boolean daytime = true;

    public WeatherIconView(Context context) {
        super(context);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("天氣");
    }

    public void setWeather(int code, boolean isDaytime) {
        weatherCode = code;
        daytime = isDaytime;
        setContentDescription(description(code, isDaytime));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth(), getHeight()) / 48f;
        canvas.save();
        canvas.scale(scale, scale);
        if (weatherCode == 0) {
            drawClear(canvas);
        } else if (weatherCode == 45 || weatherCode == 48) {
            drawFog(canvas);
        } else if (weatherCode >= 71 && weatherCode <= 77 || weatherCode >= 85 && weatherCode <= 86) {
            drawCloud(canvas);
            drawSnow(canvas);
        } else if (weatherCode >= 95) {
            drawCloud(canvas);
            drawThunder(canvas);
        } else if (weatherCode >= 51 && weatherCode <= 67 || weatherCode >= 80 && weatherCode <= 82) {
            drawCloud(canvas);
            drawRain(canvas);
        } else {
            drawClear(canvas);
            drawCloud(canvas);
        }
        canvas.restore();
    }

    private void drawClear(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.8f);
        paint.setColor(daytime ? Color.rgb(255, 210, 75) : Color.rgb(220, 232, 246));
        if (daytime) {
            canvas.drawCircle(18, 18, 7, paint);
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * i / 4.0;
                canvas.drawLine(
                        18 + (float) Math.cos(angle) * 11,
                        18 + (float) Math.sin(angle) * 11,
                        18 + (float) Math.cos(angle) * 14,
                        18 + (float) Math.sin(angle) * 14, paint);
            }
        } else {
            moonPath.reset();
            moonPath.moveTo(25, 8);
            moonPath.cubicTo(13, 10, 11, 25, 22, 30);
            moonPath.cubicTo(11, 30, 6, 17, 13, 10);
            canvas.drawPath(moonPath, paint);
        }
    }

    private void drawCloud(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(235, 241, 245));
        cloudPath.reset();
        cloudPath.moveTo(12, 34);
        cloudPath.cubicTo(5, 34, 5, 24, 13, 23);
        cloudPath.cubicTo(16, 14, 29, 14, 33, 23);
        cloudPath.cubicTo(43, 22, 45, 34, 36, 36);
        cloudPath.lineTo(13, 36);
        cloudPath.close();
        canvas.drawPath(cloudPath, paint);
    }

    private void drawRain(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f);
        paint.setColor(Color.rgb(70, 190, 225));
        canvas.drawLine(16, 39, 14, 44, paint);
        canvas.drawLine(25, 39, 23, 44, paint);
        canvas.drawLine(34, 39, 32, 44, paint);
    }

    private void drawSnow(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(Color.WHITE);
        canvas.drawLine(16, 40, 16, 45, paint);
        canvas.drawLine(13.5f, 42.5f, 18.5f, 42.5f, paint);
        canvas.drawLine(30, 40, 30, 45, paint);
        canvas.drawLine(27.5f, 42.5f, 32.5f, 42.5f, paint);
    }

    private void drawThunder(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(255, 210, 60));
        boltPath.reset();
        boltPath.moveTo(25, 37);
        boltPath.lineTo(19, 45);
        boltPath.lineTo(25, 44);
        boltPath.lineTo(22, 48);
        boltPath.lineTo(33, 40);
        boltPath.lineTo(27, 41);
        boltPath.close();
        canvas.drawPath(boltPath, paint);
    }

    private void drawFog(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(Color.rgb(220, 228, 232));
        canvas.drawLine(8, 18, 38, 18, paint);
        canvas.drawLine(13, 25, 42, 25, paint);
        canvas.drawLine(7, 32, 34, 32, paint);
    }

    private String description(int code, boolean isDaytime) {
        if (code == 0) return isDaytime ? "晴天" : "晴朗夜晚";
        if (code == 45 || code == 48) return "有霧";
        if (code >= 71 && code <= 77 || code >= 85 && code <= 86) return "下雪";
        if (code >= 95) return "雷雨";
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return "下雨";
        return "多雲";
    }
}
