package com.quietpanel.client;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
        implements TransportServer.Listener, ApodServer.Listener {
    private static final int BACKGROUND = Color.rgb(11, 15, 20);
    private static final int PANEL = Color.rgb(24, 31, 40);
    private static final int PRIMARY = Color.rgb(242, 238, 230);
    private static final int SECONDARY = Color.rgb(143, 152, 163);
    private static final int ACCENT = Color.rgb(72, 184, 199);
    private static final int WARNING = Color.rgb(239, 108, 108);
    private static final int PHOTO_PAGE = 2;
    private static final int PAGE_COUNT = 6;
    private static final int DEFAULT_PHOTO_INTERVAL_SECONDS = 45;
    private static final int MIN_PHOTO_INTERVAL_SECONDS = 10;
    private static final int MAX_PHOTO_INTERVAL_SECONDS = 300;
    private static final long PHOTO_PAN_FRAME_MS = 67;
    private static final float PHOTO_PAN_TRAVEL_FRACTION = 0.20f;
    private static final String PHOTO_DIRECTORY = "QuietPanel/Photos";
    private static final int MAX_PHOTO_FILES = 10000;

    private final List<Button> actionButtons = new ArrayList<Button>();
    private final DiskRow[] diskRows = new DiskRow[4];
    private TransportServer transport;
    private ApodServer apodServer;
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
    private ImageView photoImage;
    private TextView photoStatus;
    private TextView photoTime;
    private TextView photoDate;
    private Button photoSettingsButton;
    private LinearLayout clockPanel;
    private FrameLayout photoPage;
    private Bitmap photoBitmap;
    private Bitmap pendingPhotoBitmap;
    private final List<File> photoFiles = new ArrayList<File>();
    private final Handler photoHandler = new Handler();
    private final Matrix photoMatrix = new Matrix();
    private final SimpleDateFormat photoTimeFormat =
            new SimpleDateFormat("HH:mm", Locale.TAIWAN);
    private final SimpleDateFormat photoDateFormat =
            new SimpleDateFormat("M月d日 EEEE", Locale.TAIWAN);
    private final Date photoClockDate = new Date();
    private float clockDownX;
    private float clockDownY;
    private int clockDownLeft;
    private int clockDownTop;
    private int photoIndex;
    private int photoFailures;
    private int photoGeneration;
    private boolean photoLoading;
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
    private long highCpuStartedAt = -1;
    private boolean cpuWarning;
    private boolean diskWarning;
    private final boolean[] pageEnabled = { true, true, true, true, true, true };

    private final Runnable photoTicker = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed || !pcDisplayOn || currentPage != PHOTO_PAGE) {
                return;
            }
            updatePhotoClock();
            if (!photoLoading && !photoFiles.isEmpty()
                    && SystemClock.elapsedRealtime() >= nextPhotoAt) {
                loadNextPhoto();
            }
            photoHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable photoPanTicker = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed || !pcDisplayOn || currentPage != PHOTO_PAGE || photoBitmap == null) {
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
        updateScreenKeepAwake();
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(buildInterface());
        setActionButtonsEnabled(false);
        loadApodCache();

        transport = new TransportServer(this);
        transport.start();
        apodServer = new ApodServer(this);
        apodServer.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        applyPhotoSettings();
        if (pcDisplayOn && currentPage == PHOTO_PAGE) {
            startPhotoSlideshow();
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        stopPhotoSlideshow();
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
    public void onConnectionChanged(final boolean connected, final String detail) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectionText.setText(connected ? "USB LIVE  ·  " + detail : detail);
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
        TextView title = makeText("QUIETPANEL  v6.6.0", 22, PRIMARY, Gravity.START);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        connectionText = makeText("啟動連線服務…", 13, SECONDARY, Gravity.END);
        appHeader.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1));
        appHeader.addView(connectionText, new LinearLayout.LayoutParams(0, dp(54), 1));
        appRoot.addView(appHeader, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        pager = new SwipePager(this);
        pager.addView(buildSystemPage());
        pager.addView(buildStoragePage());
        pager.addView(buildPhotoPage());
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
        final FrameLayout page = new FrameLayout(this);
        photoPage = page;
        page.setBackgroundColor(Color.BLACK);
        page.setClickable(true);
        page.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showPhotoSettingsButton();
            }
        });

        photoImage = new ImageView(this);
        photoImage.setScaleType(ImageView.ScaleType.MATRIX);
        page.addView(photoImage, new FrameLayout.LayoutParams(
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
        page.addView(photoStatus, statusParams);

        clockPanel = new LinearLayout(this);
        clockPanel.setOrientation(LinearLayout.VERTICAL);
        clockPanel.setGravity(Gravity.RIGHT);
        clockPanel.setPadding(dp(18), dp(10), dp(18), dp(12));
        clockPanel.setBackground(rounded(Color.argb(105, 0, 0, 0)));
        clockPanel.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        clockDownX = event.getRawX();
                        clockDownY = event.getRawY();
                        clockDownLeft = clockPanel.getLeft();
                        clockDownTop = clockPanel.getTop();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        moveClockPanel(
                                clockDownLeft + Math.round(event.getRawX() - clockDownX),
                                clockDownTop + Math.round(event.getRawY() - clockDownY));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveClockPosition();
                        return true;
                    default:
                        return true;
                }
            }
        });

        photoTime = makeText("", 64, Color.WHITE, Gravity.RIGHT);
        photoTime.setTypeface(Typeface.DEFAULT_BOLD);
        photoTime.setIncludeFontPadding(false);
        photoTime.setShadowLayer(dp(3), dp(1), dp(1), Color.BLACK);
        clockPanel.addView(photoTime, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        photoDate = makeText("", 24, Color.WHITE, Gravity.RIGHT);
        photoDate.setIncludeFontPadding(false);
        photoDate.setShadowLayer(dp(2), dp(1), dp(1), Color.BLACK);
        clockPanel.addView(photoDate, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        page.addView(clockPanel, clockParams);
        page.post(new Runnable() {
            @Override
            public void run() {
                applyClockPosition();
                applyPhotoSettings();
            }
        });

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
        page.addView(photoSettingsButton, folderButtonParams);
        updatePhotoClock();
        applyPhotoSettings();
        return page;
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
        long id = transport == null ? -1 : transport.sendAction(macro.action);
        if (id < 0) {
            setActionStatus("尚未連線，指令未送出", Color.rgb(239, 108, 108));
        } else {
            setActionStatus("已送出  " + macro.label.replace("\n（長按）", ""), SECONDARY);
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
        double cpu = system.optDouble("cpuPercent", 0);
        double memory = system.optDouble("ramPercent", 0);
        double download = system.optDouble("networkDownMBps", 0);
        double upload = system.optDouble("networkUpMBps", 0);
        double diskRead = system.optDouble("diskReadMBps", 0);
        double diskWrite = system.optDouble("diskWriteMBps", 0);

        cpuValue.setText(format(cpu) + " %");
        memoryValue.setText(format(memory) + " %");
        downloadValue.setText(format(download) + " MB/s");
        uploadValue.setText(format(upload) + " MB/s");
        diskReadValue.setText(format(diskRead) + " MB/s");
        diskWriteValue.setText(format(diskWrite) + " MB/s");
        cpuHistory.addSample(cpu);
        memoryHistory.addSample(memory);
        downloadHistory.addSample(download);
        uploadHistory.addSample(upload);
        diskReadHistory.addSample(diskRead);
        diskWriteHistory.addSample(diskWrite);
        updateCpuWarning(cpu);
    }

    private void updateDisks(JSONArray disks) {
        boolean warning = false;
        for (int i = 0; i < diskRows.length; i++) {
            JSONObject disk = disks == null ? null : disks.optJSONObject(i);
            warning |= diskRows[i].update(disk);
        }
        if (diskWarning != warning) {
            diskWarning = warning;
            updatePageIndicator();
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
        if (currentPage == PHOTO_PAGE) {
            stopPhotoSlideshow();
            hidePhotoFolderButtonImmediately();
        }
        currentPage = requestedPage;
        pager.setDisplayedChild(currentPage);
        boolean photoFullScreen = currentPage == PHOTO_PAGE;
        appHeader.setVisibility(photoFullScreen ? View.GONE : View.VISIBLE);
        pageIndicator.setVisibility(photoFullScreen ? View.GONE : View.VISIBLE);
        if (photoFullScreen) {
            appRoot.setPadding(0, 0, 0, 0);
        } else {
            appRoot.setPadding(dp(12), dp(8), dp(12), dp(6));
        }
        if (currentPage == PHOTO_PAGE && activityResumed && pcDisplayOn) {
            startPhotoSlideshow();
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
        if (photoPage == null || clockPanel == null) {
            return;
        }
        int maxLeft = Math.max(0, photoPage.getWidth() - clockPanel.getWidth());
        int maxTop = Math.max(0, photoPage.getHeight() - clockPanel.getHeight());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) clockPanel.getLayoutParams();
        params.leftMargin = Math.max(0, Math.min(left, maxLeft));
        params.topMargin = Math.max(0, Math.min(top, maxTop));
        clockPanel.setLayoutParams(params);
    }

    private void applyClockPosition() {
        if (photoPage == null || clockPanel == null
                || photoPage.getWidth() <= 0 || photoPage.getHeight() <= 0) {
            return;
        }
        android.content.SharedPreferences preferences = getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, MODE_PRIVATE);
        int maxLeft = Math.max(0, photoPage.getWidth() - clockPanel.getWidth());
        int maxTop = Math.max(0, photoPage.getHeight() - clockPanel.getHeight());
        float xRatio = preferences.getFloat(PhotoFolderActivity.CLOCK_X_RATIO, 1.0f);
        float yRatio = preferences.getFloat(PhotoFolderActivity.CLOCK_Y_RATIO, 1.0f);
        moveClockPanel(Math.round(maxLeft * clampRatio(xRatio)),
                Math.round(maxTop * clampRatio(yRatio)));
    }

    private void saveClockPosition() {
        if (photoPage == null || clockPanel == null) {
            return;
        }
        int maxLeft = Math.max(1, photoPage.getWidth() - clockPanel.getWidth());
        int maxTop = Math.max(1, photoPage.getHeight() - clockPanel.getHeight());
        getSharedPreferences(PhotoFolderActivity.PREFERENCES, MODE_PRIVATE)
                .edit()
                .putFloat(PhotoFolderActivity.CLOCK_X_RATIO,
                        clampRatio((float) clockPanel.getLeft() / maxLeft))
                .putFloat(PhotoFolderActivity.CLOCK_Y_RATIO,
                        clampRatio((float) clockPanel.getTop() / maxTop))
                .apply();
    }

    private float clampRatio(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private void applyPhotoSettings() {
        if (clockPanel == null) {
            return;
        }
        boolean showBackground = getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, MODE_PRIVATE)
                .getBoolean(PhotoFolderActivity.CLOCK_BACKGROUND, true);
        clockPanel.setBackground(showBackground ? rounded(Color.argb(105, 0, 0, 0)) : null);
        applyClockPosition();
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
        return Math.max(1000L, getPhotoIntervalMs() - 2000L);
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
        String[] labels = { "系統", "儲存", "相簿", "NASA", "MACRO", "快捷" };
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
        stopPhotoSlideshow();
        updatePhotoClock();
        refreshPhotoFiles();
        if (!photoFiles.isEmpty()) {
            loadNextPhoto();
        }
        photoHandler.postDelayed(photoTicker, 1000);
    }

    private void stopPhotoSlideshow() {
        photoHandler.removeCallbacks(photoTicker);
        photoHandler.removeCallbacks(photoPanTicker);
        photoGeneration++;
        photoLoading = false;
        if (photoImage != null) {
            photoImage.animate().cancel();
            photoImage.setImageDrawable(null);
            photoImage.setAlpha(1.0f);
        }
        if (photoBitmap != null && !photoBitmap.isRecycled()) {
            photoBitmap.recycle();
            photoBitmap = null;
        }
        if (pendingPhotoBitmap != null && !pendingPhotoBitmap.isRecycled()) {
            pendingPhotoBitmap.recycle();
            pendingPhotoBitmap = null;
        }
    }

    private void pausePhotoForDisplayOff() {
        photoHandler.removeCallbacks(photoTicker);
        stopPhotoPan();
        if (photoImage != null) {
            photoImage.animate().cancel();
            photoImage.setAlpha(1.0f);
        }
    }

    private void resumePhotoAfterDisplayOn() {
        if (!activityResumed || currentPage != PHOTO_PAGE) {
            return;
        }
        if (photoBitmap == null) {
            startPhotoSlideshow();
            return;
        }
        updatePhotoClock();
        nextPhotoAt = SystemClock.elapsedRealtime() + getPhotoIntervalMs();
        startPhotoPan();
        photoHandler.removeCallbacks(photoTicker);
        photoHandler.postDelayed(photoTicker, 1000);
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
        if (photoImage == null || photoBitmap == null
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
        int viewWidth = photoImage.getWidth();
        int viewHeight = photoImage.getHeight();
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

        float translateX;
        float translateY;
        if (overflowX >= overflowY) {
            translateX = -overflowX * position;
            translateY = -overflowY / 2.0f;
        } else {
            translateX = -overflowX / 2.0f;
            translateY = -overflowY * position;
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
            photoDate.setText(photoDateFormat.format(photoClockDate));
        }
    }

    private void refreshPhotoFiles() {
        photoFiles.clear();
        photoIndex = 0;
        photoFailures = 0;

        Set<String> selectedFolders = getSharedPreferences(
                PhotoFolderActivity.PREFERENCES, MODE_PRIVATE)
                .getStringSet(PhotoFolderActivity.PHOTO_FOLDERS, null);
        if (selectedFolders == null || selectedFolders.isEmpty()) {
            File defaultDirectory = new File(
                    Environment.getExternalStorageDirectory(), PHOTO_DIRECTORY);
            if (!defaultDirectory.exists() && !defaultDirectory.mkdirs()) {
                showPhotoStatus(
                        "無法建立預設照片資料夾\n" + defaultDirectory.getAbsolutePath(),
                        WARNING);
                return;
            }
            selectedFolders = new HashSet<String>();
            selectedFolders.add(defaultDirectory.getAbsolutePath());
        }

        Set<String> visitedDirectories = new HashSet<String>();
        Set<String> discoveredPhotos = new LinkedHashSet<String>();
        for (String path : selectedFolders) {
            collectPhotoFiles(
                    new File(path), visitedDirectories, discoveredPhotos, 0);
            if (discoveredPhotos.size() >= MAX_PHOTO_FILES) {
                break;
            }
        }
        for (String path : discoveredPhotos) {
            photoFiles.add(new File(path));
        }
        Collections.shuffle(photoFiles);
        if (photoFiles.isEmpty()) {
            showPhotoStatus(
                    "選擇的資料夾沒有可播放照片\n"
                            + "請點一下畫面，再選擇「相簿資料夾」",
                    SECONDARY);
        } else {
            showPhotoStatus(
                    "正在載入 " + photoFiles.size() + " 張照片…", SECONDARY);
        }
    }

    private void collectPhotoFiles(
            File directory,
            Set<String> visitedDirectories,
            Set<String> discoveredPhotos,
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
                        entry, visitedDirectories, discoveredPhotos, depth + 1);
            } else if (entry.isFile() && isSupportedPhoto(entry.getName())) {
                discoveredPhotos.add(entry.getAbsolutePath());
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

        final File file = photoFiles.get(photoIndex++);
        final int generation = photoGeneration;
        photoLoading = true;
        nextPhotoAt = SystemClock.elapsedRealtime() + getPhotoIntervalMs();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = decodePhoto(file);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != photoGeneration
                                || currentPage != PHOTO_PAGE
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
                            return;
                        }
                        photoFailures = 0;
                        displayPhoto(bitmap);
                    }
                });
            }
        }, "QuietPanel-photo-decode").start();
    }

    private Bitmap decodePhoto(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
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

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError ignored) {
            return null;
        }
    }

    private void displayPhoto(final Bitmap bitmap) {
        photoStatus.setVisibility(View.GONE);
        if (photoBitmap == null) {
            photoBitmap = bitmap;
            photoImage.setImageBitmap(bitmap);
            startPhotoPan();
            photoImage.setAlpha(0.0f);
            photoImage.animate().alpha(1.0f).setDuration(1200).start();
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
        photoImage.animate().alpha(0.0f).setDuration(800).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (generation != photoGeneration
                        || currentPage != PHOTO_PAGE
                        || !activityResumed) {
                    if (pendingPhotoBitmap == bitmap) {
                        pendingPhotoBitmap = null;
                    }
                    if (!bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                    return;
                }

                Bitmap previous = photoBitmap;
                photoBitmap = bitmap;
                pendingPhotoBitmap = null;
                photoImage.setImageBitmap(bitmap);
                startPhotoPan();
                if (previous != null && previous != bitmap && !previous.isRecycled()) {
                    previous.recycle();
                }
                photoImage.animate().alpha(1.0f).setDuration(1200).start();
            }
        }).start();
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
        final ProgressBar progress;

        DiskRow() {
            container = new LinearLayout(MainActivity.this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp(16), dp(5), dp(16), dp(5));
            container.setBackground(rounded(PANEL));

            LinearLayout line = new LinearLayout(MainActivity.this);
            name = makeText("--", 20, PRIMARY, Gravity.START);
            usage = makeText("等待資料", 15, SECONDARY, Gravity.END);
            line.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
            line.addView(usage, new LinearLayout.LayoutParams(0, dp(32), 2));
            container.addView(line);

            progress = new ProgressBar(
                    MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            container.addView(progress, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));

            LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
            outer.setMargins(dp(5), dp(5), dp(5), dp(5));
            container.setLayoutParams(outer);
        }

        boolean update(JSONObject disk) {
            if (disk == null) {
                name.setText("--");
                usage.setText("沒有更多磁碟");
                usage.setTextColor(SECONDARY);
                progress.setProgress(0);
                return false;
            }

            double total = disk.optDouble("totalGB", 0);
            double used = disk.optDouble("usedGB", 0);
            double percent = total <= 0 ? 0 : used / total * 100.0;
            boolean lowSpace = percent >= 90.0;
            name.setText(disk.optString("name", "?"));
            usage.setText(format(used) + " / " + format(total) + " GB  ·  "
                    + format(percent) + "%" + (lowSpace ? "  ·  空間不足" : ""));
            usage.setTextColor(lowSpace ? WARNING : SECONDARY);
            progress.setProgress((int) Math.round(percent * 10));
            return lowSpace;
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
