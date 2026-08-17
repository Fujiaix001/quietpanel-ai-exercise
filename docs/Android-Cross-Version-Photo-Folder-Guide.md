# Android 跨版本相簿／資料夾讀取指南

這份指南從 QuietPanel v9 的實作整理而來，目標是把同一套「相簿時鐘」功能安全移植到另一個 Android 專案（例如 LittleClock）。

適用範圍：Android 4.2（API 17）到現代 Android，以及 Amazon Fire OS、實體 SD 卡、上萬張照片的資料夾。

## 先說結論

不要把所有資料夾都當作 `java.io.File` 處理。

- **Android 4.2–4.4（API 17–20）**：保留 `File` 路徑選取與 `File.listFiles()` 掃描。
- **Android 5.0 以上（API 21+）**：提供系統資料夾選取器 `ACTION_OPEN_DOCUMENT_TREE`，並把選擇結果當作 `content://` Tree URI 保存。
- **不要把 `content://` URI 轉回檔案絕對路徑。** 在 Fire OS、可移除 SD 卡、Scoped Storage 裝置上，這通常做不到或不可靠。
- 掃描與 Bitmap 解碼一律在背景執行；UI 執行緒只接收結果。

這不是單純的「權限」問題。Fire OS 的 SD 卡與新 Android 的儲存提供者常常不會讓 `File.listFiles()` 看見可用內容；系統文件選取器取得的 URI 才是正確權限憑證。

## 建議的資料模型：路徑與 URI 共存

儲存選取資料夾時，`SharedPreferences` 的 `StringSet` 可以同時保存：

- 舊式資料夾：正規化的絕對路徑，例如 `/storage/emulated/0/Pictures`。
- 系統選取資料夾：完整 Tree URI，例如 `content://com.android.externalstorage.documents/tree/...`。

讀取端不要只存 `File`，要使用能代表兩種來源的項目：

```java
private static final class PhotoSource {
    final File file;   // legacy path source
    final Uri uri;     // Storage Access Framework source
    final String identity; // deduplication key

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

private InputStream openPhotoInputStream(PhotoSource source) throws Exception {
    return source.file != null
            ? new FileInputStream(source.file)
            : getContentResolver().openInputStream(source.uri);
}
```

後續的圖片解碼只依賴 `InputStream`，因此不需要知道來源是檔案還是 Document Provider。

## 1. API 21+：使用系統資料夾選取器

不要自己嘗試用檔案瀏覽 UI 去切換 Fire 的 SD 卡。請讓 Android／Fire 的 DocumentsUI 處理儲存卷、SD 卡與提供者。

```java
private static final int REQUEST_PICK_PHOTO_TREE = 4101;

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
```

讀取照片只需要 read permission；保留 write flag 是為了與某些舊 DocumentsUI／Fire OS 的授權行為相容。若專案的安全策略要求最小權限，可只要求 read，但必須重新測試 Fire。

在 `onActivityResult()` 取得 URI 時，必須立刻保存永久授權：

```java
@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQUEST_PICK_PHOTO_TREE || resultCode != RESULT_OK
            || data == null || data.getData() == null) {
        return;
    }

    Uri treeUri = data.getData();
    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    boolean persistent = true;
    try {
        getContentResolver().takePersistableUriPermission(treeUri, flags);
    } catch (SecurityException ignored) {
        // 某些 Fire OS picker 只給目前程序可用的一次性 URI。
        persistent = false;
    }

    selectedFolders.add(treeUri.toString());
    // 寫入 SharedPreferences 要等使用者按「套用」再做。
    // persistent == false 時請提示：重新開啟 App 後可能需重新選取。
}
```

### 重要：不要把暫時授權當成永久授權

部分 Fire OS 檔案選取器會回傳可立即使用、但不能持久化的 URI。這不是程式錯誤；正確作法是：

1. 本次程序仍可嘗試讀取。
2. 重開 App 後若 query／openInputStream 出現 `SecurityException`，顯示「請重新選取 SD 卡資料夾」。
3. 不要因為一個 URI 失效就中止其他已選的資料夾。

## 2. API 17–20：保留 File 路徑模式

Android 4.2 沒有 `ACTION_OPEN_DOCUMENT_TREE`，因此需保留本機資料夾瀏覽器和：

```java
File[] entries = directory.listFiles();
```

legacy 路徑需要：

- 在 manifest 宣告 `READ_EXTERNAL_STORAGE`。
- 若同時支援 API 23–32 的 legacy 路徑，需在執行期取得讀取權限。
- Android 13+ 若直接讀 MediaStore，使用 `READ_MEDIA_IMAGES`；但已經透過 SAF 選取的 `content://` URI **不需要**這個權限。

