package com.quietpanel.client;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;

import java.util.Calendar;

public class AlarmHelper {

    public static final String PREF_ALARM_ENABLED = "alarm_enabled";
    public static final String PREF_ALARM_HOUR = "alarm_hour";
    public static final String PREF_ALARM_MINUTE = "alarm_minute";
    public static final String PREF_ALARM_REPEAT = "alarm_repeat"; // true for daily, false for once

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PhotoFolderActivity.PREFERENCES, Context.MODE_PRIVATE);
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            return am == null || am.canScheduleExactAlarms();
        }
        return true;
    }

    public static void updateAlarmSchedule(Context context) {
        SharedPreferences prefs = getPrefs(context);
        boolean enabled = prefs.getBoolean(PREF_ALARM_ENABLED, false);
        if (enabled) {
            int hour = prefs.getInt(PREF_ALARM_HOUR, 7);
            int minute = prefs.getInt(PREF_ALARM_MINUTE, 0);
            boolean repeat = prefs.getBoolean(PREF_ALARM_REPEAT, true);
            scheduleAlarmAt(context, hour, minute, repeat);
        } else {
            cancelAlarm(context);
        }
    }

    public static void scheduleAlarmAt(Context context, int hour, int minute, boolean repeat) {
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        if (cal.getTimeInMillis() < now) {
            Calendar nowCal = Calendar.getInstance();
            nowCal.setTimeInMillis(now);
            if (nowCal.get(Calendar.HOUR_OF_DAY) == hour && nowCal.get(Calendar.MINUTE) == minute) {
                setAlarmExact(context, now + 1000L);
                return;
            }
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        setAlarmExact(context, cal.getTimeInMillis());
    }

    public static void scheduleSnooze(Context context, int minutes) {
        long triggerAt = System.currentTimeMillis() + (minutes * 60 * 1000L);
        setAlarmExact(context, triggerAt);
    }

    @android.annotation.SuppressLint("ScheduleExactAlarm")
    private static void setAlarmExact(Context context, long triggerAtMillis) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = getPendingIntent(context);

        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else if (Build.VERSION.SDK_INT >= 19) {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            }
        } catch (Exception e) {
            e.printStackTrace();
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        }
    }

    public static void cancelAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = getPendingIntent(context);
        am.cancel(pi);
    }

    private static PendingIntent getPendingIntent(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, 1001, intent, flags);
    }

    public static String getNextAlarmTimeString(Context context) {
        SharedPreferences prefs = getPrefs(context);
        boolean enabled = prefs.getBoolean(PREF_ALARM_ENABLED, false);
        if (!enabled) return null;

        int hour = prefs.getInt(PREF_ALARM_HOUR, 7);
        int minute = prefs.getInt(PREF_ALARM_MINUTE, 0);
        return String.format(java.util.Locale.US, "%02d:%02d", hour, minute);
    }
}
