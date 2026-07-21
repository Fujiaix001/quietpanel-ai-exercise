# QuietPanel 結案報告（2026-07-22）

## 1. 專案結論與目前可用狀態

QuietPanel 是將一台 **Xiaomi 2013023（紅米一代、Android 4.2.2 / API 17）** 作為橫向常駐資訊面板的私人專案。Windows 10 電腦端的 Rust Bridge 經 USB／ADB 將系統資訊、巨集指令與 NASA APOD 資料送至手機；相簿則完全由手機從 SD 卡讀取。

本次結案時，正式穩定版本為 **v6.5.0（ADB 方案）**。手機已安裝 `versionName=6.5.0`、`versionCode=650`，PC 端 Bridge 已可透過 ADB 運作。此版本是目前這台舊手機最可靠、資源最節省的使用方式。

本專案暫停於可日常使用的狀態，沒有待修的必要功能。未來如重啟開發，請先以本文件、根目錄 `README.md` 與 Git 標籤為準。

## 2. 原始碼、版本與發行檔位置

| 項目 | 位置／內容 |
| --- | --- |
| 原始碼工作目錄 | `C:\Users\hsuchungming\Desktop\WorkSpace\QuietPanel_v6` |
| GitHub | <https://github.com/Fujiaix001/quietpanel-ai-exercise> |
| 正式分支 | `main` |
| 結案提交 | `2968f7c`（回復 v7 USB 網路共用） |
| 穩定功能基準 | `7b47816`（v6.5.0 ADB 功能內容） |
| 穩定版標籤 | `v6.5.0`（`d4f65ee`；後續有相簿按鈕樣式與系統頁磁碟 I/O 提交） |
| 發行目錄 | `dist\` |
| Android APK | `dist\QuietPanel-v6.5.0.apk` |
| Windows Bridge | `dist\QuietPanelBridge.exe` |
| 安裝腳本 | `dist\Install-Android.cmd` |
| 日常啟動腳本 | `dist\Start-QuietPanel.cmd` |

`dist\` 是建置產物，不納入 Git。結案當天該目錄內可能仍有先前實驗留下的 `QuietPanel-v7.0.0.apk`；日後手動安裝時請明確選擇 `QuietPanel-v6.5.0.apk`，不要依檔名萬用字元猜測版本。

## 3. 目前架構

```text
Windows 10 PC
  QuietPanelBridge.exe（Rust，系統匣常駐）
    ├─ sysinfo：CPU、記憶體、網路、磁碟 I/O
    ├─ Windows API：巨集與快捷鍵
    ├─ NASA APOD：最多每 6 小時更新一次
    ├─ 螢幕開關偵測：同步手機節電
    └─ ADB Forward：tcp:27183、tcp:27184
              │ USB／USB 偵錯
Android 4.2.2 手機
  QuietPanel APK（Java，橫向全螢幕）
    ├─ 系統、儲存、相簿、NASA、Macro、快捷六頁
    ├─ 系統資料／指令：127.0.0.1:27183 JSON 行協定
    ├─ NASA 圖片：127.0.0.1:27184 二進位通道
    └─ 相簿：手機 SD 卡本機讀取，不經 Bridge
