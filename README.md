# QuietPanel v9.0.12

## v9.0.12 版本修正

- 修正第三頁時鐘／日期面板在重新啟動後回到左上角的問題。
- 時鐘拖曳位置會持久化保存，進入第三頁並完成版面配置後自動恢復。

QuietPanel 把 Android 手機變成 Windows 系統監控、快捷控制與相片時鐘面板。v9 將原本分開維護的 USB ADB 與 Bluetooth PAN／Wi-Fi 版本重新合併：Android 畫面、時鐘及通訊協定只有一份，Windows Bridge 依需求使用 ADB 或 Wireless 建置。

支援 Android 4.2（API 17）至目前的 Android；主要實機為 Xiaomi 2013023／Android 4.2.2。

## 連線模式

同一個 Android APK 可接受四種模式，點標題列的模式按鈕切換：

- `ADB`：只監聽 `127.0.0.1:27183`，由 USB ADB Port Forward 接入。新安裝預設使用此模式。
- `BT`：啟用 Bluetooth PAN，並在所有 IP 介面監聽。
- `WiFi`：不啟用 PAN，只等待區域網路連線。
- `AUTO`：啟用 PAN，同時接受 ADB、Bluetooth PAN 或 Wi-Fi 的 TCP 連線。

Windows 端有兩個 Bridge：

- `QuietPanelBridge-v9.0.12-ADB.exe`
- `QuietPanelBridge-v9.0.12-Wireless.exe`

兩者共用系統監測、控制、天氣、JSON 協定與系統匣程式碼；只替換連線建立方式。

Android 發行檔也分為兩個變體：

- `QuietPanel-v9.0.12-Public.apk`：只含可公開散布的字型，沒有 Storopia。
- `QuietPanel-v9.0.12-Private-Storopia.apk`：個人測試用，才含 Storopia；請勿公開散布。

## v9 相片時鐘

LittleClock v3.0.2 的時鐘能力已整合到 QuietPanel 相片頁：

- 時間與日期可分別顯示或隱藏。
- 時鐘可拖曳、雙指縮放並記住橫／直向位置。
- 可選字型（含芫荽 Iansui）及半透明底板。
- 每三分鐘微幅位移，降低長時間顯示的烙印風險。
- 可設定夜間暗屏時段；觸控後暫時喚醒 30 秒。
- 可選環境光自動亮度。
- 低耗電模式停用相片平移動畫。
- 支援每日或單次鬧鐘、貪睡及新舊 Android 響鈴流程。
- 顯示由 Windows Bridge 提供的天氣圖示、溫度及選用地名；無地名時可與日期同列精簡顯示。

相片仍只從手機本機選定的資料夾讀取，不會傳到電腦。

## 電腦端天氣

Bridge 使用現代 HTTPS 向 Open-Meteo 查詢，手機不直接連天氣服務。這避開 Android 4.2 的 TLS 與憑證限制，也讓 ADB、PAN 及未來 BLE 共用相同資料來源。

預設設定位於 Bridge 同目錄的 `QuietPanelBridge.json`：

```json
{
  "bluetooth_device": "68:DF:DD:0C:C1:AE",
  "phone_ip": "192.168.44.1",
  "enabledPages": [0, 1, 2, 3, 4, 5, 6],
  "weather": {
    "enabled": true,
    "location": "Taipei",
    "latitude": 25.033,
    "longitude": 121.5654
  }
}
```

天氣每 60 分鐘更新一次，失敗後每 10 分鐘重試。快取保存在 `QuietPanelWeatherCache.json`；超過六小時未成功更新時，手機隱藏過期資料。天氣只在連線建立及內容更新時傳送，不加入每秒監控封包。`location` 是顯示標籤，請填英文名稱（例如 `Taipei`、`New Taipei City`），以便完整套用時鐘字型；座標才是實際查詢位置。

## 使用方式

### USB ADB

1. 手機開啟 USB 偵錯並允許電腦的 RSA 授權。
2. 執行 `dist\Install-Android.cmd`。
3. 手機標題列選擇 `ADB`。
4. 執行 `dist\Start-QuietPanel-ADB.cmd`。

### Bluetooth PAN

1. 在 Windows 與手機完成一般藍牙配對。
2. 手機選擇 `BT` 或 `AUTO`。
3. 目標 Xiaomi 2013023 第一次使用或 Windows 重灌後，執行 `dist\Setup-Bluetooth-PAN.cmd`，將 Windows PAN 設為 `192.168.44.2/24`。
4. 執行 `dist\Start-QuietPanel-Wireless.cmd`。

其他手機應先使用 DHCP，不要直接套用紅米專用的靜態位址。若要還原，執行 `Setup-Bluetooth-PAN.cmd -RestoreDhcp`。

## 七個頁面

1. 系統：CPU、記憶體、網路與最近五分鐘歷史。
2. 儲存：最多四個 Windows 磁碟的容量與使用率。
3. 相簿：手機本機相片、可調整時鐘及天氣。
4. 工作相簿：相片時鐘及工作快捷鍵。
5. NASA：每日天文圖片與說明。
6. Macro：靜音、媒體、截圖、桌面及鎖定等操作。
7. 快捷：視窗、複製、貼上、復原及重做。

## 隱私與網路

- ADB 模式的控制通道只監聽手機 localhost。
- Wireless 模式會開啟手機 TCP `27183`、`27184` 及 UDP `27185`；只應在信任的 Wi-Fi 或已配對 PAN 使用。
- Bridge 會連線 Open-Meteo 取得天氣，並可連線 NASA APOD 取得每日圖片。
- 程式不收集或上傳私人照片、截圖、輸入內容或系統監控紀錄。
- NASA API Key 只從本機環境變數 `QUIETPANEL_NASA_API_KEY` 讀取。

## 建置

需求：Rust、JDK 17、Android SDK 36。

```powershell
.\build.ps1
```

建置會執行：

1. Rust 格式檢查。
2. ADB Bridge 測試與 Release 建置。
3. Wireless/PAN Bridge 測試與 Release 建置。
4. Android Lint 與 Release APK 建置。
5. 組裝啟動腳本、PAN 設定工具及 SHA-256 清單。

完整設計及移植紀錄見 [v9 架構報告](docs/QuietPanel-v9-Clock-Weather-Dual-Transport.md)。PAN 的硬體診斷經過見 [v8 Bluetooth PAN 診斷](docs/QuietPanel-v8-Bluetooth-PAN-Diagnosis-2026-07-26.md)。

> 此專案是使用者與 AI 協作的私人學習專案。Android APK 使用本機 debug signing key，不應視為正式商店發行簽章。
