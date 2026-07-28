package com.quietpanel.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Vibrator;

/**
 * API 29+ 前景服務：負責播放鬧鐘音效與震動。
 * 解決 Android 10 以上背景啟動 Activity 受限導致鬧鐘不響的問題。
 * API < 29 不使用此 Service，走原本的 startActivity 路徑。
 */
public class AlarmService extends Service {

    private static final String CHANNEL_ID = "alarm_channel_v1";
    private static final int NOTIFICATION_ID = 1001;
    private static final long AUTO_STOP_MS = 15 * 60 * 1000L; // 15 分鐘自動停止

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private final Handler handler = new Handler();

    private final Runnable autoStopRunnable = new Runnable() {
        @Override
        public void run() {
            stopSelf();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        showForegroundNotification();
        startRingingAndVibrating();
        handler.postDelayed(autoStopRunnable, AUTO_STOP_MS);

        // 嘗試啟動 Activity 顯示完整 UI
        try {
            Intent ringIntent = new Intent(this, AlarmRingingActivity.class);
            ringIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(ringIntent);
        } catch (Exception ignored) {
            // Activity 啟動失敗時，Service 仍持續播放音效，使用者可從通知操作
        }

        return START_NOT_STICKY;
    }

    private void showForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "鬧鐘通知", NotificationManager.IMPORTANCE_HIGH);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setSound(null, null);
            channel.enableVibration(false);
            nm.createNotificationChannel(channel);
        }

        Intent ringIntent = new Intent(this, AlarmRingingActivity.class);
        ringIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent fullScreenPi = PendingIntent.getActivity(this, 2002, ringIntent, piFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("⏰ 鬧鐘響起")
                .setContentText("點擊關閉或貪睡")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setAutoCancel(false)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setFullScreenIntent(fullScreenPi, true);

        startForeground(NOTIFICATION_ID, builder.build());
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

    /** 由 AlarmRingingActivity 呼叫以停止服務。 */
    public static void stopAlarmService(Context context) {
        context.stopService(new Intent(context, AlarmService.class));
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(autoStopRunnable);
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
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
        super.onDestroy();
    }
}
