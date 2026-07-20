package com.quietpanel.client;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity
        implements TransportServer.Listener, ApodServer.Listener {
    private static final int BACKGROUND = Color.rgb(11, 15, 20);
    private static final int PANEL = Color.rgb(24, 31, 40);
    private static final int PRIMARY = Color.rgb(242, 238, 230);
    private static final int SECONDARY = Color.rgb(143, 152, 163);
    private static final int ACCENT = Color.rgb(72, 184, 199);
    private static final int WARNING = Color.rgb(239, 108, 108);

    private final List<Button> actionButtons = new ArrayList<Button>();
    private final DiskRow[] diskRows = new DiskRow[4];
    private TransportServer transport;
    private ApodServer apodServer;
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
    private TextView memoryValue;
    private TextView downloadValue;
    private TextView uploadValue;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
    protected void onDestroy() {
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(12), dp(8), dp(12), dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = makeText("QUIETPANEL  v6.3", 22, PRIMARY, Gravity.START);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        connectionText = makeText("啟動連線服務…", 13, SECONDARY, Gravity.END);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1));
        header.addView(connectionText, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        pager = new SwipePager(this);
        pager.addView(buildSystemPage());
        pager.addView(buildStoragePage());
        pager.addView(buildApodPage());
        pager.addView(buildMacroPage());
        pager.addView(buildToolPage());
        pager.setListener(new SwipePager.Listener() {
            @Override
            public void onSwipe(int direction) {
                showPage(currentPage + direction);
            }
        });
        root.addView(pager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        pageIndicator = makeText("", 14, SECONDARY, Gravity.CENTER);
        root.addView(pageIndicator, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30)));
        showPage(0);

        return root;
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
        MetricCard download = addMetric(
                secondRow, "NETWORK  ↓", "-- MB/s", Color.rgb(41, 182, 246), false);
        MetricCard upload = addMetric(
                secondRow, "NETWORK  ↑", "-- MB/s", Color.rgb(102, 187, 106), false);
        downloadValue = download.value;
        uploadValue = upload.value;
        downloadHistory = download.history;
        uploadHistory = upload.history;

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

        cpuValue.setText(format(cpu) + " %");
        memoryValue.setText(format(memory) + " %");
        downloadValue.setText(format(download) + " MB/s");
        uploadValue.setText(format(upload) + " MB/s");
        cpuHistory.addSample(cpu);
        memoryHistory.addSample(memory);
        downloadHistory.addSample(download);
        uploadHistory.addSample(upload);
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
        currentPage = Math.max(0, Math.min(4, requestedPage));
        pager.setDisplayedChild(currentPage);
        updatePageIndicator();
    }

    private void updatePageIndicator() {
        String[] labels = { "系統", "儲存", "NASA", "MACRO", "快捷" };
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            dots.append(i == currentPage ? "●" : "○");
            if (i < labels.length - 1) {
                dots.append("   ");
            }
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
