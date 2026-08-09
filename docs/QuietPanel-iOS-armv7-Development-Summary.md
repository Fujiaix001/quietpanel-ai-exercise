# QuietPanel iOS armv7 開發摘要

- 更新日期：2026-08-09
- 公開分支：`ios-armv7-quietpanel`
- iPad 端版本：QuietPanel 0.6.0 build 21
- 主要實機：iPad mini 1／iOS 9.3.5／armv7／已越獄
- 主要開發主機：Apple Silicon MacBook Air M2

## 1. 目標與目前狀態

本移植的目標不是把 Android APK 直接轉成 iOS App，而是保留 QuietPanel 的使用方式，在第一代 iPad mini 上以原生 UIKit 重現主要功能，並把舊硬體不適合處理的工作交給 Mac。

目前版本已在實機完成五頁整合：Mac 系統資訊、延伸螢幕、相簿時鐘、相簿快捷工具及 NASA 每日天文圖。iPad 與 Mac 共用一條 USB／OpenDisplay 相容連線，Mac 端另有可見的控制器管理服務、頁面、天氣與 NASA 更新。

這個 repository 的 `ios/` 保存 iPad 客戶端。配套的 `QuietPanel Display` Mac 發送端是在 OpenDisplay 1.16.0 原始碼工作樹的 `quietpanel-visible` 分支維護；為避免把整個上游專案複製進來，目前沒有重複放入本 repository。兩端必須搭配使用，只有相簿輪播與已快取的 NASA 資料可在 Mac 暫時離線時繼續顯示。

## 2. 執行條件

### iPad 端

- 第一代 iPad mini，實機為 iOS 9.3.5、armv7、橫向 1024×768。
- 需要越獄，原因是 iOS 9 的正常商店簽章流程已不適合持續部署這個私人 armv7 App；越獄也讓開發時可透過 SSH 安全推送與回滾。
- 日常使用不依賴 Cydia tweak。OpenSSH 只屬於開發、診斷及部署工具，App 執行本身不需要 SSH 常駐操作。
- 必須允許 QuietPanel 存取「照片」，才能選取相簿、輪播照片及把安全匯入的圖片寫入 Photos 資料庫。
- Lightning 線必須能傳輸資料，不能只有充電功能。
- 建議把 App 安裝在資料分割區的應用程式容器，不要長期放在 rootfs 的 `/Applications`。第一代 iPad 的系統分割區很小；功能、字型與圖片增加時，使用資料區可避免耗盡越獄後更吃緊的系統空間。

### Mac 端

- 已驗證主機為 MacBook Air M2；目前 `QuietPanel Display` 的最低系統版本是 macOS 14。
- 必須執行獨立的 `QuietPanel Display.app`。iPad App 不會自行建立 macOS 虛擬螢幕，也不會直接向網路取得系統資訊。
- 延伸螢幕與截圖需要 macOS「螢幕錄製」權限。
- 延伸螢幕點按、貼上及工作頁快捷鍵需要 macOS「輔助使用」權限。
- Mac 必須能透過 usbmuxd 看見 iPad。連線使用 USB 原生通道，不需要 `iproxy` 常駐，也不依賴同一個 Wi-Fi。
- 天氣使用 Open-Meteo，不需要 API key。NASA APOD 預設可使用 `DEMO_KEY`；大量請求時才需要以 `QUIETPANEL_NASA_API_KEY` 環境變數提供自己的 key。

### 開發與建置

- Theos。
- 已驗證可產生 armv7 的舊 iPhoneOS 9.3 SDK／相容 clang toolchain。
- Objective-C、ARC、UIKit；最低部署版本為 iOS 9.0。
- 不使用 SwiftUI、Catalyst、WebView UI 或新的 Swift runtime，原因是這些方案不適合 iOS 9／armv7，也會增加記憶體與安裝體積。

## 3. 為何採用這個架構

### iPad 與 Mac 分工

iPad 負責 UIKit 畫面、Photos 相簿、觸控、H.264 解碼及本機快取；Mac 負責系統監測、虛擬顯示器、螢幕擷取與編碼、天氣 HTTPS、NASA 下載及 macOS 操作。

這樣分工有三個理由：

1. iOS 9 的 TLS 與憑證環境太舊，不適合直接連現代 API。
2. Mac 本來就是系統資訊與延伸畫面的來源，避免 iPad 重複做無法完成的工作。
3. iPad mini 1 只有舊款雙核心處理器與有限記憶體，讓 Mac 處理網路和編碼可降低發熱、耗電及斷線風險。

