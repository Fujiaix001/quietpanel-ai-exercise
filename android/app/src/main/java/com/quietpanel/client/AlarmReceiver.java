package com.quietpanel.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

public class AlarmReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "alarm_channel_v1";

    @Override
    @android.annotation.SuppressLint("NotificationPermission")
    public void onReceive(Context context, Intent intent) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "QuietPanel:AlarmWakeLock");
            wakeLock.acquire(15000L); // Hold CPU awake for 15 seconds so Activity initializes completely
        }

        SharedPreferences prefs = AlarmHelper.getPrefs(context);
        boolean repeat = prefs.getBoolean(AlarmHelper.PREF_ALARM_REPEAT, true);
        if (!repeat) {
            prefs.edit().putBoolean(AlarmHelper.PREF_ALARM_ENABLED, false).apply();
        } else {
            int hour = prefs.getInt(AlarmHelper.PREF_ALARM_HOUR, 7);
            int minute = prefs.getInt(AlarmHelper.PREF_ALARM_MINUTE, 0);
            AlarmHelper.scheduleAlarmAt(context, hour, minute, true);
        }

        if (Build.VERSION.SDK_INT >= 29) {
            // API 29+ 透過 Foreground Service 播放鬧鐘，避免背景啟動 Activity 受限
            Intent serviceIntent = new Intent(context, AlarmService.class);
            try {
                context.startForegroundService(serviceIntent);
            } catch (RuntimeException ignored) {
                // 部分廠商系統會拒絕背景前景服務；回退至舊路徑避免鬧鐘靜音。
                launchLegacy(context);
            }
        } else {
            // API < 29 維持原本的 startActivity 路徑
            launchLegacy(context);
        }
    }

    /** API < 29 的原始邏輯：直接啟動 Activity 並發出通知。 */
    private void launchLegacy(Context context) {
        Intent ringIntent = new Intent(context, AlarmRingingActivity.class);
        ringIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                context, 2002, ringIntent, piFlags);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "鬧鐘通知", NotificationManager.IMPORTANCE_HIGH);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                channel.setSound(null, null);
                channel.enableVibration(false);
                nm.createNotificationChannel(channel);
            }

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }

            builder.setContentTitle("⏰ 鬧鐘響起")
                    .setContentText("點擊關閉或貪睡")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setFullScreenIntent(fullScreenPendingIntent, true);

            nm.notify(1001, builder.build());
        }

        try {
            context.startActivity(ringIntent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