不要把 legacy 路徑讀取當成現代 Android SD 卡的 fallback。它只應服務 API 17–20，或確實可見的 app 可存取資料夾。

## 3. 掃描 Document Tree：直接使用 DocumentsContract

若專案不想引入 AndroidX `DocumentFile`，可以直接用 `DocumentsContract`。這對 API 21+ 足夠，並能控制掃描順序與 Cursor 關閉。

```java
private void collectDocumentTreePhotos(Uri treeUri, int depth) {
    if (depth > 12) return; // 防止異常提供者／循環目錄
    try {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
        collectDocumentDirectoryPhotos(treeUri, root, depth);
    } catch (Exception ignored) {
        // 卡片被拔除、URI 授權失效或 provider 異常：跳過此來源。
    }
}

private void collectDocumentDirectoryPhotos(Uri treeUri, Uri directoryUri, int depth) {
    String directoryId = DocumentsContract.getDocumentId(directoryUri);
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId);
    String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    Cursor cursor = null;
    try {
        cursor = getContentResolver().query(children, projection, null, null, null);
        if (cursor == null) return;
        while (cursor.moveToNext()) {
            String childId = cursor.getString(0);
            String name = cursor.getString(1);
            String mimeType = cursor.getString(2);
            Uri child = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
            // 資料夾遞迴；檔案以副檔名或 MIME type 過濾。
        }
    } finally {
        if (cursor != null) cursor.close();
    }
}
```

必做防護：

- 遞迴深度上限，例如 12。
- `visitedDirectories`：key 可用 `treeUri + "|" + documentId`，避免 provider 重複回傳目錄。
- `discoveredPhotos`：以 URI 字串或 canonical file path 去重。
- 每個選取資料夾獨立 `try/catch`；失效 SD 卡不應阻斷內部儲存空間。
- 永遠關閉 Cursor 和 InputStream。

### Fire OS 的掃描順序

先列出「目前選取目錄自己的照片」，把子資料夾收集起來後再遞迴子資料夾。這種廣度優先傾向的順序可避免某個很深的資料夾讓第一張可播放照片延後很久才出現。

## 4. 掃描絕不能放在 UI 執行緒

資料夾裡有數千或上萬張照片時，以下動作都可能慢：

- `File.listFiles()`
- `ContentResolver.query()`
- SD 卡／Documents Provider I/O
- 去重、排序與讀取 EXIF

因此掃描必須在背景 Thread／Executor。UI 的原則是：**第一張找到後立刻可播放，完整索引在背景慢慢完成。**

```java
final int scanGeneration = photoGeneration;
photoScanInProgress = true;

new Thread(new Runnable() {
    @Override public void run() {
        final List<PhotoSource> completeList = new ArrayList<PhotoSource>();
        scanAllSelectedFolders(completeList, new PhotoDiscovery() {
            @Override public void onPhotoDiscovered(final PhotoSource first) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (scanGeneration != photoGeneration) return;
                        // 只發布第一張，立即開始解碼與顯示。
                    }
                });
            }
        });
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (scanGeneration != photoGeneration) return;
                // 以 completeList 取代暫存列表，完整輪播從此開始。
            }
        });
    }
}, "Photo-scan").start();
```

`photoGeneration` 是取消令牌：使用者換資料夾、離開相簿頁、Activity 暫停時遞增它。任何舊掃描或舊解碼回到 UI 後，先比較 generation；不一致就丟棄結果並 recycle Bitmap。

## 5. 不要為了速度而截斷上萬張相片

不要用「最多 500 張」這類硬上限來假裝掃描完成；那會使某些照片永遠不會出現。

正確策略：

- 允許完整索引（可用 `Integer.MAX_VALUE`）。
- 用背景執行、先發布第一張、掃描中不重複開始。
- 若未來確實需要限制記憶體，限制的是「常駐 Bitmap／縮圖快取」，不是相簿索引的總張數。

## 6. 避免每次換頁都重掃

保存一個依選取資料夾產生的穩定 signature，例如先排序後用換行合併：

```java
private String folderSignature(Set<String> folders) {
    List<String> ordered = new ArrayList<String>(folders);
    Collections.sort(ordered);
    return TextUtils.join("\n", ordered);
}
```

維護：

- `photoScanInProgress`
- `photoCatalogLoaded`
- `photoFolderSignature`