```

USB 偵錯與 ADB 必須保持啟用，才能讓 Bridge 建立轉送與接收巨集。此限制是為了換取紅米一代上已驗證的長時間穩定性。

## 4. 六個頁面與已完成功能

1. **系統**：CPU、記憶體、網路上傳／下載、磁碟讀出／寫入，以及最近五分鐘的歷史曲線與高使用率警示。
2. **儲存**：最多四個 Windows 磁碟的容量、使用率與空間不足警示。
3. **相簿時鐘**：手機選擇的一個或多個 SD 卡資料夾（含子資料夾）中的 JPG／JPEG／PNG。滿版裁切、淡入淡出、極慢微幅平移、大型右下雙行時間與日期；不顯示秒。點一下相簿畫面才出現「相簿資料夾」按鈕。預設相容路徑為 `/sdcard/QuietPanel/Photos`。
4. **NASA**：APOD 圖片、標題、日期、版權與英文說明；遇影片日顯示 NASA 提供的縮圖。
5. **Macro**：靜音、YouTube、全螢幕截圖、顯示桌面、播放／暫停、音量加減、鎖定電腦。
6. **快捷**：上一視窗、工作檢視、最小化、長按關閉視窗、複製、貼上、復原、重做。

Bridge 的 Windows 系統匣選單可勾選要在手機顯示的頁面；至少保留一頁，設定存於 Bridge 同目錄的 `QuietPanelBridge.json`。

## 5. 日常使用與故障排除

### 日常啟動

1. 手機以 USB 連至 PC，開啟「USB 偵錯」。
2. 需要重新安裝時，明確安裝 `dist\QuietPanel-v6.5.0.apk`；MIUI 覆蓋安裝時可能需在手機端確認。
3. 執行 `dist\Start-QuietPanel.cmd`。Bridge 應出現在 Windows 系統匣，不顯示主控台視窗。
4. Bridge 會自動偵測**單一** ready 裝置，並建立 `tcp:27183`、`tcp:27184` 的 ADB Forward。

### 常見問題

| 現象 | 優先處理 |
| --- | --- |
| 手機頁面不更新、巨集沒反應 | 確認 USB 偵錯、USB 線與 `adb devices` 是否顯示 `device`；重新啟動 Bridge。 |
| ADB 找不到裝置或 Forward 消失 | 關閉 Android Studio、模擬器或其他可能使用不同 adb 的工具後，再重啟 Bridge／ADB。 |
| 同時接多支 Android 裝置 | Bridge 會拒絕任意選擇，以免送錯指令；只保留目標手機。 |
| 手機無法如預期熄屏 | 關閉開發人員選項的「充電時不休眠／Stay awake」，並設定手機螢幕逾時，例如 10 分鐘。 |
| 相簿沒有照片 | 在手機第三頁點一下畫面，開啟資料夾選擇器；確認 SD 卡掛載、資料夾有支援格式圖片。 |
| NASA 失敗 | 應保留上一張快取；檢查 PC 網路與 NASA API 限流，不應因此中斷其他頁面。 |

## 6. 重要程式檔案導覽

### Windows Bridge（`bridge/src/`）

| 檔案 | 職責 |
| --- | --- |
| `main.rs` | 主迴圈、連線、每秒系統狀態、指令分派。 |
| `adb.rs` | 單一裝置偵測與 ADB Port Forward 建立／重連。 |
| `metrics.rs` | CPU、記憶體、網路與磁碟讀寫統計。 |
| `actions.rs` | Windows 巨集與鍵盤／視窗 API 操作。 |
| `apod.rs` | NASA APOD 下載、快取與圖片傳送。 |
| `display.rs` | Windows 螢幕狀態偵測，供手機同步節電。 |
| `protocol.rs` | JSON 與 NASA 二進位傳輸格式。 |
| `tray.rs` | 系統匣與可見頁面選單。 |
| `settings.rs` | `QuietPanelBridge.json` 的讀寫。 |

### Android App（`android/app/src/main/java/com/quietpanel/client/`）

| 檔案 | 職責 |
| --- | --- |
| `MainActivity.java` | 六頁 UI、資料呈現、相簿時鐘與節電處理。 |
| `TransportServer.java` | 系統資料與巨集的本機 JSON 接收端。 |
| `ApodServer.java` | NASA 圖片二進位接收端。 |
| `PhotoFolderActivity.java` | SD 卡相簿資料夾的多選與子資料夾掃描。 |
| `SwipePager.java` | 左右滑動切頁，避免與按鈕誤觸衝突。 |
| `HistoryGraphView.java` | 系統歷史曲線繪製。 |

## 7. 資源、隱私與安全設計

- 系統監控每秒傳送小型 JSON；手機只保留 300 筆（五分鐘）曲線於記憶體。
- 相簿一次只解碼一張圖片；僅在第三頁播放，離開即停止動畫、時鐘與圖片資源。
- 相簿慢速平移約 15 fps，且只移動完整可平移距離的 20%，目的在維持動態質感而非強迫看完整張圖。
- NASA 圖片只保留最新一張，Bridge 最多每六小時請求一次，圖片上限 6 MB。
- 實測待機時 Bridge 約 14–15 MB 工作集、CPU 接近 0%；相簿的主要負擔是手機螢幕本身，程式動畫負擔低。
- 通訊只走 USB／ADB localhost Forward，不開放 LAN 或網際網路控制埠。
- 唯一外部請求為 NASA APOD。若設 `QUIETPANEL_NASA_API_KEY`，只能放在本機環境變數，禁止提交 Git。

## 8. v7 USB 網路共用實驗：已封存，不作正式方案

曾開發 **v7.0.0**，目標是以 Android USB 網路共用（RNDIS）直接 TCP 取代 ADB，讓日常使用不需開發人員選項。

結果是：紅米一代在 `192.168.42.0/24` USB 網段上曾成功傳送系統資料、巨集與 NASA 圖片，但 Windows 10 事件紀錄多次顯示 `Remote NDIS based Internet Sharing Device` 網路介面被移除或重設。介面消失時 Bridge 仍活著，卻失去資料通道，手機巨集與系統資料都會停止；介面重建後才恢復。

這是舊手機的 USB 網路共用／驅動層不穩定，不是 QuietPanel UI 或協定錯誤。相同長時間情境下 ADB 穩定，因此主線以提交 `2968f7c` 非破壞式回復 ADB。

v7 保留位置：

- Git 標籤：`v7.0.0`（提交 `2fe403d`）
- GitHub 分支：`archive/v7-usb-tethering`
- 實驗說明：該分支的 `docs/USB_TETHERING_EXPERIMENT.md`

未來可在較新的 Android 平板重新測試 USB 網路共用，或優先改用 Wi-Fi 區域網路；不建議再花時間嘗試讓這台紅米一代的 RNDIS 長時間穩定。

## 9. 建置與維護注意事項

正常建置命令：

```powershell
Set-Location C:\Users\hsuchungming\Desktop\WorkSpace\QuietPanel_v6
.\build.ps1
```

它原本會依序進行 Rust 格式檢查、Rust 測試／Release、Android Lint／Release，最後更新 `dist\`。

**已知建置問題：** 在結案當日，目前 Rust 版本對 `bridge/src/metrics.rs` 的格式判定與已提交 v6.5.0 原始碼不同，導致 `cargo fmt --all -- --check` 失敗；功能與編譯無關。為了讓已回復的穩定版保持內容完全一致，當日沒有將自動格式化結果提交。若未來要正式維護，先確認 `cargo fmt` 只造成排版差異後，再另開一個小提交處理即可。

結案時已以以下方式成功重建 v6.5.0 APK 與 Bridge：

```powershell
Set-Location .\bridge
cargo test --locked --offline
cargo build --release --locked --offline

