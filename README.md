# QuietPanel v6.4.3

QuietPanel 把 Android 4.2.2 手機變成 USB 系統監控與快捷控制面板。

> **AI 程式寫作練習／個人自用專案。** 本儲存庫不是正式產品，也不尋求功能請求、問題回報、技術支援或 Pull Request。請勿為此專案投入額外的社群維護、除錯或支援時間。

此專案由使用者與 AI 協作開發，內容僅供學習與私人設備使用；請自行評估執行巨集與系統控制功能的風險。

## 隱私與網路行為

- 手機與電腦以 USB、ADB Port Forward 在本機傳輸資料；不開放區域網路或網際網路控制埠。
- 程式不收集、不上傳使用者檔案、輸入內容、截圖或系統監控紀錄。
- 唯一的外部網路請求是 Windows Bridge 每六小時最多一次向 NASA APOD 取得當日公開圖片與說明。
- NASA API Key 不寫入原始碼。若使用者自行設定 `QUIETPANEL_NASA_API_KEY`，它只會從本機環境變數讀取，且不會被 Git 追蹤。

## 開發與測試環境

此版本在下列私人設備與軟體環境開發、編譯及實機驗證：

| 類別 | 環境 |
| --- | --- |
| 電腦 | Windows 10 教育版 64 位元（10.0.19045） |
| 手機 | Xiaomi 2013023，Android 4.2.2（API 17） |
| 電腦端 | Rust 1.97.1、Windows Rust Bridge |
| Android 建置 | Microsoft OpenJDK 17.0.12、Android compileSdk 36、Gradle 9.1.0 |
| 連線 | USB 偵錯、ADB Port Forward `tcp:27183` 與 `tcp:27184` |

## 六個頁面

1. 系統：CPU、記憶體、網路下載與上傳速率，以及最近五分鐘歷史曲線。
2. 儲存：最多四個 Windows 磁碟的容量與使用率。
3. 相簿：全螢幕隨機輪播外接 SD 卡 `/sdcard/QuietPanel/Photos` 中的 JPG、JPEG 與 PNG，右下角以大型雙行時鐘顯示時間與日期。
4. NASA：每日天文圖片、標題、日期、版權與英文說明；圖片由 Win10 下載後經 USB 傳送。
5. Macro：靜音、YouTube、全螢幕截圖、顯示桌面、播放／暫停、音量加、音量減、鎖定電腦。
6. 快捷：上一視窗、工作檢視、最小化視窗、關閉視窗、複製、貼上、復原、重做。

左右滑動換頁。從任何按鈕開始滑動都會取消按鈕點擊，不會同時執行指令。「關閉視窗」必須長按，避免誤觸。

相簿頁只在畫面停留於第三頁時輪播，每 45 秒隨機切換一張。比例與螢幕不同的照片會保持滿版，沿著超出畫面的方向做非常緩慢的微幅平移；每張照片只移動完整可平移距離的 20%，不強求在單次播放中呈現所有裁切區域。平移約以 15fps 更新，在維持慢速動態質感的同時降低舊手機的持續運算負擔；換圖時以較慢的淡出與淡入銜接。進入相簿頁會隱藏 QuietPanel 標題列與頁面指示；右下角以大型雙行時鐘顯示時間、日期與星期，不顯示秒。程式一次只解碼一張圖片以降低舊手機記憶體用量。請事先將照片縮小後放入外接 SD 卡 `/sdcard/QuietPanel/Photos`；離開相簿頁時輪播、平移與時鐘更新會暫停並釋放相簿圖片。相簿完全由手機本機處理，不經 Windows Bridge 傳輸。

CPU 連續 30 秒達到 90% 時會顯示過高警告；降至 85% 以下解除。磁碟使用率達 90% 時會顯示空間不足警告。歷史資料只保存在手機記憶體中，App 重啟後重新累積。

## 使用方式

1. 手機開啟 USB 偵錯並以 USB 連接電腦。
2. 第一次使用時執行 `dist\Install-Android.cmd`。
3. 每次使用時執行 `dist\Start-QuietPanel.cmd`。

Bridge 會自行偵測單一 Android 裝置、建立 `tcp:27183` ADB Forward，並在連線中斷後重新偵測。若同時連接多台 Android 裝置，Bridge 會停止選擇並顯示裝置清單，避免把指令送到錯誤裝置。

NASA APOD 最多每六小時檢查一次，Win10 與手機都只保留最新一張快取。一般系統資料使用 `tcp:27183`；圖片使用獨立的 `tcp:27184` 二進位通道，避免把圖片編碼成大型 JSON。若當日內容是影片，第四頁顯示 NASA 提供的影片縮圖。預設使用 NASA `DEMO_KEY`；可用環境變數 `QUIETPANEL_NASA_API_KEY` 設定自己的免費 API Key。

全螢幕截圖使用 Windows + Print Screen，交由 Windows 儲存到系統設定的螢幕擷取畫面位置。

## 建置

需求：Rust、JDK 17、Android SDK 36、Gradle Wrapper 所需檔案。

```powershell
.\build.ps1
```

建置會依序執行 Rust 格式檢查與測試、Release 編譯、Android Lint 與 Release APK 編譯，最後把 APK、EXE、ADB 與 SHA-256 清單放到 `dist`。

Android Release APK 目前使用本機 debug signing key，適合這台私人裝置直接安裝，不用於公開商店發行。
