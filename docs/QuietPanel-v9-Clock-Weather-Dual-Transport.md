# QuietPanel v9 時鐘、天氣與雙通訊架構紀錄

日期：2026-07-28

## 目標

將 LittleClock v3.0.2 的時鐘能力整合到 QuietPanel，同時消除 ADB v6.9.1 與 Bluetooth PAN v8.1.6 互相覆蓋的維護方式。天氣改由 Windows 端取得，以避免舊 Android 的 TLS、憑證及網路路由問題。

LittleClock 專案在移植期間保持唯讀。來源基準為本機提交 `788258b`（v3.0.2）。

## 移植前狀態

QuietPanel Git 歷史同時保存兩個產品方向：

- `4684297`：Bluetooth PAN v8.1.6，包含已在 Xiaomi 2013023 上驗證的 MediaTek PAN 授權與 Windows bthpanapi 修復。
- `8d0ab4d`：ADB v6.9.1，重新以 ADB 為主並改良工作頁配置。

兩版使用同一批檔案路徑，後一版因此在工作樹中移除了 PAN 類別。這使任何 UI 或時鐘更新都無法自然套用到兩版。

## v9 決策

### Android

Android 只維護一份 App。TCP Server 依模式綁定：

- ADB：`127.0.0.1:27183`
- AUTO／BT／Wi-Fi：`0.0.0.0:27183`

ADB Bridge 透過 Port Forward 連到同一個 Server；Wireless Bridge 透過手機 IP 連入。BT 與 AUTO 才要求啟用 PAN，Wi-Fi 不觸發 PAN。

已恢復並保留 v8.1.6 的：

- `BluetoothPanController`
- `WifiBeacon`
- MediaTek Android 4.2.2 PAN 自動授權保護
- 只對已配對電腦授權的限制

### Windows Bridge

Rust 共用同一份 `main.rs`、protocol、metrics、actions、tray、weather 與 settings：

- 預設 feature `adb` 建立 ADB 版本。
- `--no-default-features --features wireless` 建立 PAN／Wi-Fi 版本。
- Wireless 版本保留單一 PAN worker、八秒後最多一次受控 rescue 與退避機制。

這樣兩個 EXE 的差異由編譯 feature 決定，不再維護兩份主程式。

## 時鐘移植

移植並重新適配的 LittleClock 能力：

- 時間與日期個別開關
- 字型、背景、縮放與拖曳
- 依橫直向保存位置
- 防烙印微移
- 夜間暗屏與 30 秒觸控喚醒
- 環境光亮度
- 低耗電靜態相片模式
- 鬧鐘、貪睡、舊版 Activity 響鈴與新版 Foreground Service
- 本機繪製的天氣與鬧鐘圖示

未直接複製 LittleClock 的整個 PhotoClockActivity；QuietPanel 保留自己的七頁結構與相片播放器，避免全螢幕手勢和 SwipePager 衝突。

未納入本輪的 LittleClock 相簿附屬功能：

- 收藏與隱藏
- 完整媒體資料庫重新掃描架構
- 多種 3D 轉場
- 內建示範風景圖

## 天氣資料流

```
Open-Meteo HTTPS
        ↓
Windows weather.rs
        ↓
QuietPanelWeatherCache.json
        ↓ weather_state JSON
ADB Forward 或 Bluetooth PAN / Wi-Fi
        ↓
Android WeatherIconView
```

設定由 `QuietPanelBridge.json` 的 `weather` 物件提供。Bridge 啟動時先載入快取並立即開始背景更新；成功後等待 60 分鐘，失敗後等待 10 分鐘。資料超過六小時視為過期。

天氣使用獨立 `weather_state` 訊息，只在連線開始或 revision 改變時傳送，因此可直接延伸至未來 BLE，不需要每秒重送。

Android 仍保留 `INTERNET` 權限，因為本機 TCP／PAN Socket 也需要此權限；但 Android 不再直接呼叫天氣 API。

## 舊機與新機考量

- API 17 不會執行新 Android 的執行期藍牙或通知權限流程。
- API 31+ 會要求 `BLUETOOTH_CONNECT`。
- API 33+ 會要求通知權限供鬧鐘使用。
- API 29+ 鬧鐘使用 Foreground Service；較舊系統沿用直接啟動響鈴 Activity。
- Android 12+ 的精確鬧鐘仍受系統「鬧鐘與提醒」特殊權限控制；權限不可用時系統可能採用較不精確的排程。
- 低耗電模式停用相片 Matrix 平移，但保留時鐘、監控與天氣。

## 發行產物

- `QuietPanel-v9.0.0.apk`
- `QuietPanelBridge-v9.0.0-ADB.exe`
- `QuietPanelBridge-v9.0.0-Wireless.exe`
- `Start-QuietPanel-ADB.cmd`
- `Start-QuietPanel-Wireless.cmd`
- `Setup-Bluetooth-PAN.cmd`
- `QuietPanelBridge.json`
- `SHA256SUMS.txt`

## 驗證要求

提交前必須完成：

- Rust ADB 測試
- Rust Wireless/PAN 測試
- 兩種 Release EXE 建置
- Android Release、Lint 與 API 17 編譯
- Git diff whitespace 檢查
- 發行檔 SHA-256 產生
- 本機分支與 GitHub 遠端提交一致性確認

實機 PAN 的底層根因與 Windows／紅米處理方式仍以 v8 診斷報告為準。

## 2026-07-28 驗收結果

- Rust ADB：17 項測試通過。
- Rust Wireless/PAN：15 項測試通過，其中包含 bthpanapi export 載入與藍牙位址正規化。
- Android Release 與 Lint：成功，APK 為 `versionCode 9000`、`versionName 9.0.0`、`minSdk 17`。
- Xiaomi 2013023／Android 4.2.2：覆蓋安裝成功，MainActivity 啟動且沒有 FATAL EXCEPTION。
- ADB 實機：Windows 與手機 `127.0.0.1:27183` 建立連線。
- Open-Meteo 實際查詢：成功產生 Taipei 快取並由 Bridge 管理。
- 最終產物 SHA-256：
  - APK：`67A6D528DBA284DF7AE2864F867B7628EA3AFC571D95F98616D6ECDB13C57A6A`
  - ADB Bridge：`94DD1F8933A84857BAD1C977AC46B97757F7EADE5347190BBC97D8F010CD3684`
  - Wireless Bridge：`F8C77F6E1C69AECB5CC30088CC7FB9AADDAD2D2224277B3498866AC65DC3BDE7`

PAN 實機底層沿用 v8.1.6 已驗證程式。本輪沒有為了重測而中斷已建立的 ADB 驗收連線或重新觸發舊紅米的短時限 PAN 授權提示。
