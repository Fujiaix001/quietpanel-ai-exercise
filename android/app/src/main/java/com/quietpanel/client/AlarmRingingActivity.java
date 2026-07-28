package com.quietpanel.client;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlarmRingingActivity extends Activity {

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private final Handler handler = new Handler();
    private static final long AUTO_SNOOZE_TIMEOUT_MS = 15 * 60 * 1000L; // 15 分鐘未操作自動貪睡

    private final Runnable autoSnoozeRunnable = new Runnable() {
        @Override
        public void run() {
            AlarmHelper.scheduleSnooze(AlarmRingingActivity.this, 5);
            stopRinging();
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setupWindowFlags();
        super.onCreate(savedInstanceState);

        setContentView(buildUI());
        if (Build.VERSION.SDK_INT < 29) {
            startRingingAndVibrating();
        }
        handler.postDelayed(autoSnoozeRunnable, AUTO_SNOOZE_TIMEOUT_MS);
    }

    private void setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private View buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xF0101216); // Dark transparent background
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        // Card Container
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(32), dp(32), dp(32), dp(32));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E222B);
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(cardBg);

        AlarmIconView alarmIcon = new AlarmIconView(this);
        alarmIcon.setIconColor(0xFF4FC3F7);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconParams.setMargins(0, 0, 0, dp(8));
        card.addView(alarmIcon, iconParams);

        // Title
        TextView title = new TextView(this);
        title.setText("鬧 鐘 響 起");
        title.setTextSize(22);
        title.setTextColor(0xFF4FC3F7); // Accent Cyan
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        // Time Text
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);
        TextView timeText = new TextView(this);
        timeText.setText(sdf.format(new Date()));
        timeText.setTextSize(64);
        timeText.setTextColor(Color.WHITE);
        timeText.setTypeface(Typeface.DEFAULT_BOLD);
        timeText.setGravity(Gravity.CENTER);
        timeText.setPadding(0, dp(16), 0, dp(24));
        card.addView(timeText);

        // Buttons Layout
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);

        // Snooze Button
        TextView snoozeBtn = createButton("貪睡 5 分鐘", 0xFF37474F, Color.WHITE);
        snoozeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlarmHelper.scheduleSnooze(AlarmRingingActivity.this, 5);
                stopRinging();
                finish();
            }
        });

        // Dismiss Button
        TextView dismissBtn = createButton("關閉鬧鐘", 0xFFD32F2F, Color.WHITE);
        dismissBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopRinging();
                finish();
            }
        });

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                dp(130), dp(48));
        btnParams.setMargins(dp(8), 0, dp(8), 0);

        buttonRow.addView(snoozeBtn, btnParams);
        buttonRow.addView(dismissBtn, btnParams);

        card.addView(buttonRow);
        root.addView(card, new LinearLayout.LayoutParams(
                dp(340), LinearLayout.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private TextView createButton(String text, int bgColor, int textColor) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(16);
        btn.setTextColor(textColor);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(24));
        btn.setBackground(bg);
        return btn;
    }

    private void startRingingAndVibrating() {
        try {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            mediaPlayer = new MediaPlayer();
            if (alarmUri != null) {
                mediaPlayer.setDataSource(this, alarmUri);
            }

            if (Build.VERSION.SDK_INT >= 21) {
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            } else {
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            }

            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 800, 800};
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRinging() {
        handler.removeCallbacks(autoSnoozeRunnable);
        // API 29+ 鬧鐘音效由 AlarmService 播放，需一併停止
        AlarmService.stopAlarmService(this);
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(1001);
            }
        } catch (Exception ignored) {
        }
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
            vibrator = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopRinging();
        super.onDestroy();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
