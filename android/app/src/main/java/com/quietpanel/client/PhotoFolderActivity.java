package com.quietpanel.client;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.TimePicker;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PhotoFolderActivity extends Activity {
    public static final String PREFERENCES = "quietpanel";
    public static final String PHOTO_FOLDERS = "photo_folders";
    public static final String PRIVATE_ALBUM_ENABLED = "private_album_enabled";
    public static final String PRIVATE_ALBUM_FOLDERS = "private_album_folders";
    public static final String PRIVATE_ALBUM_FOLDERS_CUSTOMIZED =
            "private_album_folders_customized";
    public static final String CLOCK_BACKGROUND = "clock_background";
    public static final String CLOCK_FONT_STYLE = "clock_font_style";
    public static final String DATE_FONT_STYLE = "date_font_style";
    public static final String WEATHER_FONT_STYLE = "weather_font_style";
    public static final String CLOCK_TEXT_SCALE = "clock_text_scale";
    public static final String PHOTO_INTERVAL_SECONDS = "photo_interval_seconds";
    public static final String CLOCK_X_RATIO = "clock_x_ratio";
    public static final String CLOCK_Y_RATIO = "clock_y_ratio";
    public static final String CLOCK_POSITION_CUSTOMIZED = "clock_position_customized";
    public static final String CLOCK_TIME_ENABLED = "clock_time_enabled";
    public static final String CLOCK_DATE_ENABLED = "clock_date_enabled";
    public static final String NIGHT_MODE_ENABLED = "night_mode_enabled";
    public static final String NIGHT_START_HOUR = "night_start_hour";
    public static final String NIGHT_END_HOUR = "night_end_hour";
    public static final String AMBIENT_BRIGHTNESS_ENABLED = "ambient_brightness_enabled";
    public static final String BURN_IN_ENABLED = "burn_in_enabled";
    public static final String LOW_POWER_ENABLED = "low_power_enabled";
    public static final String WEATHER_ENABLED = "weather_enabled";
    public static final String WEATHER_SHOW_LOCATION = "weather_show_location";
    public static final String WEATHER_COMPACT_MODE = "weather_compact_mode";

    private static final int REQUEST_PICK_PHOTO_TREE = 4101;
    private static final Uri PRIVATE_ALBUM_URI = Uri.parse(
            "content://com.quietphoto.privatealbum.photos/photos");
    private static final String PRIVATE_ALBUM_SOURCE_FOLDER = "source_folder";

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
    private CheckBox privateAlbumCheck;
    private LinearLayout privateAlbumFolderList;
    private TextView privateAlbumFolderHint;
    private final Map<String, CheckBox> privateAlbumFolderChecks =
            new LinkedHashMap<String, CheckBox>();
    private LinearLayout folderList;
    private CheckBox backgroundCheck;
    private CheckBox timeCheck;
    private CheckBox dateCheck;
    private CheckBox nightCheck;
    private CheckBox ambientCheck;
    private CheckBox burnInCheck;
    private CheckBox lowPowerCheck;
    private CheckBox weatherCheck;
    private CheckBox weatherLocationCheck;
    private CheckBox weatherCompactCheck;
    private CheckBox alarmCheck;
    private CheckBox alarmRepeatCheck;
    private Button nightStartButton;
    private Button nightEndButton;
    private Button alarmTimeButton;
    private int nightStartHour;
    private int nightEndHour;
    private int alarmHour;
    private int alarmMinute;
    private Spinner fontSpinner;
    private Spinner dateFontSpinner;
    private Spinner weatherFontSpinner;
    private TextView intervalText;
    private SeekBar intervalSeek;

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
        root.setPadding(dp(18), dp(10), dp(18), dp(10));
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
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(42), 2));
        titleRow.addView(selectionText, new LinearLayout.LayoutParams(0, dp(42), 1));
        titleRow.addView(cancel, new LinearLayout.LayoutParams(dp(100), dp(38)));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(100), dp(38));
        saveParams.setMargins(dp(10), 0, 0, 0);
        titleRow.addView(save, saveParams);
        root.addView(titleRow);

        backgroundCheck = new CheckBox(this);
        backgroundCheck.setText("顯示時間日期半透明底板");
        backgroundCheck.setTextColor(PRIMARY);
        backgroundCheck.setTextSize(17);
        backgroundCheck.setChecked(getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(CLOCK_BACKGROUND, true));
        backgroundCheck.setPadding(dp(10), dp(3), dp(10), dp(3));
        root.addView(backgroundCheck, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        SharedPreferences clockPrefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        root.addView(sectionTitle("時鐘與省電"));
        timeCheck = option("顯示時間", clockPrefs.getBoolean(CLOCK_TIME_ENABLED, true));
        dateCheck = option("顯示日期", clockPrefs.getBoolean(CLOCK_DATE_ENABLED, true));
        burnInCheck = option("防烙印微幅位移", clockPrefs.getBoolean(BURN_IN_ENABLED, true));
        ambientCheck = option("依環境光線調整亮度",
                clockPrefs.getBoolean(AMBIENT_BRIGHTNESS_ENABLED, false));
        lowPowerCheck = option("低耗電模式（停用相片平移動畫）",
                clockPrefs.getBoolean(LOW_POWER_ENABLED, false));
        root.addView(timeCheck);
        root.addView(dateCheck);
        root.addView(burnInCheck);
        root.addView(ambientCheck);
        root.addView(lowPowerCheck);

        nightCheck = option("夜間定時暗屏", clockPrefs.getBoolean(NIGHT_MODE_ENABLED, false));
        root.addView(nightCheck);
        nightStartHour = clockPrefs.getInt(NIGHT_START_HOUR, 23);
        nightEndHour = clockPrefs.getInt(NIGHT_END_HOUR, 7);
        LinearLayout nightRow = new LinearLayout(this);
        nightRow.setGravity(Gravity.CENTER_VERTICAL);
        nightStartButton = button("", PANEL);
        nightEndButton = button("", PANEL);
        updateNightButtons();
        nightStartButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { selectNightHour(true); }
        });
        nightEndButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { selectNightHour(false); }
        });
        nightRow.addView(nightStartButton, new LinearLayout.LayoutParams(0, dp(38), 1));
        LinearLayout.LayoutParams nightEndParams = new LinearLayout.LayoutParams(0, dp(38), 1);
        nightEndParams.setMargins(dp(10), 0, 0, 0);
        nightRow.addView(nightEndButton, nightEndParams);
        root.addView(nightRow);

        root.addView(sectionTitle("電腦提供的天氣"));
        weatherCheck = option("顯示溫度與天氣圖示",
                clockPrefs.getBoolean(WEATHER_ENABLED, true));
        weatherLocationCheck = option("顯示地名",
                clockPrefs.getBoolean(WEATHER_SHOW_LOCATION, false));
        weatherCompactCheck = option("精簡排列（無地名時與日期同列）",
                clockPrefs.getBoolean(WEATHER_COMPACT_MODE, true));
        weatherCompactCheck.setEnabled(!weatherLocationCheck.isChecked());
        weatherCompactCheck.setAlpha(weatherLocationCheck.isChecked() ? 0.45f : 1.0f);
        weatherLocationCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean available = !weatherLocationCheck.isChecked();
                weatherCompactCheck.setEnabled(available);
                weatherCompactCheck.setAlpha(available ? 1.0f : 0.45f);
            }
        });
        root.addView(weatherCheck);
        root.addView(weatherLocationCheck);
        root.addView(weatherCompactCheck);

        root.addView(sectionTitle("天氣字型"));
        weatherFontSpinner = createFontSpinner(clockPrefs.getInt(
                WEATHER_FONT_STYLE, clockPrefs.getInt(
                        CLOCK_FONT_STYLE, PhotoFontManager.STYLE_STOROPIA)));
        root.addView(weatherFontSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        root.addView(sectionTitle("鬧鐘"));
        alarmCheck = option("啟用鬧鐘",
                clockPrefs.getBoolean(AlarmHelper.PREF_ALARM_ENABLED, false));
        alarmRepeatCheck = option("每天重複",
                clockPrefs.getBoolean(AlarmHelper.PREF_ALARM_REPEAT, true));
        alarmHour = clockPrefs.getInt(AlarmHelper.PREF_ALARM_HOUR, 7);
        alarmMinute = clockPrefs.getInt(AlarmHelper.PREF_ALARM_MINUTE, 0);
        alarmTimeButton = button("", PANEL);
        updateAlarmButton();
        alarmTimeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { selectAlarmTime(); }
        });
        root.addView(alarmCheck);
        root.addView(alarmRepeatCheck);
        root.addView(alarmTimeButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        intervalText = text("", 16, PRIMARY);
        intervalText.setPadding(dp(10), 0, dp(8), 0);
        intervalSeek = flatSeekBar();
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
        intervalRow.addView(intervalText, new LinearLayout.LayoutParams(dp(150), dp(26)));
        intervalRow.addView(intervalSeek, new LinearLayout.LayoutParams(0, dp(26), 1));
        root.addView(intervalRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));
        updateIntervalText();

        android.content.SharedPreferences preferences = getSharedPreferences(
                PREFERENCES, MODE_PRIVATE);
        root.addView(sectionTitle("時間字型"));
        fontSpinner = createFontSpinner(preferences.getInt(
                CLOCK_FONT_STYLE, PhotoFontManager.STYLE_STOROPIA));
        root.addView(fontSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        root.addView(sectionTitle("日期字型"));
        dateFontSpinner = createFontSpinner(preferences.getInt(
                DATE_FONT_STYLE, preferences.getInt(
                        CLOCK_FONT_STYLE, PhotoFontManager.STYLE_STOROPIA)));
        root.addView(dateFontSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        privateAlbumCheck = option("使用「手機私有相簿」中的照片",
                clockPrefs.getBoolean(PRIVATE_ALBUM_ENABLED, false));
        privateAlbumCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                updatePrivateAlbumFolderEnabled();
                updateSelectionCount();
            }
        });
        root.addView(privateAlbumCheck);
        privateAlbumFolderHint = text(
                "由手機私有相簿集中管理；加入或刪除照片後會自動重新讀取。",
                14, SECONDARY);
        privateAlbumFolderHint.setPadding(dp(10), 0, dp(10), dp(5));
        root.addView(privateAlbumFolderHint);
        root.addView(sectionTitle("私有相簿來源資料夾"));
        privateAlbumFolderList = new LinearLayout(this);
        privateAlbumFolderList.setOrientation(LinearLayout.VERTICAL);
        root.addView(privateAlbumFolderList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        loadPrivateAlbumFolders();

        if (Build.VERSION.SDK_INT >= 21) {
            Button systemFolderPicker = button("從系統選擇照片資料夾／SD 卡", ACCENT);
            systemFolderPicker.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    choosePhotoTree();
                }
            });
            LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
            pickerParams.setMargins(0, dp(10), 0, dp(4));
            root.addView(systemFolderPicker, pickerParams);
            TextView pickerHint = text(
                    "Fire、Android 5 以上請從這裡選取實體 SD 卡內的資料夾。",
                    14, SECONDARY);
            pickerHint.setPadding(dp(10), 0, dp(10), dp(4));
            root.addView(pickerHint);
        }

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
        pathRow.addView(up, new LinearLayout.LayoutParams(dp(110), dp(35)));
        pathRow.addView(pathText, new LinearLayout.LayoutParams(0, dp(35), 1));
        root.addView(pathRow);

        currentFolderCheck = new CheckBox(this);
        currentFolderCheck.setText("使用目前資料夾中的照片（包含子目錄）");
        currentFolderCheck.setTextColor(PRIMARY);
        currentFolderCheck.setTextSize(17);
        currentFolderCheck.setPadding(dp(10), dp(4), dp(10), dp(4));
        currentFolderCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setSelected(currentDirectory,
                        isPartiallySelected(currentDirectory) || currentFolderCheck.isChecked());
            }
        });
        root.addView(currentFolderCheck, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

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

    private Spinner createFontSpinner(int selectedStyle) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> fontAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, PhotoFontManager.names()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return styleSpinnerItem(super.getView(position, convertView, parent));
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return styleSpinnerItem(super.getDropDownView(position, convertView, parent));
            }
        };
        fontAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(fontAdapter);
        spinner.setSelection(PhotoFontManager.optionIndex(selectedStyle));
        spinner.setBackground(rounded(PANEL));
        spinner.setPadding(dp(12), 0, dp(12), 0);
        return spinner;
    }

    private void choosePhotoTree() {
        if (Build.VERSION.SDK_INT < 21) {
            Toast.makeText(this, "此系統請使用下方資料夾清單", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_PHOTO_TREE);
        } catch (Exception error) {
            Toast.makeText(this, "這台裝置沒有可用的系統檔案選擇器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_PHOTO_TREE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < 21) {
            return;
        }
        Uri treeUri = data.getData();
        int grantedFlags = data.getFlags();
        boolean canRead = (grantedFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        boolean canWrite = (grantedFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        try {
            if (canRead && canWrite) {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else if (canRead) {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (canWrite) {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        } catch (SecurityException ignored) {
            // A few Fire OS file pickers grant a usable one-time URI but do not
            // advertise persistable permission. Keep it for this session.
        }
        selectedFolders.add(treeUri.toString());
        Toast.makeText(this, "已加入系統選擇的照片資料夾，請按「套用」儲存",
                Toast.LENGTH_LONG).show();
        showDirectory();
    }

    private void showDirectory() {
        pathText.setText(relativePath(currentDirectory));
        applySelectionState(currentFolderCheck, currentDirectory);
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
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
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
        row.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        rowParams.setMargins(0, dp(2), 0, dp(2));
        row.setLayoutParams(rowParams);
        row.setBackground(rounded(PANEL));

        final CheckBox check = new CheckBox(this);
        applySelectionState(check, directory);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // A grey tick means one or more descendants are selected.
                // Tapping it promotes this directory to a full selection.
                setSelected(directory, isPartiallySelected(directory) || check.isChecked());
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
        row.addView(check, new LinearLayout.LayoutParams(dp(46), dp(42)));
        row.addView(name, new LinearLayout.LayoutParams(0, dp(42), 1));
        return row;
    }

    private void setSelected(File directory, boolean selected) {
        String path = canonical(directory);
        if (selected) {
            selectedFolders.add(path);
        } else {
            selectedFolders.remove(path);
        }
        showDirectory();
    }

    private void applySelectionState(CheckBox check, File directory) {
        boolean direct = selectedFolders.contains(canonical(directory));
        boolean partial = !direct && isPartiallySelected(directory);
        check.setChecked(direct || partial);
        // Android 4.2 has no indeterminate CheckBox state.  A semi-transparent
        // checked mark is an unambiguous, compatible partial-selection marker.
        check.setAlpha(partial ? 0.45f : 1.0f);
    }

    private boolean isPartiallySelected(File directory) {
        String prefix = canonical(directory) + File.separator;
        for (String selected : selectedFolders) {
            if (selected.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Loads the private album's distinct import folders for the old-device UI. */
    private void loadPrivateAlbumFolders() {
        if (privateAlbumFolderList == null) {
            return;
        }
        privateAlbumFolderChecks.clear();
        privateAlbumFolderList.removeAllViews();
        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        boolean customized = preferences.getBoolean(PRIVATE_ALBUM_FOLDERS_CUSTOMIZED, false);
        Set<String> saved = preferences.getStringSet(PRIVATE_ALBUM_FOLDERS, null);
        Set<String> savedFolders = saved == null
                ? new HashSet<String>() : new HashSet<String>(saved);
        Set<String> folders = new LinkedHashSet<String>();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(PRIVATE_ALBUM_URI,
                    new String[] { PRIVATE_ALBUM_SOURCE_FOLDER }, null, null, null);
            if (cursor != null) {
                int folderColumn = cursor.getColumnIndex(PRIVATE_ALBUM_SOURCE_FOLDER);
                while (folderColumn >= 0 && cursor.moveToNext()) {
                    String folder = cursor.getString(folderColumn);
                    if (folder != null && folder.length() > 0) {
                        folders.add(folder);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // The private album is optional; keep the normal folder picker usable.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        for (String folder : folders) {
            CheckBox check = option(privateAlbumFolderLabel(folder),
                    !customized || savedFolders.contains(folder));
            check.setTextSize(14);
            check.setPadding(dp(18), dp(2), dp(10), dp(2));
            privateAlbumFolderChecks.put(folder, check);
            privateAlbumFolderList.addView(check, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        if (folders.isEmpty()) {
            privateAlbumFolderHint.setText(
                    "尚無可選的匯入資料夾；先在「手機私有相簿」匯入照片。"
                            + "目前會保留全部私有相簿來源設定。");
        } else {
            privateAlbumFolderHint.setText(
                    "勾選要播放的私有相簿資料夾；不勾選則不播放該資料夾。"
                            + "（單張匯入會列為單張匯入）");
        }
        updatePrivateAlbumFolderEnabled();
        updateSelectionCount();
    }

    private String privateAlbumFolderLabel(String folder) {
        if ("（單張匯入）".equals(folder)) {
            return "單張匯入";
        }
        int separator = Math.max(folder.lastIndexOf('/'), folder.lastIndexOf('\\'));
        String name = separator >= 0 && separator + 1 < folder.length()
                ? folder.substring(separator + 1) : folder;
        return name.equals(folder) ? folder : name + "\n" + folder;
    }

    private void updatePrivateAlbumFolderEnabled() {
        boolean enabled = privateAlbumCheck == null || privateAlbumCheck.isChecked();
        for (CheckBox check : privateAlbumFolderChecks.values()) {
            check.setEnabled(enabled);
            check.setAlpha(enabled ? 1.0f : 0.45f);
        }
    }

    private void updateSelectionCount() {
        int systemTrees = 0;
        for (String selected : selectedFolders) {
            if (selected.startsWith("content://")) {
                systemTrees++;
            }
        }
        int privateSources = 0;
        if (privateAlbumCheck != null && privateAlbumCheck.isChecked()) {
            if (privateAlbumFolderChecks.isEmpty()) {
                privateSources = 1;
            } else {
                for (CheckBox check : privateAlbumFolderChecks.values()) {
                    if (check.isChecked()) {
                        privateSources++;
                    }
                }
            }
        }
        int totalSources = selectedFolders.size() + privateSources;
        selectionText.setText(systemTrees > 0
                ? "已選 " + totalSources + " 個來源（SD " + systemTrees + "）"
                : "已選 " + totalSources + " 個來源");
    }

    private void saveSelection() {
        if (selectedFolders.isEmpty()
                && (privateAlbumCheck == null || !privateAlbumCheck.isChecked())) {
            Toast.makeText(this, "請至少選擇一個照片來源", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences.Editor editor = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putStringSet(PHOTO_FOLDERS, new HashSet<String>(selectedFolders))
                .putBoolean(PRIVATE_ALBUM_ENABLED,
                        privateAlbumCheck != null && privateAlbumCheck.isChecked())
                .putBoolean(CLOCK_BACKGROUND, backgroundCheck.isChecked())
                .putBoolean(CLOCK_TIME_ENABLED, timeCheck.isChecked())
                .putBoolean(CLOCK_DATE_ENABLED, dateCheck.isChecked())
                .putBoolean(NIGHT_MODE_ENABLED, nightCheck.isChecked())
                .putInt(NIGHT_START_HOUR, nightStartHour)
                .putInt(NIGHT_END_HOUR, nightEndHour)
                .putBoolean(AMBIENT_BRIGHTNESS_ENABLED, ambientCheck.isChecked())
                .putBoolean(BURN_IN_ENABLED, burnInCheck.isChecked())
                .putBoolean(LOW_POWER_ENABLED, lowPowerCheck.isChecked())
                .putBoolean(WEATHER_ENABLED, weatherCheck.isChecked())
                .putBoolean(WEATHER_SHOW_LOCATION, weatherLocationCheck.isChecked())
                .putBoolean(WEATHER_COMPACT_MODE, weatherCompactCheck.isChecked())
                .putBoolean(AlarmHelper.PREF_ALARM_ENABLED, alarmCheck.isChecked())
                .putBoolean(AlarmHelper.PREF_ALARM_REPEAT, alarmRepeatCheck.isChecked())
                .putInt(AlarmHelper.PREF_ALARM_HOUR, alarmHour)
                .putInt(AlarmHelper.PREF_ALARM_MINUTE, alarmMinute)
                .putInt(CLOCK_FONT_STYLE,
                        PhotoFontManager.styleAtOptionIndex(fontSpinner.getSelectedItemPosition()))
                .putInt(DATE_FONT_STYLE,
                        PhotoFontManager.styleAtOptionIndex(dateFontSpinner.getSelectedItemPosition()))
                .putInt(WEATHER_FONT_STYLE,
                        PhotoFontManager.styleAtOptionIndex(weatherFontSpinner.getSelectedItemPosition()))
                .putInt(PHOTO_INTERVAL_SECONDS, 10 + intervalSeek.getProgress() * 5);
        if (!privateAlbumFolderChecks.isEmpty()) {
            Set<String> selectedPrivateFolders = new HashSet<String>();
            for (Map.Entry<String, CheckBox> entry : privateAlbumFolderChecks.entrySet()) {
                if (entry.getValue().isChecked()) {
                    selectedPrivateFolders.add(entry.getKey());
                }
            }
            editor.putBoolean(PRIVATE_ALBUM_FOLDERS_CUSTOMIZED, true)
                    .putStringSet(PRIVATE_ALBUM_FOLDERS, selectedPrivateFolders);
        }
        editor.apply();
        AlarmHelper.updateAlarmSchedule(this);
        setResult(RESULT_OK);
        finish();
    }

    private void updateIntervalText() {
        if (intervalText != null && intervalSeek != null) {
            intervalText.setText("單張停留：" + (10 + intervalSeek.getProgress() * 5) + " 秒");
        }
    }

    private CheckBox option(String label, boolean checked) {
        CheckBox check = new CheckBox(this);
        check.setText(label);
        check.setTextColor(PRIMARY);
        check.setTextSize(16);
        check.setChecked(checked);
        check.setPadding(dp(10), dp(2), dp(10), dp(2));
        return check;
    }

    private void selectNightHour(final boolean start) {
        int value = start ? nightStartHour : nightEndHour;
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                if (start) {
                    nightStartHour = hourOfDay;
                } else {
                    nightEndHour = hourOfDay;
                }
                updateNightButtons();
            }
        }, value, 0, true).show();
    }

    private void updateNightButtons() {
        if (nightStartButton != null) {
            nightStartButton.setText(String.format(java.util.Locale.US,
                    "暗屏開始 %02d:00", nightStartHour));
        }
        if (nightEndButton != null) {
            nightEndButton.setText(String.format(java.util.Locale.US,
                    "恢復顯示 %02d:00", nightEndHour));
        }
    }

    private void selectAlarmTime() {
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                alarmHour = hourOfDay;
                alarmMinute = minute;
                updateAlarmButton();
            }
        }, alarmHour, alarmMinute, true).show();
    }

    private void updateAlarmButton() {
        if (alarmTimeButton != null) {
            alarmTimeButton.setText(String.format(java.util.Locale.US,
                    "響鈴時間 %02d:%02d", alarmHour, alarmMinute));
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

    private TextView sectionTitle(String label) {
        TextView title = text(label, 17, PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(10), dp(8), dp(10), dp(3));
        return title;
    }

    private View styleSpinnerItem(View view) {
        if (view instanceof TextView) {
            TextView item = (TextView) view;
            item.setTextColor(PRIMARY);
            item.setTextSize(16);
            item.setBackgroundColor(PANEL);
            item.setPadding(dp(12), dp(6), dp(12), dp(6));
        }
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

    private SeekBar flatSeekBar() {
        SeekBar seekBar = new SeekBar(this);
        GradientDrawable track = rounded(Color.rgb(52, 65, 77));
        GradientDrawable activeTrack = rounded(ACCENT);
        track.setSize(dp(1), dp(4));
        activeTrack.setSize(dp(1), dp(4));
        LayerDrawable progressDrawable = new LayerDrawable(new Drawable[] {
                track,
                new ClipDrawable(activeTrack, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        });
        progressDrawable.setId(0, android.R.id.background);
        progressDrawable.setId(1, android.R.id.progress);
        seekBar.setProgressDrawable(progressDrawable);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.TRANSPARENT);
        thumb.setSize(dp(1), dp(1));
        seekBar.setThumb(thumb);
        seekBar.setThumbOffset(0);
        seekBar.setPadding(0, 0, 0, 0);
        seekBar.setMinimumHeight(dp(1));
        return seekBar;
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
