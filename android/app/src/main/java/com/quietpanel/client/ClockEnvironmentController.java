package com.quietpanel.client;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

import java.util.Calendar;

/** Night dimming, ambient brightness and burn-in protection for the clock panel. */
public final class ClockEnvironmentController implements SensorEventListener {
    public interface Listener {
        void onNightSleepChanged(boolean sleeping);
    }

    private static final long NIGHT_CHECK_MS = 60_000L;
    private static final long BURN_IN_MS = 180_000L;
    private static final long TOUCH_WAKE_MS = 30_000L;

    private final Activity activity;
    private final View clockPanel;
    private final Listener listener;
    private final Handler handler = new Handler();
    private final SensorManager sensorManager;
    private final Sensor lightSensor;
    private boolean running;
    private boolean sleeping;
    private boolean sensorRegistered;
    private long touchWakeUntil;
    private int burnStep;

    private final Runnable nightTicker = new Runnable() {
        @Override
        public void run() {
            applyMode();
            if (running) {
                handler.postDelayed(this, NIGHT_CHECK_MS);
            }
        }
    };

    private final Runnable burnInTicker = new Runnable() {
        @Override
        public void run() {
            applyBurnInOffset();
            if (running) {
                handler.postDelayed(this, BURN_IN_MS);
            }
        }
    };

    public ClockEnvironmentController(Activity activity, View clockPanel, Listener listener) {
        this.activity = activity;
        this.clockPanel = clockPanel;
        this.listener = listener;
        sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    public void start() {
        if (running) {
            applyMode();
            return;
        }
        running = true;
        applyMode();
        handler.postDelayed(nightTicker, NIGHT_CHECK_MS);
        handler.postDelayed(burnInTicker, BURN_IN_MS);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(nightTicker);
        handler.removeCallbacks(burnInTicker);
        unregisterSensor();
        clockPanel.setTranslationX(0);
        clockPanel.setTranslationY(0);
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
    }

    public void onUserTouch() {
        if (sleeping) {
            touchWakeUntil = SystemClock.elapsedRealtime() + TOUCH_WAKE_MS;
            applyMode();
            handler.removeCallbacks(nightTicker);
            handler.postDelayed(nightTicker, TOUCH_WAKE_MS);
        }
    }

    public boolean isSleeping() {
        return sleeping && SystemClock.elapsedRealtime() >= touchWakeUntil;
    }

    private void applyMode() {
        SharedPreferences prefs = preferences();
        boolean shouldSleep = prefs.getBoolean(PhotoFolderActivity.NIGHT_MODE_ENABLED, false)
                && isNightHour(prefs)
                && SystemClock.elapsedRealtime() >= touchWakeUntil;
        if (sleeping != shouldSleep) {
            sleeping = shouldSleep;
            if (listener != null) {
                listener.onNightSleepChanged(shouldSleep);
            }
        }
        if (shouldSleep) {
            unregisterSensor();
            setBrightness(0.02f);
        } else if (prefs.getBoolean(PhotoFolderActivity.AMBIENT_BRIGHTNESS_ENABLED, false)) {
            registerSensor();
        } else {
            unregisterSensor();
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        }
    }

    private boolean isNightHour(SharedPreferences prefs) {
        int start = prefs.getInt(PhotoFolderActivity.NIGHT_START_HOUR, 23);
        int end = prefs.getInt(PhotoFolderActivity.NIGHT_END_HOUR, 7);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (start == end) {
            return true;
        }
        return start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    private void applyBurnInOffset() {
        if (!preferences().getBoolean(PhotoFolderActivity.BURN_IN_ENABLED, true)) {
            clockPanel.setTranslationX(0);
            clockPanel.setTranslationY(0);
            return;
        }
        final int[] x = {0, 2, -2, 2, -2, 0};
        final int[] y = {0, -2, 2, 2, -2, 0};
        burnStep = (burnStep + 1) % x.length;
        float density = activity.getResources().getDisplayMetrics().density;
        clockPanel.setTranslationX(x[burnStep] * density);
        clockPanel.setTranslationY(y[burnStep] * density);
    }

    private void registerSensor() {
        if (!sensorRegistered && sensorManager != null && lightSensor != null) {
            sensorRegistered = sensorManager.registerListener(
                    this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void unregisterSensor() {
        if (sensorRegistered && sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        sensorRegistered = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_LIGHT || event.values.length == 0) {
            return;
        }
        float lux = Math.max(0.0f, event.values[0]);
        float brightness = Math.max(0.08f, Math.min(1.0f,
                0.08f + (float) (Math.log10(lux + 1.0) / 3.0)));
        setBrightness(brightness);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private SharedPreferences preferences() {
        return activity.getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, Context.MODE_PRIVATE);
    }

    private void setBrightness(float brightness) {
        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        if (Math.abs(params.screenBrightness - brightness) > 0.01f) {
            params.screenBrightness = brightness;
            activity.getWindow().setAttributes(params);
        }
    }
}