若 signature 沒變，切換相簿頁與工作相簿頁時直接重用 catalog；只有資料夾選擇真的改變時才清空並重掃。這能避免 Fire 在使用者滑頁時看似「又從頭掃描」甚至觸發 ANR。

## 7. 跨來源 Bitmap 解碼與低記憶體策略

同一張圖片必須重新開啟兩次 InputStream：第一次只讀尺寸，第二次真正解碼。Stream 不能假設可以 reset。

```java
BitmapFactory.Options bounds = new BitmapFactory.Options();
bounds.inJustDecodeBounds = true;
InputStream first = openPhotoInputStream(source);
BitmapFactory.decodeStream(first, null, bounds);
first.close();

int sample = choosePowerOfTwoSample(bounds.outWidth, bounds.outHeight,
        screenWidth, screenHeight);
BitmapFactory.Options options = new BitmapFactory.Options();
options.inSampleSize = sample;
options.inPreferredConfig = Bitmap.Config.RGB_565;
options.inDither = true;

InputStream second = openPhotoInputStream(source);
Bitmap bitmap = BitmapFactory.decodeStream(second, null, options);
second.close();
```

建議：

- 先讀 bounds，再依螢幕大小取樣；不要解原圖。
- `RGB_565` 約為 `ARGB_8888` 一半記憶體，適合相簿時鐘背景；若產品要高品質透明度或精細漸層，再改用 `ARGB_8888`。
- 遇到 `OutOfMemoryError` 時，可提高 `inSampleSize` 後重試；不要讓單張壞圖終止整個輪播。
- 換圖或離頁時取消舊工作、清掉 `ImageView`，並釋放不再使用的 Bitmap。不要同時快取大量全尺寸 Bitmap。

## 8. 使用者介面與錯誤訊息

建議的訊息：

- 掃描開始：`正在讀取相簿…`
- 找到首張後：可直接隱藏訊息或顯示第一張。
- 完整索引中：不需要顯示「正在載入 XXXX 張」，避免製造焦慮。
- 無照片：明確提示重新選取資料夾。
- URI 失效：`SD 卡資料夾權限已失效，請重新選取。`

不要在背景掃描期間阻擋滑頁、觸控或時鐘更新。

## 9. LittleClock 移植順序

1. 保留既有的 API 17 `File` 資料夾流程。
2. 新增 API 21+ 系統資料夾選擇按鈕與 Tree URI 持久授權。
3. 將選取來源改成「路徑或 URI」的統一資料模型。
4. 將掃描改到背景，加入 generation 取消令牌。
5. 先發布第一張，之後完成完整索引。
6. 加上 folder signature，避免切頁重掃。
7. 讓 Bitmap 解碼只透過 `openPhotoInputStream()`，並使用 bounds + sample。
8. 在 Android 4.2 紅米、Fire OS SD 卡、現代 Android 三類裝置各測一次。

## 10. 最低測試矩陣

| 裝置情境 | 必測結果 |
|---|---|
| Android 4.2／API 17 | legacy 資料夾可選、可遞迴讀取、無 SAF 呼叫 |
| Fire OS + 實體 SD 卡 | 系統 picker 可進 SD 卡、重新開 App 後仍可讀；若不能持久化則有明確重選提示 |
| Android 11+ | Tree URI 可讀、旋轉／切頁不會重掃、授權失效不會閃退 |
| 上萬張照片 | 首張先顯示、完整索引在背景完成、沒有張數截斷 |
| 切換資料夾或離頁 | 舊掃描／舊 Bitmap 不會覆蓋新畫面，也不會造成 ANR |

## 不要做的事

- 不要在 API 21+ 把 SD 卡資料夾 URI 硬轉成 `File` 路徑。
- 不要在 UI thread 走完整遞迴掃描。
- 不要因為掃描慢而截斷相簿總張數。
- 不要假設 `takePersistableUriPermission()` 在所有 Fire OS 都成功。
- 不要每次 Activity resume 或相簿頁切換都重新掃描。
- 不要只為了讀取一張背景圖而解碼原始解析度。

## QuietPanel 對照來源

可參考目前專案的實作位置：

- `android/app/src/main/java/com/quietpanel/client/PhotoFolderActivity.java`
  - 系統資料夾選擇、URI 授權與選取儲存。
- `android/app/src/main/java/com/quietpanel/client/MainActivity.java`
  - `PhotoSource`、背景掃描、DocumentsContract 遍歷、catalog signature、解碼與 generation 取消。

移植時請複製設計原則與必要程式段落，不要直接整個複製 `MainActivity`；LittleClock 應把這些內容放入它自己的相簿／背景模組。