Set-Location ..\android
.\gradlew.bat :app:assembleRelease --no-daemon --offline
```

Android APK 使用本機 debug signing key，適合私人裝置直接覆蓋安裝，不可視為可上架的正式簽章。

## 10. 未來可能方向（非目前待辦）

### 藝術作品頁

使用者提出過「每日增加一頁藝術家作品，底部顯示藝術家帳號」的想法。建議讓**另一個獨立的 PC 爬蟲／資料取得程式**處理來源、下載、授權與快取，再輸出固定格式資料給 Bridge；不要讓舊手機或既有 Bridge 直接爬網站。

建議資料介面範例：

```json
{
  "imagePath": "C:\\QuietPanel\\ArtCache\\2026-07-22.jpg",
  "artist": "Artist Name",
  "account": "@artist_account",
  "sourceUrl": "https://example.com/source"
}
```

Bridge 只讀取已準備好的圖片與 JSON，手機只顯示滿版圖與署名，因此對現有架構與手機資源的衝擊很低。困難主要在第三方網站的爬取限制、授權與來源變動；優先考慮官方 API、RSS 或公開授權來源。頁面數量應限制在最近 7–30 天，避免手機長期累積無限資料。

### 新裝置

較新的 Fire HD 8 或 Android 平板可望共用大部分 Android UI 與協定，但應重新驗證：Android 儲存權限、畫面比例、效能、Wi-Fi／USB 傳輸策略，以及是否需要保留 API 17 相容性。不要假設 v7 的 USB 網路共用結果會在所有裝置上相同。

## 11. 協作與版本控制原則

- 使用繁體中文溝通。
- 採一次只加一項小功能的版本演進；先實機驗證，再進下一項。
- 不要未經要求擴大功能、刪除使用者資料、覆寫既有工作，或改變 GitHub 可見性。
- 平常可先保留本機提交；只有使用者明確說「推到 GitHub」時再集中推送。
- 涉及外部資料、爬蟲、帳號或圖片時，要先處理來源授權、隱私與失敗時的降級行為。
- 以低資源、穩定常駐為優先；不要為了看似更炫的效果刻意提高這台舊手機的 CPU／GPU 使用率。

## 12. 重啟開發前的最短檢查清單

1. `git status --short --branch`：確認工作區乾淨、位於 `main`。
2. `adb devices -l`：確認只連接目標手機，狀態為 `device`。
3. 啟動 `dist\QuietPanelBridge.exe`，檢查系統匣圖示與手機系統頁更新。
4. 任何新功能先確認是否需修改 Android、Bridge 或兩端；相簿本機功能應盡量不經 Bridge。
5. 每個功能點獨立提交、實機驗證；需要發佈時再建立清楚標籤與 GitHub 推送。

---

本文件記錄的是結案當日的可運作狀態與已知限制。最重要的決策是：**此紅米一代固定使用 ADB，不再以 USB 網路共用作為日常連線方案。**