### 獨立 App、識別與連線埠

QuietPanel 沒有覆蓋原本的 OpenDisplay 或 LegacyPad Display：

- bundle/package：`tw.codex.quietpanel`
- App／執行檔：`QuietPanel`
- QuietPanel 接收埠：`9001`
- LegacyPad Display 保留埠：`9000`

採用獨立識別與埠是為了讓已驗證的舊 App 保持可用，也避免 macOS 權限、偏好設定、程序名稱與 USB 連線互相混淆。

### 資料區安裝

Theos 的 `.deb` 適合建置與早期 Cydia 測試，但日常實機版本採資料區 App 容器。部署時保留上一版完整 bundle，再原子切換新舊目錄；啟動或視覺驗收失敗即可換回舊 bundle。資料容器不隨程式 bundle 切換，因此相簿選擇、時鐘位置、字型與快取不會因回滾而消失。

App 容器的 UUID 每次安裝可能不同，文件與程式不可寫死實機路徑。

## 4. 五個頁面

### 第一頁：Mac 系統資訊

- CPU 與記憶體即時用量。
- 最近兩分鐘的 CPU／記憶體曲線。
- 下載與上傳速率。
- 記憶體用量、系統磁碟可用空間、運作時間及系統負載。
- Mac 主機名稱、時間、日期與延伸螢幕連線狀態。

這一頁以大字與卡片式資訊取代早期的單調數字，讓 7.9 吋面板從桌面距離仍能快速閱讀。

### 第二頁：延伸螢幕

- 建立 1024×768 macOS 虛擬螢幕並以 H.264 傳到 iPad。
- 使用 `AVSampleBufferDisplayLayer` 解碼，維持 OpenDisplay 已驗證的 framing、影像與游標協定。
- 游標位置與圖像走低延遲控制通道，由 iPad 本機繪製。
- 在影像區點一下會依 aspect-fit 座標映射成 Mac 左鍵點按。
- 切離本頁時保留虛擬顯示器，避免視窗與游標配置跳動，但暫停擷取、H.264 編碼、傳輸及 iPad 解碼；回到本頁時要求新的 keyframe。

保留虛擬顯示器但停掉影像工作，是兼顧桌面配置穩定與低資源消耗的折衷。

### 第三頁：相簿時鐘

- 從 iOS Photos 選擇一個或多個相簿，也可選全部照片。
- 支援 Finder／iTunes 同步相簿及由 QuietPanel 安全匯入、在 Photos 中可見的同名相簿。
- 輪播只讀取本機照片，不會把私人照片傳給 Mac 或網路。
- 輪播間隔可調；圖片請求以實際面板解析度為上限，避免解碼遠大於螢幕的原圖。
- 時間、日期與天氣可各自選字型；公開版本包含 19 種可再散布的開放字型。
- Storopia 只存在於擁有者的私人測試版，檔案已由 `.gitignore` 排除；授權未確認前不得推到公開 repository 或發行包。
- 可顯示 Open-Meteo 天氣向量圖示、溫度、地點、日出／日落與日照進度線。
- 半透明底板可以關閉，寬度依內容收斂，不覆蓋過多照片。
- 輕觸背景後設定按鈕顯示五秒，平時隱藏以保持相片畫面乾淨。
- 時鐘面板可縮放、移動並保存位置；放大時最多允許 20% 超出邊緣，但仍能取回。

手勢衝突的處理方式是：直接水平滑動優先換頁；單指必須在面板上明確長按約 0.35 秒才開始拖曳；雙指可移動或縮放。這讓字體放大後仍能換頁，也減少在時鐘附近滑動時誤改位置。

### 第四頁：相簿快捷工具

- 沿用第三頁的照片、時鐘、天氣及輪播。
- 工作按鈕使用固定的 iOS 系統字型，不跟著相簿時鐘的自選字型改變。
- 按鈕常駐，半透明底板較明顯，方便立即操作。
- 背景觸控與時鐘編輯停用，只有快捷鍵可操作，以免桌面常駐時誤觸。
- `YouTube`：在 Mac 開啟 YouTube。
- `截圖`：以 macOS `screencapture` 寫入 Dropbox 的「螢幕截圖／Screenshots」資料夾，同時把實際圖片寫入剪貼簿，不會只貼上 Dropbox 分享連結。
- `貼上`：在 Mac 送出 Command–V。
- Mac 會回傳成功或權限錯誤，iPad 顯示實際結果。

