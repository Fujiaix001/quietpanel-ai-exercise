package com.quietpanel.client;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PhotoFolderActivity extends Activity {
    public static final String PREFERENCES = "quietpanel";
    public static final String PHOTO_FOLDERS = "photo_folders";
    public static final String CLOCK_BACKGROUND = "clock_background";
    public static final String CLOCK_FONT_STYLE = "clock_font_style";
    public static final String PHOTO_INTERVAL_SECONDS = "photo_interval_seconds";
    public static final String CLOCK_X_RATIO = "clock_x_ratio";
    public static final String CLOCK_Y_RATIO = "clock_y_ratio";

    private static final int BACKGROUND = Color.rgb(11, 15, 20);
    private static final int PANEL = Color.rgb(24, 31, 40);
    private static final int PRIMARY = Color.rgb(242, 238, 230);
    private static final int SECONDARY = Color.rgb(143, 152, 163);
    private static final int ACCENT = Color.rgb(72, 184, 199);

    private final Set<String> selectedFolders = new LinkedHashSet<String>();
    private File storageRoot;
    private File currentDirectory;
    private TextView pathText;
    private TextView selectionText;
    private CheckBox currentFolderCheck;
    private LinearLayout folderList;
    private CheckBox backgroundCheck;
    private TextView intervalText;
    private SeekBar intervalSeek;
    private int selectedClockFontStyle;
    private final List<Button> clockFontButtons = new ArrayList<Button>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        storageRoot = Environment.getExternalStorageDirectory();
        currentDirectory = storageRoot;
        Set<String> saved = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getStringSet(PHOTO_FOLDERS, null);
        if (saved == null || saved.isEmpty()) {
            selectedFolders.add(canonical(new File(storageRoot, "QuietPanel/Photos")));
        } else {
            selectedFolders.addAll(new HashSet<String>(saved));
        }

        setContentView(buildInterface());
        showDirectory();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        if (!sameFile(currentDirectory, storageRoot)) {
            File parent = currentDirectory.getParentFile();
            if (parent != null && isInsideStorage(parent)) {
                currentDirectory = parent;
                showDirectory();
                return;
            }
        }
        super.onBackPressed();
    }

    private View buildInterface() {
        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        root.setBackgroundColor(BACKGROUND);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("相簿設定", 23, PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        selectionText = text("", 14, ACCENT);
        selectionText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        Button cancel = button("取消", PANEL);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        Button save = button("套用", Color.rgb(37, 124, 137));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                saveSelection();
            }
        });
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(52), 2));
        titleRow.addView(selectionText, new LinearLayout.LayoutParams(0, dp(52), 1));
        titleRow.addView(cancel, new LinearLayout.LayoutParams(dp(100), dp(46)));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(100), dp(46));
        saveParams.setMargins(dp(10), 0, 0, 0);
        titleRow.addView(save, saveParams);
        root.addView(titleRow);

        backgroundCheck = new CheckBox(this);
        backgroundCheck.setText("顯示時間日期半透明底板");
        backgroundCheck.setTextColor(PRIMARY);
        backgroundCheck.setTextSize(17);
        backgroundCheck.setChecked(getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(CLOCK_BACKGROUND, true));
        backgroundCheck.setPadding(dp(10), dp(4), dp(10), dp(4));
        root.addView(backgroundCheck, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        TextView fontTitle = text("時鐘字體", 16, PRIMARY);
        fontTitle.setPadding(dp(10), dp(4), dp(10), 0);
        root.addView(fontTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30)));

        selectedClockFontStyle = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getInt(CLOCK_FONT_STYLE, 0);
        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        final String[] fontLabels = { "預設", "等寬", "襯線", "細體" };
        for (int i = 0; i < fontLabels.length; i++) {
            final int style = i;
            Button fontButton = button(fontLabels[i],
                    style == selectedClockFontStyle ? Color.rgb(37, 124, 137) : PANEL);
            fontButton.setTextSize(14);
            fontButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    setClockFontStyle(style);
                }
            });
            clockFontButtons.add(fontButton);
            LinearLayout.LayoutParams fontParams = new LinearLayout.LayoutParams(0, dp(40), 1);
            if (i < fontLabels.length - 1) {
                fontParams.setMargins(0, 0, dp(6), 0);
            }
            fontRow.addView(fontButton, fontParams);
        }
        root.addView(fontRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        intervalText = text("", 16, PRIMARY);
        intervalText.setPadding(dp(10), 0, dp(8), 0);
        intervalSeek = new SeekBar(this);
        intervalSeek.setMax((300 - 10) / 5);
        int savedSeconds = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getInt(PHOTO_INTERVAL_SECONDS, 45);
        intervalSeek.setProgress(Math.max(0, Math.min(intervalSeek.getMax(),
                (savedSeconds - 10) / 5)));
        intervalSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateIntervalText();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        intervalRow.addView(intervalText, new LinearLayout.LayoutParams(dp(150), dp(52)));
        intervalRow.addView(intervalSeek, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(intervalRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        updateIntervalText();

        LinearLayout pathRow = new LinearLayout(this);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        Button up = button("上一層", PANEL);
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });
        pathText = text("", 15, SECONDARY);
        pathText.setPadding(dp(14), 0, 0, 0);
        pathRow.addView(up, new LinearLayout.LayoutParams(dp(110), dp(44)));
        pathRow.addView(pathText, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(pathRow);

        currentFolderCheck = new CheckBox(this);
        currentFolderCheck.setText("使用目前資料夾中的照片（包含子目錄）");
        currentFolderCheck.setTextColor(PRIMARY);
        currentFolderCheck.setTextSize(17);
        currentFolderCheck.setPadding(dp(10), dp(6), dp(10), dp(6));
        currentFolderCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setSelected(currentDirectory, currentFolderCheck.isChecked());
            }
        });
        root.addView(currentFolderCheck, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        TextView hint = text("點資料夾名稱進入；勾選方框可一次選擇多個資料夾。", 14, SECONDARY);
        hint.setPadding(dp(10), 0, dp(10), dp(6));
        root.addView(hint);

        folderList = new LinearLayout(this);
        folderList.setOrientation(LinearLayout.VERTICAL);
        root.addView(folderList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        pageScroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        return pageScroll;
    }

    private void showDirectory() {
        pathText.setText(relativePath(currentDirectory));
        currentFolderCheck.setChecked(selectedFolders.contains(canonical(currentDirectory)));
        folderList.removeAllViews();

        File[] entries = currentDirectory.listFiles();
        List<File> directories = new ArrayList<File>();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.isDirectory() && !entry.getName().startsWith(".")) {
                    directories.add(entry);
                }
            }
        }
        Collections.sort(directories, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });

        if (directories.isEmpty()) {
            TextView empty = text("這個位置沒有子資料夾", 16, SECONDARY);
            empty.setGravity(Gravity.CENTER);
            folderList.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(70)));
        } else {
            for (final File directory : directories) {
                folderList.addView(folderRow(directory));
            }
        }
        updateSelectionCount();
    }

    private View folderRow(final File directory) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        rowParams.setMargins(0, dp(3), 0, dp(3));
        row.setLayoutParams(rowParams);
        row.setBackground(rounded(PANEL));

        final CheckBox check = new CheckBox(this);
        check.setChecked(selectedFolders.contains(canonical(directory)));
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setSelected(directory, check.isChecked());
            }
        });
        TextView name = text(directory.getName() + "   ›", 18, PRIMARY);
        name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        name.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                currentDirectory = directory;
                showDirectory();
            }
        });
        row.addView(check, new LinearLayout.LayoutParams(dp(58), dp(52)));
        row.addView(name, new LinearLayout.LayoutParams(0, dp(52), 1));
        return row;
    }

    private void setSelected(File directory, boolean selected) {
        String path = canonical(directory);
        if (selected) {
            selectedFolders.add(path);
        } else {
            selectedFolders.remove(path);
        }
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        selectionText.setText("已選 " + selectedFolders.size() + " 個");
    }

    private void saveSelection() {
        if (selectedFolders.isEmpty()) {
            Toast.makeText(this, "請至少選擇一個資料夾", Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putStringSet(PHOTO_FOLDERS, new HashSet<String>(selectedFolders))
                .putBoolean(CLOCK_BACKGROUND, backgroundCheck.isChecked())
                .putInt(CLOCK_FONT_STYLE, selectedClockFontStyle)
                .putInt(PHOTO_INTERVAL_SECONDS, 10 + intervalSeek.getProgress() * 5)
                .apply();
        setResult(RESULT_OK);
        finish();
    }

    private void updateIntervalText() {
        if (intervalText != null && intervalSeek != null) {
            intervalText.setText("單張停留：" + (10 + intervalSeek.getProgress() * 5) + " 秒");
        }
    }

    private void setClockFontStyle(int style) {
        selectedClockFontStyle = style;
        for (int i = 0; i < clockFontButtons.size(); i++) {
            clockFontButtons.get(i).setBackground(rounded(
                    i == selectedClockFontStyle ? Color.rgb(37, 124, 137) : PANEL));
        }
    }

    private String relativePath(File directory) {
        String root = canonical(storageRoot);
        String path = canonical(directory);
        if (path.equals(root)) {
            return "SD 卡";
        }
        if (path.startsWith(root + File.separator)) {
            return "SD 卡 / " + path.substring(root.length() + 1);
        }
        return path;
    }

    private boolean isInsideStorage(File file) {
        String root = canonical(storageRoot);
        String path = canonical(file);
        return path.equals(root) || path.startsWith(root + File.separator);
    }

    private boolean sameFile(File left, File right) {
        return canonical(left).equals(canonical(right));
    }

    private String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackground(rounded(color));
        return button;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(9));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
