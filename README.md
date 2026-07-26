# QuietPanel v8.1.6

QuietPanel 把 Android 4.2.2 手機變成 Windows 系統監控與快捷控制面板。v8.1.6 的無線版以同一套 TCP 協定支援 Wi-Fi 與 Bluetooth PAN；藍牙不再使用 RFCOMM／虛擬 COM 埠。

## v8.1.6 無線連線

- 手機在 `TCP 27183` 等待 Bridge，並每兩秒從所有可用介面（包含 Wi-Fi 與 Bluetooth PAN）送出探索封包。
- Windows Bridge 先嘗試設定檔中的固定 IP，再等待探索封包；固定 IP 失效不會阻止自動探索。
- Bridge 啟動時會先替已配對手機啟用 Windows 的 NAP 服務，再透過 Windows 10 內建 `bthpanapi.dll` 建立 PAN；不經過 COM4／COM5，也不會把「介面存在」誤判為「已連線」。
- Bridge 只有在 TCP 不通時才嘗試 PAN，舊 Windows PAN 呼叫卡住時最多送出一次受控喚醒，失敗採指數退避；已連線時不會反覆要求舊藍牙控制器建立同一條 PAN。
- `dist\QuietPanelBridge.json` 的 `bluetooth_device` 可填手機名稱或藍牙 MAC。這台實測手機預設為 `68:DF:DD:0C:C1:AE`。
- 手機選擇 `AUTO` 或 `BT` 時會要求 Android 啟用藍牙網路共用。目標 MediaTek Android 4.2.2 ROM 會只對已配對的「電腦」裝置自動接受 PAN；其他 ROM 若未開放 PAN API，請在 Android「網路共用與可攜式無線基地台」手動勾選「藍牙網路共用」。
- 目標紅米韌體把手機設為 `192.168.44.1`，卻發出錯誤網段的 DHCP。這台設備第一次使用前須在 `dist` 執行 `Setup-Bluetooth-PAN.cmd`，把 Windows PAN 設為 `192.168.44.2/24`。

## 產出檔案

- `dist\QuietPanel-v8.1.6.apk`：Android App。
- `dist\QuietPanelBridge-v8.1.6.exe`：Wi-Fi／Bluetooth PAN Bridge。
- `dist\QuietPanelBridge.json`：手機名稱／MAC 與頁面選擇設定。
- `dist\Setup-Bluetooth-PAN.cmd`：目標紅米的 Windows PAN 位址設定／DHCP 還原工具。
- `dist\SHA256SUMS.txt`：產出檔案雜湊。

完整的根因、所有診斷經過、驗收證據與其他機器判斷表請見 [`docs/QuietPanel-v8-Bluetooth-PAN-Diagnosis-2026-07-26.md`](docs/QuietPanel-v8-Bluetooth-PAN-Diagnosis-2026-07-26.md)。

## 系統匣與頁面選擇

Bridge 啟動後常駐 Windows 系統匣，不再顯示主控台視窗。點選系統匣圖示會看到六個頁面的勾選選單；取消勾選的頁面會立刻從手機的左右滑動順序中移除。Bridge 至少保留一頁，設定會儲存在 Bridge 同目錄的 `QuietPanelBridge.json`。

## 多目錄相簿

在手機第三頁點一下照片，左上角才會暫時出現「相簿資料夾」按鈕。選擇畫面可以瀏覽 SD 卡並同時勾選多個目錄；每個選定目錄都會包含其子目錄。設定只保存在手機端，照片仍完全由手機讀取與播放。

## 電腦螢幕同步節電

Bridge 會偵測 Windows 螢幕的開關狀態。電腦螢幕關閉時，手機會停止相簿動畫並解除「保持常亮」，讓 Android 依手機的螢幕逾時設定關閉螢幕；電腦螢幕重新亮起時，手機會自動亮起並回到 QuietPanel。

請在手機的開發人員選項關閉「充電時不休眠／Stay awake」，並設定合適的螢幕逾時（例如 10 分鐘）；否則 Android 仍可能在 USB 充電時保持螢幕亮著。

> **AI 程式寫作練習／個人自用專案。** 本儲存庫不是正式產品，也不尋求功能請求、問題回報、技術支援或 Pull Request。請勿為此專案投入額外的社群維護、除錯或支援時間。

此專案由使用者與 AI 協作開發，內容僅供學習與私人設備使用；請自行評估執行巨集與系統控制功能的風險。

## 隱私與網路行為

- v8 無線版會在手機的本機網路介面開啟 `TCP 27183`、`TCP 27184`，並以 `UDP 27185` 探索；請只在信任的 Wi-Fi 或已配對藍牙 PAN 上使用。
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
| 連線 | Bluetooth PAN／Wi-Fi，TCP `27183`、`27184`，UDP `27185` |

## 六個頁面