第三、第四頁分開，是為了讓一般相簿時鐘保持低干擾，而工作頁可在防誤觸前提下提供固定快捷鍵。

### 第五頁：NASA 每日天文圖

- Mac 取得 NASA APOD、縮小圖片後透過既有 USB 連線傳送。
- 顯示圖片、標題、日期、版權、媒體類型與說明。
- Mac 每六小時檢查更新，亦可在控制器手動重新整理。
- iPad 保留最後一次成功資料；Mac 或 NASA 暫時離線時仍可顯示快取。

由 Mac 下載及縮圖，可避開 iOS 9 TLS 問題，也避免 iPad 解碼不必要的大型原圖。

## 5. Mac 控制器功能與權限理由

`QuietPanel Display` 提供可見視窗，避免只有背景程序而無法判斷服務是否正在執行。控制器可：

- 啟動或停止 USB 服務並顯示連線狀態。
- 選擇 iPad 顯示哪些頁面；至少保留一頁。
- 停用第二頁時移除 macOS 虛擬螢幕。
- 設定天氣開關、顯示地名、緯度與經度。
- 顯示天氣與 NASA 更新狀態並手動更新 NASA。
- 在睡眠、拔線或 App 關閉後以有限重試重新等待連線，而不是留下無限忙迴圈。

螢幕錄製與輔助使用是 macOS 系統權限，不能由程式繞過。使用獨立的 `QuietPanel Display` bundle 也可避免 OpenDisplay 與 QuietPanel 在權限清單中出現難以辨識的同名項目。

## 6. 通訊與資料流

QuietPanel 沿用 OpenDisplay 的 USB framing，在同一條連線中傳送 H.264 影像與 JSON 控制訊息。主要控制訊息包括：

- `metrics`：CPU、記憶體、網路、磁碟及系統資訊。
- `page_config`：Mac 選定的頁面。
- `weather_state`：天氣、地點、更新時間、日出與日落。
- `nasa_state`：APOD metadata 與縮圖。
- `visible`：iPad 正在顯示的頁面，讓 Mac 停掉不需要的工作。
- `cursor`／`cursor_image`：低延遲本機游標。
- `touch`：第二頁點按事件。
- `action`／`action_result`：第四頁快捷鍵與結果。
- `ping`／`pong`：偵測 USB 半斷線及睡眠後的失效連線。

所有頁面共用 TCP `9001`，不再為資訊、天氣或 NASA 各開一個常駐服務。這能減少舊裝置上的 socket、重連狀態及背景工作數量。

## 7. 相簿匯入設計

為了讓透過 USB 傳入的資料夾真正出現在 iPad「照片」App，檔案先放入 QuietPanel 資料容器的 `Documents/QuietPanelImports/<相簿名稱>/`，再由 App 使用 Photos framework 建立同名相簿及照片資產。

安全條件如下：

- 只接受一般的 JPEG、JPG 與 PNG 檔案。
- 依檔名做自然排序。
- 每批最多匯入 10 張，避免 iPad mini 1 記憶體尖峰。
- `.ready` 出現後才開始，避免邊傳輸邊讀取未完成檔案。
- `.state.plist` 記錄相簿 identifier、來源數量、總位元組與進度，可在中斷後續傳。
- 發現多個同名相簿、非空白同名相簿、檔案數改變或相簿被改名時停止，不猜測目標，也不自動重複寫入。
- 完成後照片由 Photos 管理，在系統相簿與 QuietPanel 選擇器中都看得到；來源暫存不等於 Photos 唯一副本。

這套流程比直接修改 Photos 資料庫安全，因為所有寫入都經過 Apple 公開的 Photos framework。

## 8. 字型與 build 21 清晰度處理

iPad mini 1 是 1× 非 Retina 螢幕。相簿時鐘原本先以基準字級產生 UILabel 內容，再把整個面板放大；即使提高 `contentsScale`，日期與天氣的小字仍可能有「放大的點陣圖」感。

build 21 改為：

- 依面板縮放倍率計算時間、日期、溫度、地點與日照文字的最終字級。
- 字級與 label 尺寸對齊實體像素。
- 文字子 view 使用反向 transform 抵消父面板縮放，讓 UIKit／CoreText 直接以畫面上的最終大小光柵化字形，而不是放大較小的文字 backing store。
- 天氣向量圖示仍使用較高繪製比例。
- 原本的陰影顏色與 offset 參數不變。

這個作法只改文字繪製方式，不改使用者設定、面板大小、位置、字型選項與手勢，因此可在不減少功能的前提下改善主觀清晰度。

