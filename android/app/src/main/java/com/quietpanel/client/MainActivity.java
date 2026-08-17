package com.quietpanel.client;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.MetricAffectingSpan;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity
        implements TransportServer.Listener, ApodServer.Listener,
        ClockEnvironmentController.Listener {
    private static final int BACKGROUND = Color.rgb(11, 15, 20);
    private static final int PANEL = Color.rgb(24, 31, 40);
    private static final int PRIMARY = Color.rgb(242, 238, 230);
    private static final int SECONDARY = Color.rgb(143, 152, 163);
    private static final int ACCENT = Color.rgb(72, 184, 199);
    private static final int WARNING = Color.rgb(239, 108, 108);
    private static final int SYSTEM_PAGE = 0;
    private static final int STORAGE_PAGE = 1;
    private static final int PHOTO_PAGE = 2;
    private static final int WORK_PHOTO_PAGE = 3;
    private static final int PAGE_COUNT = 7;
    private static final int DEFAULT_PHOTO_INTERVAL_SECONDS = 45;
    private static final int MIN_PHOTO_INTERVAL_SECONDS = 10;
    private static final int MAX_PHOTO_INTERVAL_SECONDS = 300;
    private static final long PHOTO_PAN_FRAME_MS = 200;
    private static final long PHOTO_TRANSITION_RESERVE_MS = 3000;
    private static final float PHOTO_PAN_TRAVEL_FRACTION = 0.13f;
    private static final float PHOTO_TIME_TEXT_SIZE_SP = 64.0f;
    private static final float PHOTO_DATE_TEXT_SIZE_SP = 24.0f;
    private static final float MIN_CLOCK_TEXT_SCALE = 0.75f;
    private static final float MAX_CLOCK_TEXT_SCALE = 2.5f;
    private static final String PHOTO_DIRECTORY = "QuietPanel/Photos";
    private static final Uri PRIVATE_ALBUM_URI = Uri.parse(
            "content://com.quietphoto.privatealbum.photos/photos");
    private static final String PRIVATE_ALBUM_CONTENT_URI = "content_uri";
    private static final String PRIVATE_ALBUM_SOURCE_FOLDER = "source_folder";
    // Keep the complete selected album.  A slideshow must not silently omit
    // images merely because the folder happens to contain a large collection.
    private static final int MAX_PHOTO_FILES = Integer.MAX_VALUE;

    /** Replaces Storopia's missing degree glyph with a matching geometric face. */
    private static final class FixedTypefaceSpan extends MetricAffectingSpan {
        private final Typeface typeface;

        FixedTypefaceSpan(Typeface typeface) {
            this.typeface = typeface;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            apply(paint);
        }

        @Override
        public void updateMeasureState(TextPaint paint) {
            apply(paint);
        }

        private void apply(Paint paint) {
            paint.setTypeface(typeface);
        }
    }

    /** A slideshow item selected from either a legacy path or a system document tree. */
    private static final class PhotoSource {
        final File file;
        final Uri uri;
        final String identity;

        private PhotoSource(File file, Uri uri, String identity) {
            this.file = file;
            this.uri = uri;
            this.identity = identity;
        }

        static PhotoSource fromFile(File file, String identity) {
            return new PhotoSource(file, null, identity);
        }

        static PhotoSource fromUri(Uri uri) {
            return new PhotoSource(null, uri, uri.toString());
        }
    }

    private interface PhotoDiscovery {
        void onPhotoDiscovered(PhotoSource source);
    }

    private final List<Button> actionButtons = new ArrayList<Button>();
    private final DiskRow[] diskRows = new DiskRow[4];
    private TransportServer transport;
    private ApodServer apodServer;
    private ClockEnvironmentController clockEnvironment;
    private TextView modePillButton;
    private LinearLayout appRoot;
    private LinearLayout appHeader;
    private SwipePager pager;
    private TextView connectionText;
    private TextView pageIndicator;
    private TextView macroActionText;
    private TextView toolActionText;
    private TextView cpuValue;
    private TextView cpuLabel;
    private HistoryGraphView cpuHistory;
    private HistoryGraphView memoryHistory;
    private HistoryGraphView downloadHistory;
    private HistoryGraphView uploadHistory;
    private HistoryGraphView diskReadHistory;
    private HistoryGraphView diskWriteHistory;
    private TextView memoryValue;
    private TextView downloadValue;
    private TextView uploadValue;
    private TextView diskReadValue;
    private TextView diskWriteValue;
    private ImageView photoBackgroundImage;
    private ImageView photoImage;
    private TextView photoStatus;
    private TextView photoTime;
    private TextView photoDate;
    private LinearLayout dateRow;
    private LinearLayout weatherRow;
    private WeatherIconView weatherIcon;
    private TextView weatherTemperature;
    private TextView weatherLocation;
    private LinearLayout daylightPanel;
    private DaylightProgressView daylightProgressView;
    private TextView daylightLabel;
    private LinearLayout alarmRow;
    private AlarmIconView alarmIcon;
    private TextView alarmTimeText;
    private Button photoSettingsButton;
    private LinearLayout clockPanel;
    private FrameLayout photoContentContainer;
    private FrameLayout photoPage;
    private FrameLayout workPhotoPage;
    private LinearLayout workButtonsContainer;
    private Button workScreenshotButton;
    private Button workPasteButton;
    private Bitmap photoBitmap;
    private Bitmap pendingPhotoBitmap;
    private Bitmap softBackgroundBitmap;
    private final List<PhotoSource> photoFiles = new ArrayList<PhotoSource>();
    private final Handler photoHandler = new Handler();
    private final Matrix photoMatrix = new Matrix();
    private final SimpleDateFormat photoTimeFormat =
            new SimpleDateFormat("HH:mm", Locale.TAIWAN);
    private final SimpleDateFormat photoDateChineseFormat =
            new SimpleDateFormat("M月d日 EEEE", Locale.TAIWAN);
    private final SimpleDateFormat photoDateEnglishFormat =
            new SimpleDateFormat("EEE, MMM d", Locale.US);
    private final SimpleDateFormat weatherSunTimeFormat =
            new SimpleDateFormat("HH:mm", Locale.TAIWAN);
    private final Date photoClockDate = new Date();
    private float clockDownX;
    private float clockDownY;
    private int clockDownLeft;
    private int clockDownTop;
    private ScaleGestureDetector clockScaleDetector;
    private boolean clockGestureWasScaling;
    private boolean clockGestureWasDragging;
    private boolean clockGestureWasPaging;
    private float clockTextScale = 1.0f;
    private float effectiveClockTextScale = 1.0f;
    private int clockFontStyle = PhotoFontManager.STYLE_STOROPIA;
    private int dateFontStyle = PhotoFontManager.STYLE_STOROPIA;
    private int weatherFontStyle = PhotoFontManager.STYLE_STOROPIA;
    private boolean weatherCompactLayout;
    private View.OnTouchListener clockTouchListener;
    private boolean clockBackgroundEnabled = true;
    private boolean lowPowerEnabled;
    private boolean softBackgroundEnabled;
    private boolean smartFocusEnabled;
    private boolean adaptiveColorEnabled;
    private boolean polaroidFrameEnabled;
    private boolean transition3dEnabled;
    private int photoDominantColor = Color.BLACK;
    private float photoFocusX = 0.5f;
    private float photoFocusY = 0.5f;
    private int photoIndex;
    private int photoFailures;
    private int photoGeneration;
    private ContentObserver privateAlbumObserver;
    private boolean privateAlbumObserverRegistered;
    private boolean photoLoading;
    private boolean photoScanInProgress;
    private boolean photoCatalogLoaded;
    private String photoFolderSignature = "";
    private boolean photoPanReverse = true;
    private boolean activityResumed;
    private boolean pcDisplayOn = true;
    private PowerManager.WakeLock displayWakeLock;
    private long photoPanStartedAt;
    private long nextPhotoAt;
    private ImageView apodImage;
    private TextView apodImageStatus;
    private TextView apodTitle;
    private TextView apodMeta;
    private TextView apodExplanation;
    private Bitmap apodBitmap;
    private String displayedApodDate = "";
    private int currentPage;
    private boolean hasSystemState;
    private double latestCpu;
    private double latestMemory;
    private double latestDownload;
    private double latestUpload;
    private double latestDiskRead;
    private double latestDiskWrite;
    private JSONArray latestDisks;
    private long highCpuStartedAt = -1;
    private boolean cpuWarning;
    private boolean diskWarning;
    private final boolean[] pageEnabled = { true, true, true, true, true, true, true };

    private final Runnable photoTicker = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed || !pcDisplayOn || (currentPage != PHOTO_PAGE && currentPage != WORK_PHOTO_PAGE)) {
                return;
            }
            updatePhotoClock();
            if (clockEnvironment != null && clockEnvironment.isSleeping()) {
                schedulePhotoTicker();
                return;
            }
            if (!photoLoading && !photoFiles.isEmpty()
                    && SystemClock.elapsedRealtime() >= nextPhotoAt) {
                loadNextPhoto();
            }
            schedulePhotoTicker();
        }
    };

    private final Runnable photoPanTicker = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed || !pcDisplayOn || lowPowerEnabled
                    || (clockEnvironment != null && clockEnvironment.isSleeping())
                    || (currentPage != PHOTO_PAGE && currentPage != WORK_PHOTO_PAGE)
                    || photoBitmap == null) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - photoPanStartedAt;
            float progress = Math.min(1.0f, (float) elapsed / getPhotoPanDurationMs());
            applyPhotoPan(progress);
            if (progress < 1.0f) {
                photoHandler.postDelayed(this, PHOTO_PAN_FRAME_MS);
            }
        }
    };

    private final Runnable photoFolderButtonHider = new Runnable() {
        @Override
        public void run() {
            hidePhotoFolderButton();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestModernRuntimePermissions();
        updateScreenKeepAwake();
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(buildInterface());
        setActionButtonsEnabled(false);
        loadApodCache();

        int savedMode = getSharedPreferences("quietpanel_prefs", MODE_PRIVATE)
                .getInt("transport_mode", TransportServer.MODE_ADB);
        transport = new TransportServer(this, this);
        transport.setMode(savedMode);
        transport.start();
        updateModePillText(savedMode);
        apodServer = new ApodServer(this);
        apodServer.start();
        clockEnvironment = new ClockEnvironmentController(this, clockPanel, this);
        registerPrivateAlbumObserver();
    }

    private void requestModernRuntimePermissions() {
        if (android.os.Build.VERSION.SDK_INT < 31) {
            return;
        }
        ArrayList<String> permissions = new ArrayList<String>();
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[permissions.size()]), 4101);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        applyPhotoSettings();
        if (clockEnvironment != null) {
            clockEnvironment.start();
        }
        if (pcDisplayOn && (currentPage == PHOTO_PAGE || currentPage == WORK_PHOTO_PAGE)) {
            hidePhotoFolderButtonImmediately();
            startPhotoSlideshow();
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (clockEnvironment != null) {
            clockEnvironment.stop();
        }
        unregisterPrivateAlbumObserver();
        pausePhotoSlideshow();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopPhotoSlideshow();
        if (transport != null) {
            transport.stop();
        }
        if (apodServer != null) {
            apodServer.stop();
        }
        if (clockEnvironment != null) {
            clockEnvironment.stop();
        }
        if (apodImage != null) {
            apodImage.setImageDrawable(null);
        }
        if (apodBitmap != null && !apodBitmap.isRecycled()) {
            apodBitmap.recycle();
            apodBitmap = null;
        }
        if (photoImage != null) {
            photoImage.setImageDrawable(null);
        }
        if (photoBitmap != null && !photoBitmap.isRecycled()) {
            photoBitmap.recycle();
            photoBitmap = null;
        }
        if (displayWakeLock != null && displayWakeLock.isHeld()) {
            displayWakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN
                && clockEnvironment != null) {
            clockEnvironment.onUserTouch();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onConnectionChanged(final boolean connected, final String detail) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectionText.setText(connected ? "LIVE  ·  " + detail : detail);
                connectionText.setTextColor(connected ? ACCENT : SECONDARY);
                setActionButtonsEnabled(connected);
                if (!connected) {
                    highCpuStartedAt = -1;
                    setCpuWarning(false);
                }
            }
        });
    }

    @Override
    public void onStateReceived(final JSONObject system, final JSONArray disks) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateSystem(system);
                updateDisks(disks);
            }
        });
    }

    @Override
    public void onActionResult(final long id, final boolean ok, final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setActionStatus((ok ? "完成  " : "失敗  ") + message,
                        ok ? ACCENT : WARNING);
            }
        });
    }

    @Override
    public void onDisplayStateChanged(final boolean displayOn) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyPcDisplayState(displayOn);
            }
        });
    }

    @Override
    public void onPageConfigReceived(final JSONArray enabledPages) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyPageConfig(enabledPages);
            }
        });
    }

    @Override
    public void onWeatherReceived(final JSONObject weather) {
        if (weather == null) {
            return;
        }
        getSharedPreferences(PhotoFolderActivity.PREFERENCES, MODE_PRIVATE)
                .edit().putString("weather_cache", weather.toString()).apply();
        runOnUiThread(new Runnable() {
            @Override public void run() { applyWeather(weather); }
        });
    }

    @Override
    public void onNightSleepChanged(final boolean sleeping) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (sleeping) {
                    stopPhotoPan();
                    photoHandler.removeCallbacks(photoTicker);
                } else if (activityResumed && pcDisplayOn
                        && (currentPage == PHOTO_PAGE || currentPage == WORK_PHOTO_PAGE)) {
                    schedulePhotoTicker();
                    if (!lowPowerEnabled && photoBitmap != null) {
                        startPhotoPan();
                    }
                }
            }
        });
    }

    @Override
    public void onApodReceived(final JSONObject metadata, final byte[] imageBytes) {
        final String date = metadata.optString("date", "");
        if (date.equals(displayedApodDate) && apodBitmap != null) {
            return;
        }

        saveApodCache(metadata, imageBytes);
        final Bitmap bitmap = decodeApod(imageBytes);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (bitmap == null) {
                    showApodError("NASA 圖片格式無法顯示");
                    return;
                }
                displayApod(metadata, bitmap);
            }
        });
    }

    @Override
    public void onApodError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showApodError(message);
            }
        });
    }

    private View buildInterface() {
        appRoot = new LinearLayout(this);
        appRoot.setOrientation(LinearLayout.VERTICAL);
        appRoot.setBackgroundColor(BACKGROUND);
        appRoot.setPadding(dp(12), dp(8), dp(12), dp(6));

        appHeader = new LinearLayout(this);
        appHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = makeText("QuietPanel", 20, PRIMARY, Gravity.START);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        modePillButton = makeText("AUTO", 12, Color.WHITE, Gravity.CENTER);
        modePillButton.setPadding(dp(8), dp(4), dp(8), dp(4));
        modePillButton.setBackground(rounded(Color.argb(125, 20, 30, 45)));
        modePillButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (transport == null) return;
                int next = (transport.getMode() + 1) % 4;
                getSharedPreferences("quietpanel_prefs", MODE_PRIVATE)
                        .edit().putInt("transport_mode", next).apply();
                transport.setMode(next);
                updateModePillText(next);
            }
        });
        connectionText = makeText("啟動連線服務…", 13, SECONDARY, Gravity.END);
        connectionText.setSingleLine(true);
        connectionText.setEllipsize(TextUtils.TruncateAt.END);

        appHeader.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(54)));
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(dp(64), dp(30));
        modeParams.setMargins(dp(12), 0, dp(12), 0);
        appHeader.addView(modePillButton, modeParams);
        appHeader.addView(connectionText, new LinearLayout.LayoutParams(0, dp(54), 1));
        appRoot.addView(appHeader, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        pager = new SwipePager(this);
        pager.addView(buildSystemPage());
        pager.addView(buildStoragePage());
        pager.addView(buildPhotoPage());
        pager.addView(buildWorkPhotoPage());
        pager.addView(buildApodPage());
        pager.addView(buildMacroPage());
        pager.addView(buildToolPage());
        pager.setListener(new SwipePager.Listener() {
            @Override
            public void onSwipe(int direction) {
                showAdjacentPage(direction);
            }
        });
        appRoot.addView(pager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        pageIndicator = makeText("", 14, SECONDARY, Gravity.CENTER);
        appRoot.addView(pageIndicator, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30)));
        showPage(0);

        return appRoot;
    }

    private View buildSystemPage() {
        LinearLayout page = pageContainer();

        LinearLayout firstRow = metricRow();
        MetricCard cpu = addMetric(
                firstRow, "CPU", "-- %", Color.rgb(255, 193, 7), true);
        MetricCard memory = addMetric(
                firstRow, "MEMORY", "-- %", Color.rgb(156, 112, 255), true);
        cpuLabel = cpu.label;
        cpuValue = cpu.value;
        memoryValue = memory.value;
        cpuHistory = cpu.history;
        memoryHistory = memory.history;

        LinearLayout secondRow = metricRow();
        MetricCard upload = addMetric(
                secondRow, "NETWORK  ↑", "-- MB/s", Color.rgb(102, 187, 106), false);
        MetricCard download = addMetric(
                secondRow, "NETWORK  ↓", "-- MB/s", Color.rgb(41, 182, 246), false);
        MetricCard diskRead = addMetric(
                secondRow, "DISK  READ", "-- MB/s", Color.rgb(255, 167, 38), false);
        MetricCard diskWrite = addMetric(
                secondRow, "DISK  WRITE", "-- MB/s", Color.rgb(171, 71, 188), false);
        downloadValue = download.value;
        uploadValue = upload.value;
        diskReadValue = diskRead.value;
        diskWriteValue = diskWrite.value;
        downloadHistory = download.history;
        uploadHistory = upload.history;
        diskReadHistory = diskRead.history;
        diskWriteHistory = diskWrite.history;

        page.addView(firstRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        page.addView(secondRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private View buildStoragePage() {
        LinearLayout page = pageContainer();
        for (int i = 0; i < diskRows.length; i++) {
            diskRows[i] = new DiskRow();
            page.addView(diskRows[i].container, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }
        return page;
    }

    private View buildPhotoPage() {
        photoPage = new FrameLayout(this);
        photoPage.setBackgroundColor(Color.BLACK);

        photoContentContainer = new FrameLayout(this);
        photoContentContainer.setClickable(true);
        photoContentContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (currentPage == PHOTO_PAGE) {
                    showPhotoSettingsButton();
                }
            }
        });

        photoImage = new ImageView(this);
        photoImage.setScaleType(ImageView.ScaleType.MATRIX);
        photoContentContainer.addView(photoImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        photoStatus = makeText(
                "點一下畫面可選擇相簿資料夾",
                16, SECONDARY, Gravity.CENTER);
        photoStatus.setPadding(dp(24), dp(12), dp(24), dp(12));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        photoContentContainer.addView(photoStatus, statusParams);

        clockPanel = new LinearLayout(this);
        clockPanel.setOrientation(LinearLayout.VERTICAL);
        clockPanel.setGravity(Gravity.RIGHT);
        clockPanel.setPadding(dp(18), dp(10), dp(18), dp(12));
        clockPanel.setBackground(rounded(Color.argb(105, 0, 0, 0)));
        clockScaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        clockGestureWasScaling = true;
                        if (clockPanel != null && clockPanel.getParent() != null) {
                            clockPanel.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        applyClockTextScale(clockTextScale * detector.getScaleFactor());
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        saveClockTextScale();
                        if (photoContentContainer != null) {
                            photoContentContainer.post(new Runnable() {
                                @Override
                                public void run() {
                                    moveClockPanel(clockPanel.getLeft(), clockPanel.getTop());
                                }
                            });
                        }
                    }
                });
        final int clockDragTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        clockTouchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                clockScaleDetector.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        clockGestureWasScaling = false;
                        clockGestureWasDragging = false;
                        clockGestureWasPaging = false;
                        clockDownX = event.getRawX();
                        clockDownY = event.getRawY();
                        clockDownLeft = clockPanel.getLeft();
                        clockDownTop = clockPanel.getTop();
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (clockGestureWasScaling || event.getPointerCount() > 1) {
                            return true;
                        }
                        float dragX = event.getRawX() - clockDownX;
                        float dragY = event.getRawY() - clockDownY;
                        if (!clockGestureWasDragging && !clockGestureWasPaging) {
                            if (Math.abs(dragX) <= clockDragTouchSlop
                                    && Math.abs(dragY) <= clockDragTouchSlop) {
                                return true;
                            }
                            if (Math.abs(dragX) > Math.abs(dragY) * 1.2f) {
                                clockGestureWasPaging = true;
                                return true;
                            }
                            clockGestureWasDragging = true;
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (clockGestureWasPaging) {
                            return true;
                        }
                        moveClockPanel(
                                clockDownLeft + Math.round(dragX),
                                clockDownTop + Math.round(dragY));
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (clockGestureWasPaging
                                && Math.abs(event.getRawX() - clockDownX)
                                >= clockDragTouchSlop * 4) {
                            showAdjacentPage(event.getRawX() < clockDownX ? 1 : -1);
                        }
                        if (!clockGestureWasScaling && clockGestureWasDragging) {
                            moveClockPanel(
                                    clockDownLeft + Math.round(event.getRawX() - clockDownX),
                                    clockDownTop + Math.round(event.getRawY() - clockDownY));
                            saveClockPosition();
                        }
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    default:
                        return true;
                }
            }
        };
        clockPanel.setOnTouchListener(clockTouchListener);

        photoTime = makeText("", PHOTO_TIME_TEXT_SIZE_SP, Color.WHITE, Gravity.RIGHT);
        photoTime.setTypeface(Typeface.DEFAULT_BOLD);
        photoTime.setIncludeFontPadding(false);
        photoTime.setShadowLayer(dp(3), dp(1), dp(1), Color.BLACK);
        clockPanel.addView(photoTime, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        photoDate = makeText("", PHOTO_DATE_TEXT_SIZE_SP, Color.WHITE, Gravity.RIGHT);
        photoDate.setIncludeFontPadding(false);
        photoDate.setShadowLayer(dp(2), dp(1), dp(1), Color.BLACK);
        dateRow = new LinearLayout(this);
        dateRow.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
        dateRow.addView(photoDate, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        clockPanel.addView(dateRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        weatherRow = new LinearLayout(this);
        weatherRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        weatherIcon = new WeatherIconView(this);
        weatherTemperature = makeText("", 20, Color.WHITE, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        weatherLocation = makeText("", 14, Color.WHITE, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        // Keep the supporting information compact.  The old 34/28dp fixed
        // rows were much taller than their text, especially after enlarging
        // the clock, which made weather/location and the alarm look detached.
        weatherRow.addView(weatherIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        weatherRow.addView(weatherTemperature, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)));
        LinearLayout.LayoutParams locationParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(24));
        locationParams.setMargins(dp(8), 0, 0, 0);
        weatherRow.addView(weatherLocation, locationParams);
        clockPanel.addView(weatherRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        daylightPanel = new LinearLayout(this);
        daylightPanel.setOrientation(LinearLayout.VERTICAL);
        daylightPanel.setGravity(Gravity.RIGHT);
        daylightProgressView = new DaylightProgressView(this);
        daylightLabel = makeText("", 12, Color.WHITE, Gravity.RIGHT);
        daylightLabel.setIncludeFontPadding(false);
        daylightLabel.setShadowLayer(dp(2), dp(1), dp(1), Color.BLACK);
        daylightPanel.addView(daylightProgressView, new LinearLayout.LayoutParams(
                dp(220), dp(14)));
        daylightPanel.addView(daylightLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));
        daylightPanel.setVisibility(View.GONE);
        clockPanel.addView(daylightPanel, new LinearLayout.LayoutParams(
                dp(220), LinearLayout.LayoutParams.WRAP_CONTENT));

        alarmRow = new LinearLayout(this);
        alarmRow.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
        alarmIcon = new AlarmIconView(this);
        alarmTimeText = makeText("", 16, Color.WHITE, Gravity.RIGHT | Gravity.BOTTOM);
        alarmTimeText.setIncludeFontPadding(false);
        LinearLayout.LayoutParams alarmIconParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        alarmIconParams.gravity = Gravity.BOTTOM;
        alarmRow.addView(alarmIcon, alarmIconParams);
        alarmRow.addView(alarmTimeText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(20)));
        clockPanel.addView(alarmRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        photoContentContainer.addView(clockPanel, clockParams);
        View.OnLayoutChangeListener clockBoundsListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                ensureClockInsidePage();
            }
        };
        photoContentContainer.addOnLayoutChangeListener(clockBoundsListener);
        clockPanel.addOnLayoutChangeListener(clockBoundsListener);

        photoSettingsButton = new Button(this);
        photoSettingsButton.setText("設定");
        photoSettingsButton.setTextColor(Color.WHITE);
        photoSettingsButton.setTextSize(17);
        photoSettingsButton.setAllCaps(false);
        photoSettingsButton.setAlpha(0.0f);
        photoSettingsButton.setVisibility(View.GONE);
        StateListDrawable photoFolderBackground = new StateListDrawable();
        photoFolderBackground.addState(
                new int[] { android.R.attr.state_pressed },
                rounded(Color.argb(110, 70, 90, 100)));
        photoFolderBackground.addState(
                new int[] {},
                rounded(Color.argb(68, 35, 52, 62)));
        photoSettingsButton.setBackground(photoFolderBackground);
        photoSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                photoHandler.removeCallbacks(photoFolderButtonHider);
                startActivity(new Intent(MainActivity.this, PhotoFolderActivity.class));
            }
        });
        FrameLayout.LayoutParams folderButtonParams = new FrameLayout.LayoutParams(
                dp(150), dp(48), Gravity.TOP | Gravity.LEFT);
        folderButtonParams.setMargins(dp(12), dp(12), 0, 0);
        photoContentContainer.addView(photoSettingsButton, folderButtonParams);

        workButtonsContainer = new LinearLayout(this);
        workButtonsContainer.setOrientation(LinearLayout.VERTICAL);
        workButtonsContainer.setVisibility(View.GONE);

        workScreenshotButton = new Button(this);
        workScreenshotButton.setText("CAPTURE");
        workScreenshotButton.setTextColor(Color.WHITE);
        workScreenshotButton.setTextSize(16);
        workScreenshotButton.setGravity(Gravity.CENTER);
        workScreenshotButton.setAllCaps(false);
        workScreenshotButton.setTypeface(PhotoFontManager.get(this, clockFontStyle));
        StateListDrawable screenshotBg = new StateListDrawable();
        screenshotBg.addState(new int[] { android.R.attr.state_pressed }, rounded(Color.argb(165, 30, 42, 56)));
        screenshotBg.addState(new int[] {}, rounded(Color.argb(105, 12, 18, 26)));
        workScreenshotButton.setBackground(screenshotBg);
        workScreenshotButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendActionString("screenshot_all", "CAPTURE");
            }
        });

        workPasteButton = new Button(this);
        workPasteButton.setText("PASTE");
        workPasteButton.setTextColor(Color.WHITE);
        workPasteButton.setTextSize(16);
        workPasteButton.setGravity(Gravity.CENTER);
        workPasteButton.setAllCaps(false);
        workPasteButton.setTypeface(PhotoFontManager.get(this, clockFontStyle));
        StateListDrawable pasteBg = new StateListDrawable();
        pasteBg.addState(new int[] { android.R.attr.state_pressed }, rounded(Color.argb(165, 30, 42, 56)));
        pasteBg.addState(new int[] {}, rounded(Color.argb(105, 12, 18, 26)));
        workPasteButton.setBackground(pasteBg);
        workPasteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendActionString("paste", "PASTE");
            }
        });

        LinearLayout.LayoutParams btn1Params = new LinearLayout.LayoutParams(dp(140), dp(60));
        btn1Params.bottomMargin = dp(8);
        LinearLayout.LayoutParams btn2Params = new LinearLayout.LayoutParams(dp(140), dp(60));
        workButtonsContainer.addView(workScreenshotButton, btn1Params);
        workButtonsContainer.addView(workPasteButton, btn2Params);

        FrameLayout.LayoutParams workContainerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT);
        workContainerParams.leftMargin = dp(16);
        workContainerParams.bottomMargin = dp(16);
        photoContentContainer.addView(workButtonsContainer, workContainerParams);

        photoPage.addView(photoContentContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        photoPage.post(new Runnable() {
            @Override
            public void run() {
                applyClockPosition();
                applyPhotoSettings();
            }
        });

        updatePhotoClock();
        applyPhotoSettings();
        return photoPage;
    }

    private View buildWorkPhotoPage() {
        workPhotoPage = new FrameLayout(this);
        workPhotoPage.setBackgroundColor(Color.BLACK);
        return workPhotoPage;
    }

    private View buildApodPage() {
        LinearLayout page = pageContainer();
        page.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout imagePanel = new LinearLayout(this);
        imagePanel.setOrientation(LinearLayout.VERTICAL);
        imagePanel.setGravity(Gravity.CENTER);
        imagePanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        imagePanel.setBackground(rounded(PANEL));

        apodImage = new ImageView(this);
        apodImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        apodImage.setAdjustViewBounds(true);
        imagePanel.addView(apodImage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        apodImageStatus = makeText("等待 Win10 取得 NASA 每日天文圖片…",
                13, SECONDARY, Gravity.CENTER);
        imagePanel.addView(apodImageStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        LinearLayout infoPanel = new LinearLayout(this);
        infoPanel.setOrientation(LinearLayout.VERTICAL);
        infoPanel.setPadding(dp(16), dp(12), dp(16), dp(12));
        infoPanel.setBackground(rounded(PANEL));
        apodTitle = makeText("NASA · ASTRONOMY PICTURE OF THE DAY",
                21, PRIMARY, Gravity.START);
        apodTitle.setTypeface(Typeface.DEFAULT_BOLD);
        infoPanel.addView(apodTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        apodMeta = makeText("每日更新 · 電腦下載後經 USB 傳送",
                12, ACCENT, Gravity.START);
        infoPanel.addView(apodMeta, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        apodExplanation = makeText(
                "圖片會保存在手機中；NASA 暫時無法連線時仍可觀看上一張。",
                14, SECONDARY, Gravity.START);
        apodExplanation.setGravity(Gravity.TOP | Gravity.START);
        apodExplanation.setLineSpacing(0, 1.12f);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(apodExplanation, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        infoPanel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 3);
        imageParams.setMargins(dp(5), dp(5), dp(5), dp(5));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 2);
        infoParams.setMargins(dp(5), dp(5), dp(5), dp(5));
        page.addView(imagePanel, imageParams);
        page.addView(infoPanel, infoParams);
        return page;
    }

    private View buildMacroPage() {
        LinearLayout page = pageContainer();
        final MacroSpec[] macros = new MacroSpec[] {
                new MacroSpec("靜音", "toggle_mute", Color.rgb(33, 150, 243)),
                new MacroSpec("YouTube", "open_youtube", Color.rgb(244, 67, 54)),
                new MacroSpec("全螢幕截圖", "screenshot_all", Color.rgb(103, 58, 183)),
                new MacroSpec("顯示桌面", "show_desktop", Color.rgb(0, 150, 136)),
                new MacroSpec("播放／暫停", "media_play_pause", Color.rgb(255, 152, 0)),
                new MacroSpec("音量＋", "volume_up", Color.rgb(76, 175, 80)),
                new MacroSpec("音量－", "volume_down", Color.rgb(96, 125, 139)),
                new MacroSpec("鎖定電腦", "lock_pc", Color.rgb(121, 85, 72)),
        };

        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = metricRow();
            for (int column = 0; column < 4; column++) {
                final MacroSpec macro = macros[rowIndex * 4 + column];
                Button button = makeActionButton(macro);
                actionButtons.add(button);
                row.addView(button, weightedCell());
            }
            page.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }

        macroActionText = makeText("點選按鈕後，結果會顯示在這裡", 13, SECONDARY, Gravity.CENTER);
        page.addView(macroActionText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        return page;
    }

    private View buildToolPage() {
        LinearLayout page = pageContainer();
        final MacroSpec[] tools = new MacroSpec[] {
                new MacroSpec("上一視窗", "switch_window", Color.rgb(63, 81, 181)),
                new MacroSpec("工作檢視", "task_view", Color.rgb(3, 169, 244)),
                new MacroSpec("最小化視窗", "minimize_window", Color.rgb(0, 150, 136)),
                new MacroSpec("關閉視窗\n（長按）", "close_window", Color.rgb(198, 40, 40), true),
                new MacroSpec("複製", "copy", Color.rgb(76, 175, 80)),
                new MacroSpec("貼上", "paste", Color.rgb(139, 195, 74)),
                new MacroSpec("復原", "undo", Color.rgb(255, 152, 0)),
                new MacroSpec("重做", "redo", Color.rgb(121, 85, 72)),
        };

        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = metricRow();
            for (int column = 0; column < 4; column++) {
                final MacroSpec tool = tools[rowIndex * 4 + column];
                Button button = makeActionButton(tool);
                actionButtons.add(button);
                row.addView(button, weightedCell());
            }
            page.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }

        toolActionText = makeText("視窗與編輯快捷鍵", 13, SECONDARY, Gravity.CENTER);
        page.addView(toolActionText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        return page;
    }

    private Button makeActionButton(final MacroSpec macro) {
        Button button = new Button(this);
        button.setText(macro.label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(17);
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);
        button.setBackground(makeButtonBackground(macro.color));
        if (macro.longPress) {
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    setActionStatus("請長按「關閉視窗」", Color.rgb(239, 178, 84));
                }
            });
            button.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    sendAction(macro);
                    return true;
                }
            });
        } else {
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendAction(macro);
                }
            });
        }
        return button;
    }

    private void sendAction(MacroSpec macro) {
        sendActionString(macro.action, macro.label.replace("\n（長按）", ""));
    }

    private void sendActionString(String actionName, String labelName) {
        long id = transport == null ? -1 : transport.sendAction(actionName);
        if (id < 0) {
            setActionStatus("尚未連線，指令未送出", Color.rgb(239, 108, 108));
        } else {
            setActionStatus("已送出  " + labelName, SECONDARY);
        }
    }

    private void setActionStatus(String message, int color) {
        if (macroActionText != null) {
            macroActionText.setText(message);
            macroActionText.setTextColor(color);
        }
        if (toolActionText != null) {
            toolActionText.setText(message);
            toolActionText.setTextColor(color);
        }
    }

    private void updateSystem(JSONObject system) {
        if (system == null) {
            return;
        }
        hasSystemState = true;
        latestCpu = system.optDouble("cpuPercent", 0);
        latestMemory = system.optDouble("ramPercent", 0);
        latestDownload = system.optDouble("networkDownMBps", 0);
        latestUpload = system.optDouble("networkUpMBps", 0);
        latestDiskRead = system.optDouble("diskReadMBps", 0);
        latestDiskWrite = system.optDouble("diskWriteMBps", 0);

        boolean redraw = currentPage == SYSTEM_PAGE;
        cpuHistory.addSample(latestCpu, redraw);
        memoryHistory.addSample(latestMemory, redraw);
        downloadHistory.addSample(latestDownload, redraw);
        uploadHistory.addSample(latestUpload, redraw);
        diskReadHistory.addSample(latestDiskRead, redraw);
        diskWriteHistory.addSample(latestDiskWrite, redraw);
        updateCpuWarning(latestCpu);
        if (redraw) {
            renderSystemValues();
        }
    }

    private void updateDisks(JSONArray disks) {
        latestDisks = disks;
        boolean warning = false;
        for (int i = 0; i < diskRows.length; i++) {
            JSONObject disk = disks == null ? null : disks.optJSONObject(i);
            if (disk != null) {
                double total = disk.optDouble("totalGB", 0);
                double used = disk.optDouble("usedGB", 0);
                warning |= total > 0 && used / total >= 0.9;
            }
        }
        if (diskWarning != warning) {
            diskWarning = warning;
            updatePageIndicator();
        }
        if (currentPage == STORAGE_PAGE) {
            renderDisks();
        }
    }

    private void renderSystemValues() {
        if (!hasSystemState) {
            return;
        }
        cpuValue.setText(format(latestCpu) + " %");
        memoryValue.setText(format(latestMemory) + " %");
        downloadValue.setText(format(latestDownload) + " MB/s");
        uploadValue.setText(format(latestUpload) + " MB/s");
        diskReadValue.setText(format(latestDiskRead) + " MB/s");
        diskWriteValue.setText(format(latestDiskWrite) + " MB/s");
    }

    private void redrawSystemGraphs() {
        cpuHistory.redraw();
        memoryHistory.redraw();
        downloadHistory.redraw();
        uploadHistory.redraw();
        diskReadHistory.redraw();
        diskWriteHistory.redraw();
    }

    private void renderDisks() {
        for (int i = 0; i < diskRows.length; i++) {
            diskRows[i].update(latestDisks == null ? null : latestDisks.optJSONObject(i));
        }
    }

    private void updateCpuWarning(double cpuPercent) {
        if (cpuPercent >= 90.0) {
            if (highCpuStartedAt < 0) {
                highCpuStartedAt = SystemClock.elapsedRealtime();
            }
            if (SystemClock.elapsedRealtime() - highCpuStartedAt >= 30000) {
                setCpuWarning(true);
            }
        } else {
            highCpuStartedAt = -1;
            if (cpuPercent <= 85.0) {
                setCpuWarning(false);
            }
        }
    }

    private void setCpuWarning(boolean warning) {
        if (cpuWarning == warning) {
            return;
        }
        cpuWarning = warning;
        cpuLabel.setText(warning ? "CPU  ·  過高" : "CPU");
        cpuLabel.setTextColor(warning ? WARNING : SECONDARY);
        cpuValue.setTextColor(warning ? WARNING : PRIMARY);
        updatePageIndicator();
    }

    private void showPage(int requestedPage) {
        if (requestedPage < 0 || requestedPage >= PAGE_COUNT || !pageEnabled[requestedPage]) {
            requestedPage = firstEnabledPage();
        }
        if (currentPage == PHOTO_PAGE || currentPage == WORK_PHOTO_PAGE) {
            if (requestedPage != PHOTO_PAGE && requestedPage != WORK_PHOTO_PAGE) {
                pausePhotoSlideshow();
                hidePhotoFolderButtonImmediately();
            }
        }
        currentPage = requestedPage;
        pager.setDisplayedChild(currentPage);
        if (currentPage == SYSTEM_PAGE) {
            renderSystemValues();
            redrawSystemGraphs();
        } else if (currentPage == STORAGE_PAGE) {
            renderDisks();
        }
        boolean photoFullScreen = (currentPage == PHOTO_PAGE || currentPage == WORK_PHOTO_PAGE);
        appHeader.setVisibility(photoFullScreen ? View.GONE : View.VISIBLE);
        pageIndicator.setVisibility(photoFullScreen ? View.GONE : View.VISIBLE);
        if (photoFullScreen) {
            appRoot.setPadding(0, 0, 0, 0);
            FrameLayout targetContainer = (currentPage == WORK_PHOTO_PAGE) ? workPhotoPage : photoPage;
            if (photoContentContainer != null && targetContainer != null && photoContentContainer.getParent() != targetContainer) {
                if (photoContentContainer.getParent() != null) {
                    ((ViewGroup) photoContentContainer.getParent()).removeView(photoContentContainer);
                }
                targetContainer.addView(photoContentContainer, 0, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
            if (workButtonsContainer != null) {
                workButtonsContainer.setVisibility(currentPage == WORK_PHOTO_PAGE ? View.VISIBLE : View.GONE);
            }
            if (clockPanel != null) {
                clockPanel.setOnTouchListener(currentPage == PHOTO_PAGE ? clockTouchListener : null);
            }
            // The photo page is GONE while the app starts on page 1, so its
            // initial posted layout pass has no usable bounds. Restore the
            // saved clock position after this page becomes visible and the
            // shared photo container has been attached to its final parent.
            if (photoContentContainer != null) {
                photoContentContainer.post(new Runnable() {
                    @Override
                    public void run() {
                        applyClockPosition();
                    }
                });
            }
            if (currentPage != PHOTO_PAGE) {
                hidePhotoFolderButtonImmediately();
            }
            if (activityResumed && pcDisplayOn) {
                startPhotoSlideshow();
            }
        } else {
            appRoot.setPadding(dp(12), dp(8), dp(12), dp(6));
        }
        updatePageIndicator();
    }

    private void showPhotoSettingsButton() {
        if (photoSettingsButton == null || currentPage != PHOTO_PAGE) {
            return;
        }
        photoHandler.removeCallbacks(photoFolderButtonHider);
        photoSettingsButton.animate().cancel();
        photoSettingsButton.setVisibility(View.VISIBLE);
        photoSettingsButton.animate().alpha(0.82f).setDuration(180).start();
        photoHandler.postDelayed(photoFolderButtonHider, 5000);
    }

    private void hidePhotoFolderButton() {
        if (photoSettingsButton == null || photoSettingsButton.getVisibility() != View.VISIBLE) {
            return;
        }
        photoSettingsButton.animate().cancel();
        photoSettingsButton.animate().alpha(0.0f).setDuration(260).withEndAction(new Runnable() {
            @Override
            public void run() {
                photoSettingsButton.setVisibility(View.GONE);
            }
        }).start();
    }

    private void hidePhotoFolderButtonImmediately() {
        photoHandler.removeCallbacks(photoFolderButtonHider);
        if (photoSettingsButton != null) {
            photoSettingsButton.animate().cancel();
            photoSettingsButton.setAlpha(0.0f);
            photoSettingsButton.setVisibility(View.GONE);
        }
    }

    private void moveClockPanel(int left, int top) {
        if (photoContentContainer == null || clockPanel == null) {
            return;
        }
        int maxLeft = Math.max(0, photoContentContainer.getWidth() - clockPanel.getWidth());
        int maxTop = Math.max(0, photoContentContainer.getHeight() - clockPanel.getHeight());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) clockPanel.getLayoutParams();
        int clampedLeft = Math.max(0, Math.min(left, maxLeft));
        int clampedTop = Math.max(0, Math.min(top, maxTop));
        if (params.leftMargin == clampedLeft && params.topMargin == clampedTop) {
            return;
        }
        params.leftMargin = clampedLeft;
        params.topMargin = clampedTop;
        clockPanel.setLayoutParams(params);
    }

    private void applyClockPosition() {
        if (photoContentContainer == null || clockPanel == null
                || photoContentContainer.getWidth() <= 0 || photoContentContainer.getHeight() <= 0) {
            return;
        }
        android.content.SharedPreferences preferences = getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, MODE_PRIVATE);
        int maxLeft = Math.max(0, photoContentContainer.getWidth() - clockPanel.getWidth());
        int maxTop = Math.max(0, photoContentContainer.getHeight() - clockPanel.getHeight());
        float xRatio = preferences.getFloat(clockOrientationKey(PhotoFolderActivity.CLOCK_X_RATIO),
                preferences.getFloat(PhotoFolderActivity.CLOCK_X_RATIO, 1.0f));
        float yRatio = preferences.getFloat(clockOrientationKey(PhotoFolderActivity.CLOCK_Y_RATIO),
                preferences.getFloat(PhotoFolderActivity.CLOCK_Y_RATIO, 1.0f));
        boolean positionCustomized = preferences.getBoolean(
                clockOrientationKey(PhotoFolderActivity.CLOCK_POSITION_CUSTOMIZED),
                preferences.getBoolean(PhotoFolderActivity.CLOCK_POSITION_CUSTOMIZED, false));
        if (!positionCustomized) {
            moveClockPanel(maxLeft - dp(12), maxTop - dp(12));
            return;
        }
        moveClockPanel(Math.round(maxLeft * clampRatio(xRatio)),
                Math.round(maxTop * clampRatio(yRatio)));
    }

    private void saveClockPosition() {
        if (photoContentContainer == null || clockPanel == null) {
            return;
        }
        int maxLeft = Math.max(1, photoContentContainer.getWidth() - clockPanel.getWidth());
        int maxTop = Math.max(1, photoContentContainer.getHeight() - clockPanel.getHeight());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) clockPanel.getLayoutParams();
        getSharedPreferences(PhotoFolderActivity.PREFERENCES, MODE_PRIVATE)
                .edit()
                .putFloat(clockOrientationKey(PhotoFolderActivity.CLOCK_X_RATIO),
                        clampRatio((float) params.leftMargin / maxLeft))
                .putFloat(clockOrientationKey(PhotoFolderActivity.CLOCK_Y_RATIO),
                        clampRatio((float) params.topMargin / maxTop))
                .putBoolean(clockOrientationKey(PhotoFolderActivity.CLOCK_POSITION_CUSTOMIZED), true)
                .apply();
    }

    private String clockOrientationKey(String base) {
        int orientation = getResources().getConfiguration().orientation;
        return base + (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                ? "_portrait" : "_landscape");
    }

    private float clampRatio(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private void applyPhotoSettings() {
        if (clockPanel == null) {
            return;
        }
        android.content.SharedPreferences preferences = getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, MODE_PRIVATE);
        clockBackgroundEnabled = preferences.getBoolean(
                PhotoFolderActivity.CLOCK_BACKGROUND, true);
        lowPowerEnabled = preferences.getBoolean(
                PhotoFolderActivity.LOW_POWER_ENABLED, false);
        clockFontStyle = PhotoFontManager.normalize(preferences.getInt(
                PhotoFolderActivity.CLOCK_FONT_STYLE, PhotoFontManager.STYLE_STOROPIA));
        dateFontStyle = PhotoFontManager.normalize(preferences.getInt(
                PhotoFolderActivity.DATE_FONT_STYLE, clockFontStyle));
        weatherFontStyle = PhotoFontManager.normalize(preferences.getInt(
                PhotoFolderActivity.WEATHER_FONT_STYLE, clockFontStyle));
        // This ADB edition deliberately omits optional image effects. Reset
        // stale preferences from newer wireless builds rather than applying
        // them silently.
        softBackgroundEnabled = false;
        smartFocusEnabled = false;
        adaptiveColorEnabled = false;
        polaroidFrameEnabled = false;
        transition3dEnabled = false;

        applyClockBackground();
        applyClockFontStyle();
        if (photoTime != null) {
            photoTime.setVisibility(preferences.getBoolean(
                    PhotoFolderActivity.CLOCK_TIME_ENABLED, true) ? View.VISIBLE : View.GONE);
        }
        if (photoDate != null) {
            photoDate.setVisibility(preferences.getBoolean(
                    PhotoFolderActivity.CLOCK_DATE_ENABLED, true) ? View.VISIBLE : View.GONE);
        }
        updateAlarmIndicator();
        String weatherText = preferences.getString("weather_cache", "");
        if (weatherText != null && weatherText.length() > 0) {
            try {
                applyWeather(new JSONObject(weatherText));
            } catch (Exception ignored) {
                hideWeather();
            }
        } else {
            hideWeather();
        }
        applyPolaroidFrame();
        if (photoBitmap != null) {
            applyPhotoPresentation(photoBitmap);
        } else if (!softBackgroundEnabled) {
            releaseSoftBackground();
        }
        if (!transition3dEnabled && photoImage != null) {
            photoImage.animate().cancel();
            photoImage.setRotationY(0.0f);
            photoImage.setAlpha(1.0f);
        }
        applyClockTextScale(preferences.getFloat(PhotoFolderActivity.CLOCK_TEXT_SCALE, 1.0f));
        applyClockPosition();
    }

    private void applyClockFontStyle() {
        Typeface timeTypeface = PhotoFontManager.get(this, clockFontStyle);
        Typeface dateTypeface = PhotoFontManager.get(this, dateFontStyle);
        Typeface weatherTypeface = PhotoFontManager.get(this, weatherFontStyle);
        if (photoTime != null) {
            photoTime.setTypeface(timeTypeface);
        }
        if (photoDate != null) {
            photoDate.setTypeface(dateTypeface);
        }
        if (workScreenshotButton != null) {
            workScreenshotButton.setTypeface(timeTypeface);
        }
        if (workPasteButton != null) {
            workPasteButton.setTypeface(timeTypeface);
        }
        if (weatherTemperature != null) {
            weatherTemperature.setTypeface(weatherTypeface);
        }
        if (weatherLocation != null) {
            weatherLocation.setTypeface(weatherTypeface);
        }
        if (daylightLabel != null) {
            daylightLabel.setTypeface(weatherTypeface);
        }
        if (alarmTimeText != null) {
            alarmTimeText.setTypeface(timeTypeface);
        }
        updatePhotoClock();
    }

    private void applyClockBackground() {
        if (clockPanel == null) {
            return;
        }
        if (!clockBackgroundEnabled) {
            clockPanel.setBackground(null);
            return;
        }
        int color = adaptiveColorEnabled
                ? Color.argb(100, Color.red(photoDominantColor),
                        Color.green(photoDominantColor), Color.blue(photoDominantColor))
                : Color.argb(105, 0, 0, 0);
        clockPanel.setBackground(rounded(color));
    }

    private void applyPolaroidFrame() {
        if (photoImage == null) {
            return;
        }
        if (!polaroidFrameEnabled) {
            photoImage.setBackground(null);
            photoImage.setPadding(0, 0, 0, 0);
            return;
        }
        GradientDrawable frame = rounded(Color.rgb(248, 246, 240));
        photoImage.setBackground(frame);
        photoImage.setPadding(dp(12), dp(12), dp(12), dp(36));
    }

    private void applySoftBackground(Bitmap bitmap) {
        if (!softBackgroundEnabled || photoContentContainer == null || bitmap == null) {
            return;
        }
        if (photoBackgroundImage == null) {
            photoBackgroundImage = new ImageView(this);
            photoBackgroundImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photoBackgroundImage.setAlpha(0.72f);
            photoContentContainer.addView(photoBackgroundImage, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        releaseSoftBackgroundBitmap();
        try {
            softBackgroundBitmap = Bitmap.createScaledBitmap(bitmap, 48, 48, true);
            photoBackgroundImage.setImageBitmap(softBackgroundBitmap);
        } catch (OutOfMemoryError ignored) {
            releaseSoftBackground();
        }
    }

    private void releaseSoftBackground() {
        releaseSoftBackgroundBitmap();
        if (photoBackgroundImage != null) {
            photoBackgroundImage.setImageDrawable(null);
            if (photoContentContainer != null) {
                photoContentContainer.removeView(photoBackgroundImage);
            }
            photoBackgroundImage = null;
        }
    }

    private void releaseSoftBackgroundBitmap() {
        if (softBackgroundBitmap != null && !softBackgroundBitmap.isRecycled()) {
            softBackgroundBitmap.recycle();
        }
        softBackgroundBitmap = null;
    }

    private void applyClockTextScale(float requestedScale) {
        clockTextScale = Math.max(MIN_CLOCK_TEXT_SCALE,
                Math.min(MAX_CLOCK_TEXT_SCALE, requestedScale));
        setClockDisplayScale(clockTextScale);
        if (clockPanel != null) {
            clockPanel.post(new Runnable() {
                @Override
                public void run() {
                    ensureClockInsidePage();
                }
            });
        }
    }

    private void setClockDisplayScale(float displayScale) {
        effectiveClockTextScale = displayScale;
        if (photoTime != null) {
            photoTime.setTextSize(PHOTO_TIME_TEXT_SIZE_SP * displayScale);
        }
        if (photoDate != null) {
            photoDate.setTextSize(PHOTO_DATE_TEXT_SIZE_SP * displayScale);
        }
        if (weatherTemperature != null) {
            weatherTemperature.setTextSize(
                    (weatherCompactLayout ? 18.0f : 20.0f) * displayScale);
        }
        if (weatherLocation != null) {
            weatherLocation.setTextSize(14.0f * displayScale);
        }
        if (daylightLabel != null) {
            daylightLabel.setTextSize(12.0f * displayScale);
        }
        if (alarmTimeText != null) {
            alarmTimeText.setTextSize(16.0f * displayScale);
        }

        // Scale the compact supporting rows with their text.  These dimensions
        // leave enough room for the glyphs, but remove the oversized blank
        // line spacing that the earlier 34/28dp rows introduced.
        float weatherBaseSize = weatherCompactLayout ? 22.0f : 24.0f;
        int weatherSize = dp(Math.max(1, Math.round(weatherBaseSize * displayScale)));
        int alarmTextHeight = dp(Math.max(1, Math.round(20.0f * displayScale)));
        int alarmIconSize = dp(Math.max(1, Math.round(14.0f * displayScale)));
        resizeView(weatherIcon, weatherSize, weatherSize);
        resizeView(weatherTemperature, ViewGroup.LayoutParams.WRAP_CONTENT, weatherSize);
        resizeView(weatherLocation, ViewGroup.LayoutParams.WRAP_CONTENT, weatherSize);
        int daylightWidth = dp(Math.max(1, Math.round(220.0f * displayScale)));
        resizeView(daylightPanel, daylightWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        resizeView(daylightProgressView, daylightWidth,
                dp(Math.max(1, Math.round(14.0f * displayScale))));
        resizeView(daylightLabel, ViewGroup.LayoutParams.MATCH_PARENT,
                dp(Math.max(1, Math.round(18.0f * displayScale))));
        if (weatherLocation != null && weatherLocation.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) weatherLocation.getLayoutParams()).leftMargin =
                    dp(Math.max(1, Math.round(8.0f * displayScale)));
            weatherLocation.requestLayout();
        }
        resizeView(alarmIcon, alarmIconSize, alarmIconSize);
        resizeView(alarmTimeText, ViewGroup.LayoutParams.WRAP_CONTENT, alarmTextHeight);
    }

    private void resizeView(View view, int width, int height) {
        if (view == null || view.getLayoutParams() == null) {
            return;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
        }
    }

    private void ensureClockInsidePage() {
        if (photoContentContainer == null || clockPanel == null
                || photoContentContainer.getWidth() <= 0 || photoContentContainer.getHeight() <= 0
                || clockPanel.getWidth() <= 0 || clockPanel.getHeight() <= 0) {
            return;
        }
        int margin = dp(12);
        int availableWidth = Math.max(1, photoContentContainer.getWidth() - margin * 2);
        int availableHeight = Math.max(1, photoContentContainer.getHeight() - margin * 2);
        float requestedRatio = clockTextScale / Math.max(0.1f, effectiveClockTextScale);
        int horizontalPadding = clockPanel.getPaddingLeft() + clockPanel.getPaddingRight();
        int verticalPadding = clockPanel.getPaddingTop() + clockPanel.getPaddingBottom();
        float requestedWidth = (clockPanel.getWidth() - horizontalPadding)
                * requestedRatio + horizontalPadding;
        float requestedHeight = (clockPanel.getHeight() - verticalPadding)
                * requestedRatio + verticalPadding;
        float fit = Math.min(1.0f, Math.min(
                availableWidth / requestedWidth, availableHeight / requestedHeight));
        float targetScale = Math.max(0.1f, clockTextScale * fit);
        if (Math.abs(targetScale - effectiveClockTextScale) > 0.005f) {
            setClockDisplayScale(targetScale);
            return;
        }
        moveClockPanel(clockPanel.getLeft(), clockPanel.getTop());
    }

    private void saveClockTextScale() {
        getSharedPreferences(PhotoFolderActivity.PREFERENCES, MODE_PRIVATE)
                .edit()
                .putFloat(PhotoFolderActivity.CLOCK_TEXT_SCALE, clockTextScale)
                .apply();
    }

    private long getPhotoIntervalMs() {
        int seconds = getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, MODE_PRIVATE)
                .getInt(PhotoFolderActivity.PHOTO_INTERVAL_SECONDS,
                        DEFAULT_PHOTO_INTERVAL_SECONDS);
        seconds = Math.max(MIN_PHOTO_INTERVAL_SECONDS,
                Math.min(MAX_PHOTO_INTERVAL_SECONDS, seconds));
        return seconds * 1000L;
    }

    private long getPhotoPanDurationMs() {
        return Math.max(1000L, getPhotoIntervalMs() - PHOTO_TRANSITION_RESERVE_MS);
    }

    private void showAdjacentPage(int direction) {
        int candidate = currentPage + direction;
        while (candidate >= 0 && candidate < PAGE_COUNT) {
            if (pageEnabled[candidate]) {
                showPage(candidate);
                return;
            }
            candidate += direction;
        }
    }

    private int firstEnabledPage() {
        for (int i = 0; i < PAGE_COUNT; i++) {
            if (pageEnabled[i]) {
                return i;
            }
        }
        return 0;
    }

    private void applyPageConfig(JSONArray enabledPages) {
        if (enabledPages == null) {
            return;
        }
        boolean[] updated = new boolean[PAGE_COUNT];
        int enabledCount = 0;
        for (int i = 0; i < enabledPages.length(); i++) {
            int page = enabledPages.optInt(i, -1);
            if (page >= 0 && page < PAGE_COUNT && !updated[page]) {
                updated[page] = true;
                enabledCount++;
            }
        }
        if (enabledCount == 0) {
            return;
        }
        System.arraycopy(updated, 0, pageEnabled, 0, PAGE_COUNT);
        if (!pageEnabled[currentPage]) {
            showPage(firstEnabledPage());
        } else {
            updatePageIndicator();
        }
    }

    private void updatePageIndicator() {
        String[] labels = { "系統", "儲存", "相簿", "工作", "NASA", "MACRO", "快捷" };
        StringBuilder dots = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < labels.length; i++) {
            if (!pageEnabled[i]) {
                continue;
            }
            if (!first) {
                dots.append("   ");
            }
            dots.append(i == currentPage ? "●" : "○");
            first = false;
        }
        dots.append("     ").append(labels[currentPage]);
        if (cpuWarning) {
            dots.append("  ·  CPU 過高");
        }
        if (diskWarning) {
            dots.append("  ·  磁碟空間不足");
        }
        pageIndicator.setText(dots.toString());
        pageIndicator.setTextColor(cpuWarning || diskWarning ? WARNING : SECONDARY);
    }

    private void startPhotoSlideshow() {
        updatePhotoClock();
        Set<String> selectedFolders = getSelectedPhotoFolders();
        String folderSignature = buildPhotoFolderSignature(selectedFolders);
        if (photoScanInProgress && folderSignature.equals(photoFolderSignature)) {
            return;
        }
        if (photoCatalogLoaded && folderSignature.equals(photoFolderSignature)) {
            if (photoBitmap == null && !photoFiles.isEmpty()) {
                loadNextPhoto();
            } else {
                if (photoBitmap != null) {
                    photoStatus.setVisibility(View.GONE);
                    applyPhotoPresentation(photoBitmap);
                }
                schedulePhotoTicker();
            }
            return;
        }
        pausePhotoSlideshow();
        refreshPhotoFiles(selectedFolders, folderSignature);
    }

    private void schedulePhotoTicker() {
        photoHandler.removeCallbacks(photoTicker);
        if (!activityResumed || !pcDisplayOn || (currentPage != PHOTO_PAGE && currentPage != WORK_PHOTO_PAGE)) {
            return;
        }
        long wallTime = System.currentTimeMillis();
        long delay = 60000 - wallTime % 60000 + 25;
        if (!photoLoading && !photoFiles.isEmpty()) {
            long untilPhoto = Math.max(1, nextPhotoAt - SystemClock.elapsedRealtime());
            delay = Math.min(delay, untilPhoto);
        }
        photoHandler.postDelayed(photoTicker, delay);
    }

    /** Stops background work while keeping the current frame ready for instant re-entry. */
    private void pausePhotoSlideshow() {
        photoHandler.removeCallbacks(photoTicker);
        photoHandler.removeCallbacks(photoPanTicker);
        photoGeneration++;
        photoLoading = false;
        photoScanInProgress = false;
        if (photoImage != null) {
            photoImage.animate().cancel();
            photoImage.setAlpha(1.0f);
            photoImage.setRotationY(0.0f);
        }
        if (pendingPhotoBitmap != null && !pendingPhotoBitmap.isRecycled()) {
            pendingPhotoBitmap.recycle();
            pendingPhotoBitmap = null;
        }
    }

    /** Releases the retained frame when the activity is actually being destroyed. */
    private void stopPhotoSlideshow() {
        pausePhotoSlideshow();
        if (photoImage != null) {
            photoImage.setImageDrawable(null);
        }
        releaseSoftBackground();
        if (photoBitmap != null && !photoBitmap.isRecycled()) {
            photoBitmap.recycle();
            photoBitmap = null;
        }
    }

    private void pausePhotoForDisplayOff() {
        photoHandler.removeCallbacks(photoTicker);
        stopPhotoPan();
        if (photoImage != null) {
            photoImage.animate().cancel();
            photoImage.setAlpha(1.0f);
            photoImage.setRotationY(0.0f);
        }
        releaseSoftBackground();
    }

    private void resumePhotoAfterDisplayOn() {
        if (!activityResumed || (currentPage != PHOTO_PAGE && currentPage != WORK_PHOTO_PAGE)) {
            return;
        }
        if (photoBitmap == null) {
            startPhotoSlideshow();
            return;
        }
        updatePhotoClock();
        applyPhotoPresentation(photoBitmap);
        nextPhotoAt = SystemClock.elapsedRealtime() + getPhotoIntervalMs();
        schedulePhotoTicker();
    }

    private void applyPcDisplayState(boolean displayOn) {
        if (pcDisplayOn == displayOn) {
            return;
        }
        pcDisplayOn = displayOn;
        updateScreenKeepAwake();
        if (displayOn) {
            wakePhoneScreen();
            resumePhotoAfterDisplayOn();
        } else {
            pausePhotoForDisplayOff();
        }
    }

    private void updateScreenKeepAwake() {
        if (pcDisplayOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void wakePhoneScreen() {
        try {
            if (displayWakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (powerManager == null) {
                    return;
                }
                displayWakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "QuietPanel:pc-display");
            }
            if (!displayWakeLock.isHeld()) {
                displayWakeLock.acquire(2000);
            }
        } catch (Exception ignored) {
        }
    }

    private void startPhotoPan() {
        photoHandler.removeCallbacks(photoPanTicker);
        if (lowPowerEnabled || (clockEnvironment != null && clockEnvironment.isSleeping())
                || softBackgroundEnabled || photoImage == null || photoBitmap == null
                || photoImage.getWidth() <= 0 || photoImage.getHeight() <= 0) {
            return;
        }
        photoPanReverse = !photoPanReverse;
        photoPanStartedAt = SystemClock.elapsedRealtime();
        applyPhotoPan(0.0f);
        photoHandler.postDelayed(photoPanTicker, PHOTO_PAN_FRAME_MS);
    }

    private void stopPhotoPan() {
        photoHandler.removeCallbacks(photoPanTicker);
    }

    private void applyPhotoPan(float progress) {
        if (photoImage == null || photoBitmap == null) {
            return;
        }
        int viewWidth = photoImage.getWidth()
                - photoImage.getPaddingLeft() - photoImage.getPaddingRight();
        int viewHeight = photoImage.getHeight()
                - photoImage.getPaddingTop() - photoImage.getPaddingBottom();
        int bitmapWidth = photoBitmap.getWidth();
        int bitmapHeight = photoBitmap.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return;
        }

        float scale = Math.max(
                (float) viewWidth / bitmapWidth,
                (float) viewHeight / bitmapHeight);
        float scaledWidth = bitmapWidth * scale;
        float scaledHeight = bitmapHeight * scale;
        float overflowX = Math.max(0.0f, scaledWidth - viewWidth);
        float overflowY = Math.max(0.0f, scaledHeight - viewHeight);
        float smoothProgress = progress * progress * (3.0f - 2.0f * progress);
        float startPosition = (1.0f - PHOTO_PAN_TRAVEL_FRACTION) / 2.0f;
        float travelProgress = photoPanReverse ? 1.0f - smoothProgress : smoothProgress;
        float position = startPosition + PHOTO_PAN_TRAVEL_FRACTION * travelProgress;
        float focusX = smartFocusEnabled ? photoFocusX : 0.5f;
        float focusY = smartFocusEnabled ? photoFocusY : 0.5f;

        float translateX;
        float translateY;
        if (overflowX >= overflowY) {
            translateX = -overflowX * (focusX * 0.4f + position * 0.6f);
            translateY = -overflowY * focusY;
        } else {
            translateX = -overflowX * focusX;
            translateY = -overflowY * (focusY * 0.4f + position * 0.6f);
        }

        photoMatrix.reset();
        photoMatrix.setScale(scale, scale);
        photoMatrix.postTranslate(translateX, translateY);
        photoImage.setImageMatrix(photoMatrix);
    }

    private void updatePhotoClock() {
        photoClockDate.setTime(System.currentTimeMillis());
        if (photoTime != null) {
            photoTime.setText(photoTimeFormat.format(photoClockDate));
        }
        if (photoDate != null) {
            SimpleDateFormat format = PhotoFontManager.usesEnglishDate(dateFontStyle)
                    ? photoDateEnglishFormat : photoDateChineseFormat;
            photoDate.setText(format.format(photoClockDate));
        }
        if (daylightProgressView != null && daylightProgressView.getVisibility() == View.VISIBLE) {
            daylightProgressView.setNow(photoClockDate.getTime());
        }
    }

    private void applyWeather(JSONObject weather) {
        if (weatherRow == null) return;
        android.content.SharedPreferences preferences = getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, MODE_PRIVATE);
        if (!preferences.getBoolean(PhotoFolderActivity.WEATHER_ENABLED, true)
                || weather.optBoolean("stale", false)) {
            hideWeather();
            return;
        }
        double temperature = weather.optDouble("temperature_c", Double.NaN);
        if (Double.isNaN(temperature)) {
            hideWeather();
            return;
        }
        weatherIcon.setWeather(weather.optInt("code", 0), weather.optBoolean("is_day", true));
        String temperatureText = String.format(Locale.US, "%.0f°C", temperature);
        SpannableString styledTemperature = new SpannableString(temperatureText);
        int degreeIndex = temperatureText.indexOf('\u00B0');
        if (PhotoFontManager.isStoropiaStyle(weatherFontStyle) && degreeIndex >= 0) {
            styledTemperature.setSpan(new FixedTypefaceSpan(
                    PhotoFontManager.storopiaDegreeFallback(
                            this,
                            weatherFontStyle == PhotoFontManager.STYLE_STOROPIA_SYNTHETIC_BOLD)), degreeIndex,
                    degreeIndex + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        weatherTemperature.setText(styledTemperature);
        boolean showLocation = preferences.getBoolean(
                PhotoFolderActivity.WEATHER_SHOW_LOCATION, false);
        weatherLocation.setText(showLocation ? weather.optString("location", "") : "");
        weatherLocation.setVisibility(showLocation ? View.VISIBLE : View.GONE);
        boolean compact = preferences.getBoolean(
                PhotoFolderActivity.WEATHER_COMPACT_MODE, true) && !showLocation;
        applyWeatherLayout(compact);
        weatherRow.setVisibility(View.VISIBLE);
        applyDaylight(weather);
    }

    private void applyWeatherLayout(boolean compact) {
        if (weatherRow == null || dateRow == null || clockPanel == null) return;
        ViewGroup currentParent = weatherRow.getParent() instanceof ViewGroup
                ? (ViewGroup) weatherRow.getParent() : null;
        ViewGroup targetParent = compact ? dateRow : clockPanel;
        if (currentParent != targetParent) {
            if (currentParent != null) currentParent.removeView(weatherRow);
            if (compact) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                params.gravity = Gravity.BOTTOM;
                params.setMargins(0, 0, dp(8), 0);
                dateRow.addView(weatherRow, 0, params);
            } else {
                int weatherIndex = clockPanel.indexOfChild(dateRow) + 1;
                clockPanel.addView(weatherRow, weatherIndex, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
        weatherCompactLayout = compact;
        setClockDisplayScale(effectiveClockTextScale);
    }

    private void hideWeather() {
        if (weatherRow != null) {
            weatherRow.setVisibility(View.GONE);
        }
        if (daylightPanel != null) {
            daylightPanel.setVisibility(View.GONE);
        }
    }

    private void applyDaylight(JSONObject weather) {
        if (daylightPanel == null || daylightProgressView == null || daylightLabel == null) {
            return;
        }
        android.content.SharedPreferences preferences = getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, MODE_PRIVATE);
        if (!preferences.getBoolean(PhotoFolderActivity.WEATHER_DAYLIGHT_ENABLED, true)) {
            daylightPanel.setVisibility(View.GONE);
            return;
        }
        long sunriseAtMs = weather.optLong("sunrise_at_ms", -1L);
        long sunsetAtMs = weather.optLong("sunset_at_ms", -1L);
        if (sunriseAtMs <= 0L || sunsetAtMs <= sunriseAtMs) {
            daylightPanel.setVisibility(View.GONE);
            return;
        }
        daylightProgressView.setTimes(sunriseAtMs, sunsetAtMs);
        daylightProgressView.setNow(System.currentTimeMillis());
        daylightLabel.setText("日出 " + weatherSunTimeFormat.format(new Date(sunriseAtMs))
                + "　日落 " + weatherSunTimeFormat.format(new Date(sunsetAtMs)));
        daylightPanel.setVisibility(View.VISIBLE);
    }

    private void updateAlarmIndicator() {
        if (alarmRow == null) return;
        String nextAlarm = AlarmHelper.getNextAlarmTimeString(this);
        if (nextAlarm == null) {
            alarmRow.setVisibility(View.GONE);
        } else {
            alarmTimeText.setText(nextAlarm);
            alarmRow.setVisibility(View.VISIBLE);
        }
    }

    private void updateModePillText(int mode) {
        if (modePillButton == null) return;
        if (mode == TransportServer.MODE_ADB) {
            modePillButton.setText("ADB");
        } else if (mode == TransportServer.MODE_WIFI) {
            modePillButton.setText("WiFi");
        } else if (mode == TransportServer.MODE_BT) {
            modePillButton.setText("BT");
        } else {
            modePillButton.setText("AUTO");
        }
    }

    private Set<String> getSelectedPhotoFolders() {
        Set<String> savedFolders = getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, MODE_PRIVATE)
                .getStringSet(PhotoFolderActivity.PHOTO_FOLDERS, null);
        return savedFolders == null
                ? new HashSet<String>() : new HashSet<String>(savedFolders);
    }

    private boolean isPrivateAlbumEnabled() {
        return getSharedPreferences(PhotoFolderActivity.PREFERENCES, MODE_PRIVATE)
                .getBoolean(PhotoFolderActivity.PRIVATE_ALBUM_ENABLED, false);
    }

    /** Null means all private-album folders (the legacy/default behavior). */
    private Set<String> getSelectedPrivateAlbumFolders() {
        android.content.SharedPreferences preferences = getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, MODE_PRIVATE);
        if (!preferences.getBoolean(PhotoFolderActivity.PRIVATE_ALBUM_FOLDERS_CUSTOMIZED,
                false)) {
            return null;
        }
        Set<String> saved = preferences.getStringSet(
                PhotoFolderActivity.PRIVATE_ALBUM_FOLDERS, null);
        return saved == null ? new HashSet<String>() : new HashSet<String>(saved);
    }

    private String buildPhotoFolderSignature(Set<String> folders) {
        if (folders.isEmpty()) {
            return isPrivateAlbumEnabled() ? "@private-album" : "@default";
        }
        List<String> orderedFolders = new ArrayList<String>(folders);
        Collections.sort(orderedFolders);
        String signature = TextUtils.join("\n", orderedFolders);
        if (!isPrivateAlbumEnabled()) {
            return signature;
        }
        StringBuilder result = new StringBuilder(signature).append("\n@private-album");
        Set<String> privateFolders = getSelectedPrivateAlbumFolders();
        if (privateFolders != null) {
            List<String> orderedPrivateFolders = new ArrayList<String>(privateFolders);
            Collections.sort(orderedPrivateFolders);
            if (orderedPrivateFolders.isEmpty()) {
                result.append("\n@private-folders-empty");
            }
            for (String folder : orderedPrivateFolders) {
                result.append("\n@private-folder=").append(folder);
            }
        }
        return result.toString();
    }

    private void refreshPhotoFiles(
            final Set<String> selectedFolders, final String folderSignature) {
        photoFiles.clear();
        photoIndex = 0;
        photoFailures = 0;
        photoCatalogLoaded = false;
        photoScanInProgress = true;
        photoFolderSignature = folderSignature;
        final int scanGeneration = photoGeneration;
        showPhotoStatus("正在掃描相簿資料夾…", SECONDARY);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<PhotoSource> scannedPhotos = new ArrayList<PhotoSource>();
                final boolean[] firstPhotoPublished = new boolean[] { false };
                final String scanError = collectSelectedPhotoFiles(selectedFolders,
                        isPrivateAlbumEnabled(), scannedPhotos,
                        new PhotoDiscovery() {
                            @Override
                            public void onPhotoDiscovered(final PhotoSource source) {
                                if (firstPhotoPublished[0]) {
                                    return;
                                }
                                firstPhotoPublished[0] = true;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (scanGeneration != photoGeneration || !photoFiles.isEmpty()) {
                                            return;
                                        }
                                        photoFiles.add(source);
                                        photoIndex = 0;
                                        showPhotoStatus("相簿正在建立完整索引…", SECONDARY);
                                        loadNextPhoto();
                                    }
                                });
                            }
                        });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (scanGeneration != photoGeneration) {
                            return;
                        }
                        photoScanInProgress = false;
                        photoCatalogLoaded = true;
                        photoFiles.clear();
                        photoFiles.addAll(scannedPhotos);
                        Collections.shuffle(photoFiles);
                        if (photoFiles.isEmpty()) {
                            showPhotoStatus(scanError == null
                                    ? "選擇的資料夾沒有可播放照片\n請點一下畫面，再選擇「相簿資料夾」"
                                    : scanError, scanError == null ? SECONDARY : WARNING);
                        } else {
                            if (photoBitmap == null && !photoLoading) {
                                loadNextPhoto();
                            }
                        }
                        schedulePhotoTicker();
                    }
                });
            }
        }, "QuietPanel-photo-scan").start();
    }

    private String collectSelectedPhotoFiles(
            Set<String> selectedFolders, boolean includePrivateAlbum,
            List<PhotoSource> output, PhotoDiscovery discovery) {
        if (selectedFolders.isEmpty() && !includePrivateAlbum) {
            File defaultDirectory = new File(
                    Environment.getExternalStorageDirectory(), PHOTO_DIRECTORY);
            if (!defaultDirectory.exists() && !defaultDirectory.mkdirs()) {
                return "無法建立預設照片資料夾\n" + defaultDirectory.getAbsolutePath();
            }
            selectedFolders.add(defaultDirectory.getAbsolutePath());
        }

        Set<String> visitedDirectories = new HashSet<String>();
        Set<String> discoveredPhotos = new LinkedHashSet<String>();
        if (includePrivateAlbum) {
            collectPrivateAlbumPhotos(discoveredPhotos, output, discovery);
        }
        for (String path : selectedFolders) {
            if (path.startsWith("content://") && android.os.Build.VERSION.SDK_INT >= 21) {
                collectDocumentTreePhotos(Uri.parse(path), visitedDirectories,
                        discoveredPhotos, output, discovery, 0);
            } else {
                collectPhotoFiles(
                        new File(path), visitedDirectories, discoveredPhotos, output, discovery, 0);
            }
            if (discoveredPhotos.size() >= MAX_PHOTO_FILES) {
                break;
            }
        }
        return null;
    }

    private void collectPrivateAlbumPhotos(Set<String> discoveredPhotos,
            List<PhotoSource> output, PhotoDiscovery discovery) {
        Cursor cursor = null;
        Set<String> selectedPrivateFolders = getSelectedPrivateAlbumFolders();
        try {
            cursor = getContentResolver().query(PRIVATE_ALBUM_URI,
                    new String[] { PRIVATE_ALBUM_CONTENT_URI, PRIVATE_ALBUM_SOURCE_FOLDER },
                    null, null, null);
            if (cursor == null) {
                return;
            }
            int uriColumn = cursor.getColumnIndex(PRIVATE_ALBUM_CONTENT_URI);
            int folderColumn = cursor.getColumnIndex(PRIVATE_ALBUM_SOURCE_FOLDER);
            while (uriColumn >= 0 && cursor.moveToNext()
                    && discoveredPhotos.size() < MAX_PHOTO_FILES) {
                if (selectedPrivateFolders != null) {
                    String folder = folderColumn >= 0 ? cursor.getString(folderColumn) : null;
                    if (folder == null || !selectedPrivateFolders.contains(folder)) {
                        continue;
                    }
                }
                String value = cursor.getString(uriColumn);
                if (value == null || value.length() == 0 || !discoveredPhotos.add(value)) {
                    continue;
                }
                PhotoSource source = PhotoSource.fromUri(Uri.parse(value));
                output.add(source);
                discovery.onPhotoDiscovered(source);
            }
        } catch (RuntimeException ignored) {
            // The private album app is optional and may not be installed.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void registerPrivateAlbumObserver() {
        if (privateAlbumObserverRegistered) {
            return;
        }
        privateAlbumObserver = new ContentObserver(photoHandler) {
            @Override
            public void onChange(boolean selfChange) {
                photoGeneration++;
                photoCatalogLoaded = false;
                photoScanInProgress = false;
                photoFolderSignature = "";
                if (activityResumed && isPrivateAlbumEnabled()
                        && (currentPage == PHOTO_PAGE || currentPage == WORK_PHOTO_PAGE)) {
                    startPhotoSlideshow();
                }
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    PRIVATE_ALBUM_URI, true, privateAlbumObserver);
            privateAlbumObserverRegistered = true;
        } catch (RuntimeException ignored) {
            privateAlbumObserver = null;
        }
    }

    private void unregisterPrivateAlbumObserver() {
        if (!privateAlbumObserverRegistered || privateAlbumObserver == null) {
            return;
        }
        try {
            getContentResolver().unregisterContentObserver(privateAlbumObserver);
        } catch (RuntimeException ignored) {
        }
        privateAlbumObserverRegistered = false;
        privateAlbumObserver = null;
    }

    private void collectPhotoFiles(
            File directory,
            Set<String> visitedDirectories,
            Set<String> discoveredPhotos,
            List<PhotoSource> output,
            PhotoDiscovery discovery,
            int depth) {
        if (directory == null || !directory.isDirectory()
                || depth > 12 || discoveredPhotos.size() >= MAX_PHOTO_FILES) {
            return;
        }
        String directoryPath = canonicalPath(directory);
        if (!visitedDirectories.add(directoryPath)) {
            return;
        }
        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (discoveredPhotos.size() >= MAX_PHOTO_FILES) {
                return;
            }
            if (entry.isDirectory() && !entry.getName().startsWith(".")) {
                collectPhotoFiles(
                        entry, visitedDirectories, discoveredPhotos, output, discovery, depth + 1);
            } else if (entry.isFile() && isSupportedPhoto(entry.getName())) {
                String photoPath = canonicalPath(entry);
                if (discoveredPhotos.add(photoPath)) {
                    PhotoSource source = PhotoSource.fromFile(entry, photoPath);
                    output.add(source);
                    discovery.onPhotoDiscovered(source);
                }
            }
        }
    }

    /**
     * Reads a folder selected through Android's system picker. This is the
     * only reliable way to access removable SD cards on Fire OS and Android
     * scoped-storage devices.
     */
    @android.annotation.TargetApi(21)
    private void collectDocumentTreePhotos(
            Uri treeUri,
            Set<String> visitedDirectories,
            Set<String> discoveredPhotos,
            List<PhotoSource> output,
            PhotoDiscovery discovery,
            int depth) {
        if (depth > 12 || discoveredPhotos.size() >= MAX_PHOTO_FILES) {
            return;
        }
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            collectDocumentDirectoryPhotos(treeUri, rootDocument, visitedDirectories,
                    discoveredPhotos, output, discovery, depth);
        } catch (Exception ignored) {
            // The picker can revoke a removable card while the app is open.
        }
    }

    @android.annotation.TargetApi(21)
    private void collectDocumentDirectoryPhotos(
            Uri treeUri,
            Uri directoryUri,
            Set<String> visitedDirectories,
            Set<String> discoveredPhotos,
            List<PhotoSource> output,
            PhotoDiscovery discovery,
            int depth) {
        if (depth > 12 || discoveredPhotos.size() >= MAX_PHOTO_FILES) {
            return;
        }
        String directoryId;
        try {
            directoryId = DocumentsContract.getDocumentId(directoryUri);
        } catch (Exception ignored) {
            return;
        }
        String visitKey = treeUri.toString() + "|" + directoryId;
        if (!visitedDirectories.add(visitKey)) {
            return;
        }

        Cursor cursor = null;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId);
            String[] projection = new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            cursor = getContentResolver().query(children, projection, null, null, null);
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int typeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            List<Uri> childDirectories = new ArrayList<Uri>();
            while (cursor.moveToNext() && discoveredPhotos.size() < MAX_PHOTO_FILES) {
                String childId = cursor.getString(idColumn);
                String childName = cursor.getString(nameColumn);
                String mimeType = cursor.getString(typeColumn);
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    // Finish the selected folder's own photos before asking
                    // the provider for each child directory.  On Fire OS this
                    // avoids an early deep branch delaying every usable image.
                    childDirectories.add(childUri);
                } else if (childName != null && isSupportedPhoto(childName)
                        && discoveredPhotos.add(childUri.toString())) {
                    PhotoSource source = PhotoSource.fromUri(childUri);
                    output.add(source);
                    discovery.onPhotoDiscovered(source);
                }
            }
            for (Uri childDirectory : childDirectories) {
                if (discoveredPhotos.size() >= MAX_PHOTO_FILES) {
                    return;
                }
                collectDocumentDirectoryPhotos(treeUri, childDirectory, visitedDirectories,
                        discoveredPhotos, output, discovery, depth + 1);
            }
        } catch (Exception ignored) {
            // A malformed provider or a removed card must not stop other folders.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    private boolean isSupportedPhoto(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png");
    }

    private void loadNextPhoto() {
        if (photoLoading || photoFiles.isEmpty()) {
            return;
        }
        if (photoIndex >= photoFiles.size()) {
            Collections.shuffle(photoFiles);
            photoIndex = 0;
        }

        final PhotoSource source = photoFiles.get(photoIndex++);
        final int generation = photoGeneration;
        final boolean analyzeColor = adaptiveColorEnabled;
        final boolean analyzeFocus = smartFocusEnabled;
        photoLoading = true;
        nextPhotoAt = SystemClock.elapsedRealtime() + getPhotoIntervalMs();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = decodePhoto(source);
                final PhotoEffects.Analysis analysis = analyzeColor || analyzeFocus
                        ? PhotoEffects.analyze(bitmap, analyzeColor, analyzeFocus) : null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != photoGeneration
                                || (currentPage != PHOTO_PAGE && currentPage != WORK_PHOTO_PAGE)
                                || !activityResumed) {
                            if (bitmap != null && !bitmap.isRecycled()) {
                                bitmap.recycle();
                            }
                            return;
                        }
                        photoLoading = false;
                        if (bitmap == null) {
                            photoFailures++;
                            if (photoFailures >= photoFiles.size()) {
                                photoFiles.clear();
                                showPhotoStatus("相簿中的圖片都無法讀取", WARNING);
                            } else {
                                loadNextPhoto();
                            }
                            schedulePhotoTicker();
                            return;
                        }
                        photoFailures = 0;
                        if (analysis != null) {
                            photoDominantColor = analysis.dominantColor;
                            photoFocusX = analysis.focusX;
                            photoFocusY = analysis.focusY;
                        }
                        applyClockBackground();
                        displayPhoto(bitmap);
                        schedulePhotoTicker();
                    }
                });
            }
        }, "QuietPanel-photo-decode").start();
    }

    private Bitmap decodePhoto(PhotoSource source) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream boundsInput = null;
        try {
            boundsInput = openPhotoInputStream(source);
            if (boundsInput == null) {
                return null;
            }
            BitmapFactory.decodeStream(boundsInput, null, bounds);
        } catch (Exception ignored) {
            return null;
        } finally {
            closeInputStream(boundsInput);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int targetWidth = getResources().getDisplayMetrics().widthPixels;
        int targetHeight = getResources().getDisplayMetrics().heightPixels;
        double scale = Math.min(
                (double) targetWidth / bounds.outWidth,
                (double) targetHeight / bounds.outHeight);
        double desiredWidth = Math.min(bounds.outWidth, bounds.outWidth * scale);
        double desiredHeight = Math.min(bounds.outHeight, bounds.outHeight * scale);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= desiredWidth
                && bounds.outHeight / (sample * 2) >= desiredHeight) {
            sample *= 2;
        }

        while (sample <= 64) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
            InputStream photoInput = null;
            try {
                photoInput = openPhotoInputStream(source);
                if (photoInput == null) {
                    return null;
                }
                Bitmap bitmap = BitmapFactory.decodeStream(photoInput, null, options);
                if (bitmap != null) {
                    return bitmap;
                }
            } catch (OutOfMemoryError oom) {
                System.gc();
            } catch (Exception ignored) {
                return null;
            } finally {
                closeInputStream(photoInput);
            }
            sample *= 2;
        }
        return null;
    }

    private InputStream openPhotoInputStream(PhotoSource source) throws Exception {
        return source.file != null
                ? new FileInputStream(source.file)
                : getContentResolver().openInputStream(source.uri);
    }

    private void closeInputStream(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (Exception ignored) {
        }
    }

    private void displayPhoto(final Bitmap bitmap) {
        photoStatus.setVisibility(View.GONE);
        if (photoBitmap == null) {
            installPhoto(bitmap);
            if (transition3dEnabled) {
                photoImage.setRotationY(-75.0f);
                photoImage.setAlpha(0.15f);
                photoImage.animate().rotationY(0.0f).alpha(1.0f).setDuration(900).start();
            } else {
                photoImage.setAlpha(0.0f);
                photoImage.animate().alpha(1.0f).setDuration(1800).start();
            }
            return;
        }

        if (pendingPhotoBitmap != null
                && pendingPhotoBitmap != bitmap
                && !pendingPhotoBitmap.isRecycled()) {
            pendingPhotoBitmap.recycle();
        }
        pendingPhotoBitmap = bitmap;
        final int generation = photoGeneration;
        stopPhotoPan();
        photoImage.animate().cancel();
        Runnable swap = new Runnable() {
            @Override
            public void run() {
                if (!completePhotoSwap(bitmap, generation)) {
                    return;
                }
                if (transition3dEnabled) {
                    photoImage.setRotationY(-75.0f);
                    photoImage.animate().rotationY(0.0f).alpha(1.0f)
                            .setDuration(900).start();
                } else {
                    photoImage.animate().alpha(1.0f).setDuration(1800).start();
                }
            }
        };
        if (transition3dEnabled) {
            photoImage.animate().rotationY(75.0f).alpha(0.15f)
                    .setDuration(600).withEndAction(swap).start();
        } else {
            photoImage.animate().alpha(0.0f).setDuration(1200)
                    .withEndAction(swap).start();
        }
    }

    private boolean completePhotoSwap(Bitmap bitmap, int generation) {
        if (generation != photoGeneration
                || (currentPage != PHOTO_PAGE && currentPage != WORK_PHOTO_PAGE)
                || !activityResumed) {
            if (pendingPhotoBitmap == bitmap) {
                pendingPhotoBitmap = null;
            }
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
            return false;
        }
        Bitmap previous = photoBitmap;
        pendingPhotoBitmap = null;
        installPhoto(bitmap);
        if (previous != null && previous != bitmap && !previous.isRecycled()) {
            previous.recycle();
        }
        return true;
    }

    private void installPhoto(Bitmap bitmap) {
        photoBitmap = bitmap;
        photoImage.setImageBitmap(bitmap);
        applyPhotoPresentation(bitmap);
    }

    private void applyPhotoPresentation(Bitmap bitmap) {
        if (softBackgroundEnabled) {
            stopPhotoPan();
            photoImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            applySoftBackground(bitmap);
        } else {
            releaseSoftBackground();
            if (lowPowerEnabled) {
                stopPhotoPan();
                photoImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                photoImage.setScaleType(ImageView.ScaleType.MATRIX);
                startPhotoPan();
            }
        }
    }

    private void showPhotoStatus(String message, int color) {
        photoStatus.setText(message);
        photoStatus.setTextColor(color);
        photoStatus.setVisibility(View.VISIBLE);
    }

    private void displayApod(JSONObject metadata, Bitmap bitmap) {
        Bitmap previous = apodBitmap;
        apodBitmap = bitmap;
        displayedApodDate = metadata.optString("date", "");
        apodImage.setImageBitmap(bitmap);
        apodImageStatus.setText("NASA APOD  ·  已快取");
        apodImageStatus.setTextColor(ACCENT);
        apodTitle.setText(metadata.optString("title", "NASA 每日天文圖片"));

        StringBuilder details = new StringBuilder(displayedApodDate);
        String copyright = metadata.optString("copyright", "").trim();
        if (copyright.length() > 0) {
            details.append("  ·  © ").append(copyright);
        } else {
            details.append("  ·  NASA");
        }
        if ("video".equals(metadata.optString("mediaType", ""))) {
            details.append("  ·  VIDEO THUMBNAIL");
        }
        apodMeta.setText(details.toString());
        apodExplanation.setText(metadata.optString("explanation", "沒有圖片說明。"));

        if (previous != null && previous != bitmap && !previous.isRecycled()) {
            previous.recycle();
        }
    }

    private void showApodError(String message) {
        if (apodBitmap == null) {
            apodImageStatus.setText(message);
            apodImageStatus.setTextColor(WARNING);
        }
    }

    private Bitmap decodeApod(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int targetWidth = Math.max(480, getResources().getDisplayMetrics().widthPixels);
        int targetHeight = Math.max(320, getResources().getDisplayMetrics().heightPixels);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth
                && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void saveApodCache(JSONObject metadata, byte[] imageBytes) {
        try {
            FileOutputStream output = openFileOutput("apod-image.bin", Context.MODE_PRIVATE);
            try {
                output.write(imageBytes);
            } finally {
                output.close();
            }
            getSharedPreferences("apod", Context.MODE_PRIVATE)
                    .edit()
                    .putString("metadata", metadata.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private void loadApodCache() {
        try {
            String metadataText = getSharedPreferences("apod", Context.MODE_PRIVATE)
                    .getString("metadata", "");
            if (metadataText == null || metadataText.length() == 0) {
                return;
            }

            FileInputStream input = openFileInput("apod-image.bin");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[8192];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > 6 * 1024 * 1024) {
                        return;
                    }
                    output.write(buffer, 0, read);
                }
            } finally {
                input.close();
            }

            Bitmap bitmap = decodeApod(output.toByteArray());
            if (bitmap != null) {
                displayApod(new JSONObject(metadataText), bitmap);
            }
        } catch (Exception ignored) {
        }
    }

    private void setActionButtonsEnabled(boolean enabled) {
        for (Button button : actionButtons) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1.0f : 0.45f);
        }
    }

    private LinearLayout pageContainer() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(2), dp(2), dp(2), dp(2));
        return layout;
    }

    private LinearLayout metricRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private MetricCard addMetric(
            LinearLayout row,
            String label,
            String initialValue,
            int graphColor,
            boolean percentScale) {
        MetricCard card = new MetricCard(label, initialValue, graphColor, percentScale);
        row.addView(card.container, weightedCell());
        return card;
    }

    private LinearLayout.LayoutParams weightedCell() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        return params;
    }

    private TextView makeText(String text, float sizeSp, int color, int gravity) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(gravity | Gravity.CENTER_VERTICAL);
        return view;
    }

    private StateListDrawable makeButtonBackground(int color) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed }, rounded(lighten(color)));
        states.addState(new int[] {}, rounded(color));
        return states;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private int lighten(int color) {
        return Color.rgb(
                Math.min(255, Color.red(color) + 45),
                Math.min(255, Color.green(color) + 45),
                Math.min(255, Color.blue(color) + 45));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String format(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private final class MetricCard {
        final LinearLayout container;
        final TextView label;
        final TextView value;
        final HistoryGraphView history;

        MetricCard(
                String labelText,
                String initialValue,
                int graphColor,
                boolean percentScale) {
            container = new LinearLayout(MainActivity.this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(Gravity.CENTER);
            container.setBackground(rounded(PANEL));

            label = makeText(labelText, 14, SECONDARY, Gravity.CENTER);
            value = makeText(initialValue, 27, PRIMARY, Gravity.CENTER);
            value.setTypeface(Typeface.DEFAULT_BOLD);
            history = new HistoryGraphView(MainActivity.this, graphColor, percentScale);
            container.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 2));
            container.addView(value, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 3));
            container.addView(history, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 3));
        }
    }

    private final class DiskRow {
        final LinearLayout container;
        final TextView name;
        final TextView usage;
        final TextView remaining;
        final FrameLayout progressTrack;
        final View progressFill;

        DiskRow() {
            container = new LinearLayout(MainActivity.this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp(18), dp(5), dp(18), dp(5));
            container.setBackground(rounded(Color.rgb(18, 25, 32)));

            LinearLayout line = new LinearLayout(MainActivity.this);
            name = makeText("--", 18, PRIMARY, Gravity.START);
            remaining = makeText("等待資料", 14, SECONDARY, Gravity.END);
            name.setIncludeFontPadding(false);
            remaining.setIncludeFontPadding(false);
            line.addView(name, new LinearLayout.LayoutParams(0, dp(24), 1));
            line.addView(remaining, new LinearLayout.LayoutParams(0, dp(24), 1));
            container.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

            usage = makeText("等待資料", 13, SECONDARY, Gravity.START);
            usage.setIncludeFontPadding(false);
            container.addView(usage, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));

            progressTrack = new FrameLayout(MainActivity.this);
            progressTrack.setBackground(rounded(Color.rgb(48, 61, 73)));
            progressFill = new View(MainActivity.this);
            progressFill.setBackground(rounded(ACCENT));
            progressTrack.addView(progressFill, new FrameLayout.LayoutParams(0, dp(6)));
            container.addView(progressTrack, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));

            LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
            outer.setMargins(dp(5), dp(2), dp(5), dp(2));
            container.setLayoutParams(outer);
        }

        boolean update(JSONObject disk) {
            if (disk == null) {
                name.setText("--");
                usage.setText("沒有更多磁碟");
                usage.setTextColor(SECONDARY);
                remaining.setText("");
                setProgress(0.0, ACCENT);
                return false;
            }

            double total = disk.optDouble("totalGB", 0);
            double used = disk.optDouble("usedGB", 0);
            double percent = total <= 0 ? 0 : used / total * 100.0;
            boolean lowSpace = percent >= 90.0;
            name.setText(disk.optString("name", "?"));
            double free = Math.max(0.0, total - used);
            usage.setText("已用 " + format(used) + " / " + format(total) + " GB  ·  "
                    + format(percent) + "%" + (lowSpace ? "  ·  空間不足" : ""));
            usage.setTextColor(lowSpace ? WARNING : SECONDARY);
            remaining.setText("剩餘 " + format(free) + " GB");
            remaining.setTextColor(lowSpace ? WARNING : PRIMARY);
            setProgress(percent, lowSpace ? WARNING : ACCENT);
            return lowSpace;
        }

        private void setProgress(final double percent, int color) {
            progressFill.setBackground(rounded(color));
            progressTrack.post(new Runnable() {
                @Override
                public void run() {
                    FrameLayout.LayoutParams params =
                            (FrameLayout.LayoutParams) progressFill.getLayoutParams();
                    params.width = Math.max(0, Math.min(progressTrack.getWidth(),
                            (int) Math.round(progressTrack.getWidth() * percent / 100.0)));
                    progressFill.setLayoutParams(params);
                }
            });
        }
    }

    private static final class MacroSpec {
        final String label;
        final String action;
        final int color;
        final boolean longPress;

        MacroSpec(String label, String action, int color) {
            this(label, action, color, false);
        }

        MacroSpec(String label, String action, int color, boolean longPress) {
            this.label = label;
            this.action = action;
            this.color = color;
            this.longPress = longPress;
        }
    }
}