1. 系統：CPU、記憶體、網路下載與上傳速率，以及最近五分鐘歷史曲線。
2. 儲存：最多四個 Windows 磁碟的容量與使用率。
3. 相簿：全螢幕隨機輪播手機端所選的一個或多個 SD 卡目錄中的 JPG、JPEG 與 PNG，右下角以大型雙行時鐘顯示時間與日期。
4. NASA：每日天文圖片、標題、日期、版權與英文說明；圖片由 Win10 下載後經 USB 傳送。
5. Macro：靜音、YouTube、全螢幕截圖、顯示桌面、播放／暫停、音量加、音量減、鎖定電腦。
6. 快捷：上一視窗、工作檢視、最小化視窗、關閉視窗、複製、貼上、復原、重做。

左右滑動換頁。從任何按鈕開始滑動都會取消按鈕點擊，不會同時執行指令。「關閉視窗」必須長按，避免誤觸。

相簿頁只在畫面停留於第三頁時輪播，每張照片停留時間可在相簿設定中調整為 10～300 秒。比例與螢幕不同的照片會保持滿版，沿著超出畫面的方向做非常緩慢的微幅平移；平移約以 15fps 更新。進入相簿頁會隱藏標題列與頁面指示；大型雙行時鐘顯示時間、日期與星期，不顯示秒，並可拖曳與縮放（75%～250%，會保存）。

v6.8.13-test 的相簿設定採緊湊的可捲動選單，提供 13 種內建／子集字型。中文子集字型使用中文日期，拉丁子集字型使用英文日期，避免日期缺字；字型資產損壞或不存在時會回退至相近的 Android 系統字型。時鐘在字型、縮放或螢幕尺寸改變後會重新限制於畫面內，空間不足時只縮小實際顯示比例，不改寫使用者保存的偏好。另提供柔和背景、智慧焦點、自適應時鐘底板顏色、拍立得框及 3D 轉場，五項均預設關閉；未啟用時不建立背景圖片、不分析像素，也不執行 3D 動畫。程式仍一次只解碼一張主要照片，離開相簿頁會停止工作並釋放圖片資源。

v6.8.13-test 將相簿慢移調整為 10fps 與 13% 行程，並延長淡入淡出及 3D 轉場，呈現更緩慢的節奏。時間與換圖改採事件式排程；隱藏的監控頁仍保留每秒歷史樣本與警告判定，但延後文字格式化及圖表重繪，切回頁面時一次刷新。

公開授權字型可由 `android/prepare_photo_fonts.py` 重新下載並建立最小字元子集。Storopia 不會由腳本下載，其本機子集仍因重散布授權未確認而被 Git 忽略；含 Storopia 的 APK 僅供個人測試，請勿分發。

CPU 連續 30 秒達到 90% 時會顯示過高警告；降至 85% 以下解除。磁碟使用率達 90% 時會顯示空間不足警告。歷史資料只保存在手機記憶體中，App 重啟後重新累積。

## 使用方式

1. 第一次安裝仍可用 USB 偵錯執行 `dist\Install-Android.cmd`；安裝完成後資料通訊不需要 USB。
2. 在 Windows 與手機完成一般藍牙配對。
3. 這台 Xiaomi 2013023 第一次使用或 Windows 重灌後，在 `dist` 執行 `Setup-Bluetooth-PAN.cmd` 並接受管理員提示。其他手機應先使用 DHCP，不要在未確認其 PAN 網段前套用此靜態位址。
4. 手機選 `AUTO` 或 `BT`，並確認「藍牙網路共用」已開啟。
5. 執行 `dist\QuietPanelBridge.exe`；Bridge 會出現在 Windows 系統匣，自動登錄手機的 NAP 服務並建立 PAN 連線。
6. 若更換手機，修改同目錄 `QuietPanelBridge.json` 的 `bluetooth_device` 與（需要時）`phone_ip`。可填裝置名稱，例如 `紅米手機`，或 MAC，例如 `68:DF:DD:0C:C1:AE`。

若不再使用目標紅米的靜態設定，執行 `Setup-Bluetooth-PAN.cmd -RestoreDhcp`。設定工具第一次執行會先保存原介面資料到 `Bluetooth-PAN-before-QuietPanel.json`。

USB ADB 版仍可用 `Start-QuietPanel-v6-ADB.cmd` 作為獨立備援；v8 無線 Bridge 不建立 ADB Forward。

NASA APOD 最多每六小時檢查一次，Win10 與手機都只保留最新一張快取。一般系統資料使用 `tcp:27183`；圖片使用獨立的 `tcp:27184` 二進位通道，避免把圖片編碼成大型 JSON。若當日內容是影片，第四頁顯示 NASA 提供的影片縮圖。預設使用 NASA `DEMO_KEY`；可用環境變數 `QUIETPANEL_NASA_API_KEY` 設定自己的免費 API Key。

全螢幕截圖使用 Windows + Print Screen，交由 Windows 儲存到系統設定的螢幕擷取畫面位置。

## 建置

需求：Rust、JDK 17、Android SDK 36、Gradle Wrapper 所需檔案。

```powershell
.\build.ps1
```

建置會依序執行 Rust 格式檢查與測試、Release 編譯、Android Lint 與 Release APK 編譯，最後把 APK、EXE、ADB 與 SHA-256 清單放到 `dist`。

Android Release APK 目前使用本機 debug signing key，適合這台私人裝置直接安裝，不用於公開商店發行。
