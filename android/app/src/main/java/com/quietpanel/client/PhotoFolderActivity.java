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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        root.setBackgroundColor(BACKGROUND);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("選擇相簿資料夾", 23, PRIMARY);
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

        ScrollView scroll = new ScrollView(this);
        folderList = new LinearLayout(this);
        folderList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(folderList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
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
                .apply();
        setResult(RESULT_OK);
        finish();
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
