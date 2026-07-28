package com.quietpanel.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public final class AlarmIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int iconColor = Color.rgb(79, 195, 247); // Accent cyan

    public AlarmIconView(Context context) {
        super(context);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("鬧鐘");
    }

    public void setIconColor(int color) {
        iconColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth(), getHeight()) / 48f;
        canvas.save();
        canvas.scale(scale, scale);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.8f);
        paint.setColor(iconColor);

        // Alarm Body Circle
        canvas.drawCircle(24, 26, 12, paint);

        // Left & Right ears
        canvas.drawLine(15, 12, 11, 8, paint);
        canvas.drawLine(33, 12, 37, 8, paint);

        // Left & Right feet
        canvas.drawLine(15, 37, 12, 41, paint);
        canvas.drawLine(33, 37, 36, 41, paint);

        // Clock Hands
        canvas.drawLine(24, 26, 18, 21, paint); // Hour hand (指向 10 點)
        canvas.drawLine(24, 26, 30, 19, paint); // Minute hand (指向 2 點)

        canvas.restore();
    }
}