## 9. 資源消耗策略

- 第一頁只在可見時更新秒鐘與系統資訊。
- 第三、第四頁時鐘以分鐘更新；輪播 timer 只在相簿頁執行，並設定 tolerance。
- 相簿圖片只請求面板實際需要的解析度，且不允許 Photos 從網路下載 iCloud 原圖。
- 第二頁不可見時，iPad 不解碼 H.264；Mac 不擷取、不編碼、不傳影像。
- Mac 只在對應頁面需要時採樣 metrics、游標或影像。
- H.264 直接使用單一 CoreMedia decode buffer，避免多份暫存複製。
- 天氣每小時更新，失敗後十分鐘重試；超過六小時未成功則隱藏過期資料。
- NASA 每六小時檢查一次；背景 timer 使用容許誤差，避免不必要的精準喚醒。

目標不是追求最高更新率，而是在 2012 年硬體上維持足夠流暢、低發熱及長時間穩定。

## 10. 建置、驗證與部署

在 `ios/` 執行：

```sh
./tests/run.sh
THEOS=/absolute/path/to/theos make clean package FINALPACKAGE=1
```

建置後至少確認：

```sh
file .theos/_/Applications/QuietPanel.app/QuietPanel
lipo -info .theos/_/Applications/QuietPanel.app/QuietPanel
plutil -p .theos/_/Applications/QuietPanel.app/Info.plist
```

應得到 armv7、bundle `tw.codex.quietpanel`、minimum iOS 9.0 與預期 build number。

`ios/tests/run.sh` 目前涵蓋：

- framing／protocol parser。
- QuietPanel 與 LegacyPad Display 的獨立身份及 port。
- 頁面切換不能被阻塞的接收佇列卡住。
- 相簿時鐘主要 UX 約束、手勢、按鈕字型與觸控訊息。

每次實機部署仍需確認：App 可啟動、五頁可切換、相簿與天氣可讀、第二頁能收到 keyframe、Mac 權限正確、拔插 USB 後能恢復。小型視覺改版可縮短壓力測試，但不能省略建置、程序存活及回滾點確認。

## 11. 回滾原則

- Git 每個可用版本保留獨立提交；build 20 是 build 21 字型清晰化之前的直接基準。
- 實機切換前保留上一版完整 `.app` bundle，不覆寫備份。
- 新版先放入同一資料分割區的暫存目錄，檢查 hash、Info.plist、擁有者及執行權限後再改名切換。
- 啟動後確認程序不是 stopped 狀態，再做畫面驗收。
- 回滾只替換 App bundle，不刪除 Photos、Documents、Library 或偏好資料。
- 不以修改 iOS 系統分割區大小作為空間方案；風險高且沒有必要。

## 12. 隱私、授權與公開範圍

- 本機相簿不會上傳到 Mac、Open-Meteo、NASA 或其他服務。
- Mac 系統資訊只在 USB 連線中送到 iPad，不會送到第三方伺服器。
- 截圖依使用者需求保存到 Dropbox 本機同步資料夾；同時放入剪貼簿的是圖片本身。
- 接收器衍生自 LegacyPad Display／OpenDisplay，iOS 原始碼依 GPL-3.0 公開；詳見 `ios/LICENSE` 與 `ios/NOTICE.md`。
- 19 種公開字型依 SIL Open Font License 1.1 散布；完整來源與授權見 `ios/Resources/Fonts/LICENSES-PhotoFonts.txt`。
- Storopia 授權未確認，只能留在擁有者本機私人 build。
- Open-Meteo 資料依 CC BY 4.0；NASA 圖片與說明保留 APOD 回傳的版權／署名資訊。
- `.theos/`、`ios/packages/`、私人字型、裝置密碼、SSH key、固定容器 UUID 與本機簽章資料不得提交。

## 13. 已知邊界

- 目前只驗證 iPad mini 1／iOS 9.3.5；其他 armv7 iPad 理論上可建置，不代表已實機驗證。
- 觸控延伸畫面目前只提供單指左鍵點按，尚未實作右鍵、拖曳、滾動、鍵盤及多點手勢。
- NASA 與天氣依賴 Mac 網路；iPad 只顯示最後快取或在過期後隱藏資料。
- 公開 repository 不包含 Storopia，也不包含配套 OpenDisplay repository 的完整 Mac 原始碼。
- 這是越獄舊設備的私人學習與延壽專案，不是 App Store 發行版本。
