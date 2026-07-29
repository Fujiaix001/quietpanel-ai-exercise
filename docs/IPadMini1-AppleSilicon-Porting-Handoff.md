# QuietPanel：iPad mini 1 ＋ Apple Silicon Mac 移植交接指南

更新日期：2026-07-30  
用途：交給另一個 Codex，在新的對話與新的 Mac 開發環境中接手執行。  
狀態：這是執行指南，不代表 iPad／macOS 移植已經完成。

## 給新對話 Codex 的起始指令

使用者可以在新的對話直接貼上以下文字：

> 請先完整閱讀 `docs/IPadMini1-AppleSilicon-Porting-Handoff.md`，再檢查整個 QuietPanel repository、目前 Git 狀態與實際連接的 iPad/Mac。依照指南從 Phase 0 與 Phase 1 開始，不要直接重寫整個 App，也不要先準備舊 Intel Mac。先在 M 晶片 Mac 上完成 ARMv7 最小程式的編譯、安裝、啟動與紀錄驗證；通過後再開始正式移植。保留 Android/Windows 現有可用版本，不得為了 iOS 版破壞 Protocol v1 或目前發行產物。每一階段都要實機驗證並留下 Git 紀錄。

## 1. 任務與使用情境

目標是讓第一代 iPad mini 成為 QuietPanel 控制台，搭配 Apple Silicon（M 系列）Mac：

- 顯示 Mac 的 CPU、記憶體、網路、磁碟、天氣與時鐘。
- 從 iPad 發出音量、媒體、截圖、複製、貼上、視窗操作等控制命令。
- 保留相簿時鐘與鬧鐘的可能性，但可以在核心控制台穩定後分階段移植。
- 優先提供不依賴公共 Wi-Fi 的通道。
- iPad 已越獄，允許使用 Theos、SSH、`ldid`、`.deb` 或其他適合舊 iOS 的安裝方式。
- 日常開發主機應是 M 晶片 Mac；2015 Intel Mac 只當最後備援。

這不是把 APK 轉成 IPA。Android UI 和 Android 系統 API 不能直接沿用；應保留通訊協定、功能語意、資料結構及視覺設計，再建立原生 iOS 客戶端和 macOS Bridge。

## 2. 現有專案基準

來源 repository：<https://github.com/Fujiaix001/quietpanel-ai-exercise>

撰寫本指南時的已知基準：

- 分支：`main`
- 提交：`3a4b41a991d2c613105dfe37d4df48c65c220ac4`
- 版本：`9.0.10`
- Android 最低版本：API 17／Android 4.2
- Windows Bridge：Rust
- Android Client：Java
- 通訊協定：Protocol v1、UTF-8 JSON
- TCP 控制埠：`27183`
- APOD 圖片埠：`27184`
- UDP discovery：`27185`

接手時先執行：

```bash
git status --short
git branch --show-current
git rev-parse HEAD
git remote -v
```

不要假設新電腦上的 clone 一定和上述提交一致。先 fetch，對照 GitHub 與本機差異，再決定是否更新。工作樹若有使用者修改，不得覆蓋、reset 或擅自清除。

原 Windows 工作目錄曾存在未追蹤診斷檔與另一份未提交指南；不可用 `git add -A` 把它們誤收進提交。新環境仍應遵守同樣原則：只 stage 本階段明確新增或修改的檔案。

## 3. 必須先讀的現有檔案

至少閱讀以下內容後才設計移植：

- `README.md`
- `docs/QuietPanel-v9-Clock-Weather-Dual-Transport.md`
- `docs/QuietPanel-v8-Bluetooth-PAN-Diagnosis-2026-07-26.md`
- `bridge/src/protocol.rs`
- `bridge/src/connector.rs`
- `bridge/src/connector/adb_connector.rs`
- `bridge/src/connector/wireless_connector.rs`
- `bridge/src/main.rs`
- `bridge/src/actions.rs`
- `bridge/src/metrics.rs`
- `bridge/src/display.rs`
- `bridge/src/tray.rs`
- `bridge/src/weather.rs`
- `android/app/src/main/java/com/quietpanel/client/TransportServer.java`
- `android/app/src/main/java/com/quietpanel/client/MainActivity.java`
- `android/app/src/main/java/com/quietpanel/client/PhotoFolderActivity.java`
- `android/app/src/main/java/com/quietpanel/client/ClockEnvironmentController.java`
- `android/app/src/main/java/com/quietpanel/client/PhotoFontManager.java`

先建立一份「可共用邏輯／平台限定邏輯」清單，不要逐行把 Java 翻成 Objective-C，也不要把 Windows API 原封不動搬到 macOS。

## 4. Phase 0：硬體與系統盤點

在修改程式碼前，直接從實機取得下列資料並記錄到新的診斷文件：

### iPad

- 完整型號與 hardware identifier，例如 `iPad2,5`、`iPad2,6` 或 `iPad2,7`。
- iOS 版本；第一代 iPad mini 常見為 9.3.5 或蜂巢版本的 9.3.6，但不可猜測。
- 越獄工具名稱、bootstrap、套件管理器，以及是否為 rootful。
- 是否已安裝 OpenSSH、`ldid`、`dpkg`、`rsync`。
- Wi-Fi SSH 是否能登入。
- Lightning 接口是否仍可傳輸資料，不只可以充電。
- `uname -a`、`uname -m`、可用儲存空間與目前記憶體狀態。
- 藍牙能否正常開啟、配對及掃描。

不要要求使用者在對話中貼出 root 密碼。若裝置仍使用預設 SSH 密碼，先指導改密碼或改用 SSH key，但任何可能讓裝置失去登入能力的修改都要先保留可恢復方式。

### Mac

- Mac 型號、Apple Silicon 型號與 macOS 版本。
- Xcode、Command Line Tools、Rust、Homebrew 版本。
- 是否已安裝 Rosetta 2。
- USB 連接 iPad 後，系統資訊與 `idevice_id -l` 是否能看見裝置。
- Mac 與 iPad 是否有可用的私人 Wi-Fi；公共 Wi-Fi 不應假定允許 client-to-client 通訊。

盤點只做讀取和診斷。不要在此階段重灌 macOS、重刷 iPad、更新 iOS 或更換越獄。

## 5. Phase 1：先證明 M 晶片可完成 ARMv7 工作流

### 判定原則

暫時不準備 2015 Intel Mac。先在 M 晶片 Mac 完成以下四件事：

1. 編譯一個 Objective-C/UIKit、ARMv7、可在 iOS 9 執行的最小 App。
2. 將它封裝成適合目前越獄環境的 `.deb` 或 `.ipa`。
3. 安裝到 iPad mini 1 並成功啟動。
4. 能讀到啟動、按鈕事件與崩潰紀錄。

四項通過，代表正式移植不需要舊 Mac。

### 建置環境方向

- 安裝完整 Xcode；只有 Command Line Tools 不足以滿足 Theos 的一般 macOS 建置需求。
- 安裝 Theos：<https://theos.dev/docs/installation-macos>
- 使用 Objective-C 與 UIKit，不要用 SwiftUI、Catalyst 或僅支援新系統的框架。
- 目標架構固定先驗證 `armv7`。
- Deployment Target 依實機 iOS 版本設定，原則上以 `9.0` 或實際所需的更低相容值開始。
- 從可信且合法的 Apple/Xcode 來源取得相容 SDK 與 toolchain；不要從不明網盤下載整套簽章或工具鏈。
- 若目前 Xcode toolchain 已移除必要的 ARMv7 能力，讓 Theos 對單次命令指定相容的舊 Xcode toolchain；不要全域破壞現行 Xcode 設定。
- 舊 Xcode GUI 在新 macOS 上不能啟動，不等於其中的命令列 toolchain 一定不能被 Theos 使用。必須以實際 smoke test 判定。

Theos 專案的方向範例，不可未驗證便照抄成最終設定：

```make
ARCHS = armv7
TARGET = iphone:clang:<實際可用SDK>:9.0
PACKAGE_FORMAT = deb
```

建置後至少檢查：

```bash
lipo -info <app-binary>
file <app-binary>
otool -l <app-binary>
```

確認輸出包含 ARMv7，並確認 minimum OS version 不高於實機版本。

### 安裝與紀錄

優先支援兩種安裝路徑：

- Wi-Fi SSH：Theos `make package install`。
- USB：用 `usbmuxd`／`iproxy` 將 Mac 本機 SSH port 轉到 iPad 的 port 22，再安裝套件。

USB 轉發概念範例：

```bash
iproxy 2222 22
ssh -p 2222 root@127.0.0.1
```

實際帳號、port 與套件路徑要依越獄環境確認，不得盲目假設。建議設定 SSH host alias，避免把密碼或裝置資訊寫進 repository。

### 何時才啟用 2015 Intel Mac

只有同一個 ARMv7 阻礙經過完整診斷仍無法在 M 晶片排除時，才把 Intel Mac 當備援。例如：

- 必須使用只能在舊 macOS 運行的 Xcode 7～10 圖形化裝置除錯。
- ARMv7 linker／舊 SDK 與 M 晶片工具鏈存在無法繞過的相容問題。
- 必須使用只支援 Intel macOS 的舊越獄或裝置救援工具。

舊 Intel Mac 如果已升到太新的 macOS，仍可能不能執行舊 Xcode。因此「有一台 2015 Mac」本身不是解決方案；必須連同相容 macOS、Xcode 與 SDK 一起評估。

## 6. 目標架構

建議新增平台目錄，不動現有 Android Client：

```text
QuietPanel_v6/
├─ android/                 # 現有 Android 版，保持可建置
├─ bridge/                  # Rust Bridge，共用 protocol/weather/metrics
├─ ios/                     # 新增：iOS 9 / ARMv7 原生客戶端
│  ├─ Makefile
│  ├─ control
│  ├─ Resources/
│  └─ Sources/
│     ├─ AppDelegate.*
│     ├─ MainViewController.*
│     ├─ Protocol/
│     ├─ Transport/
│     ├─ Pages/
│     ├─ PhotoClock/
│     └─ Settings/
└─ docs/
```

### iPad Client

使用舊 iOS 仍穩定可用的公開框架：

- UIKit
- Foundation
- CoreBluetooth
- Photos
- ImageIO/CoreGraphics
- AVFoundation 或 SystemSound（視鬧鐘需求）

避免：

- SwiftUI。
- Electron、WebView 為主的 UI。
- 依賴現代 Swift runtime 的核心介面。
- 私有藍牙 SPP API。
- 一開始便把全部 Android Activity 合成一個巨大 Objective-C controller。

UI 先以第一代 iPad mini 的實際橫向解析度與 scale 驗證。不要假設 Android dp 尺寸可以原樣使用。

### macOS Bridge

Rust 目標為：

```text
aarch64-apple-darwin
```

可以優先沿用：

- `protocol.rs`
- `weather.rs`
- `metrics.rs` 中 `sysinfo` 可跨平台的部分
- 連線重試、ping、每秒 state 更新及 action request/result 語意
- 設定檔資料模型

必須平台化或重寫：

- `actions.rs`：目前直接呼叫 Windows keyboard/window API。
- `display.rs`：目前使用 Windows power/display notification。
- `tray.rs`：目前是 Windows notification area。
- `pan.rs`：Windows Bluetooth PAN 專用。
- `adb.rs`：iOS 不使用 ADB。
- `Cargo.toml` 中無條件啟用的 `windows-sys` 與 Windows-only binary attributes。

建議建立清楚的平台邊界，例如：

```text
bridge/src/platform/windows/{actions,display,tray}.rs
bridge/src/platform/macos/{actions,display,tray}.rs
bridge/src/connector/ios_usb_connector.rs
bridge/src/connector/wifi_connector.rs
bridge/src/connector/ble_connector.rs
```

不要為了讓 macOS 編譯而刪除 Windows 實作。Windows 與 macOS 必須各自保持可建置、可測試。

macOS 的控制操作可能需要 Accessibility、Automation 或 Screen Recording 權限。每個 action 必須回傳清楚的 `action_result`，區分「動作失敗」和「尚未授權」，不能靜默失敗。

## 7. 通訊策略與開發順序

### 第一優先：USB over usbmuxd

這是最接近現有 ADB Forward 的方法：

```text
Mac Bridge → 127.0.0.1:27183
                 ↓ iproxy/usbmuxd
             iPad:27183
```

優點：

- 不依賴公共 Wi-Fi。
- 不需要 Bluetooth PAN。
- 現有 TCP、newline-delimited JSON 和 `Connector -> TcpStream` 架構可大致保留。
- 最適合作為 Protocol v1 與 UI 的第一條實機驗證通道。

### 第二優先：Wi-Fi TCP

iPad App 在可信私人網路監聽 TCP `27183`，Mac Bridge 連到 iPad IP。加入明確的 timeout、自動重連與可辨識的裝置設定。不要假定飯店、公司或公共 Wi-Fi 允許裝置彼此連線。

### 第三優先：BLE

BLE 頻寬足以傳送 QuietPanel 的 metrics、weather、alarm、page config 和 macro action；不要傳原始相片或 APOD 圖片。

建議角色：

- iPad：CoreBluetooth peripheral，提供 QuietPanel service。
- Mac：CoreBluetooth central，掃描並連線。

至少設計兩條 characteristic：

- Mac → iPad：write／write without response，依流量控制選擇。
- iPad → Mac：notify。

BLE 不能假裝成 `TcpStream`。當 USB/Wi-Fi 穩定後，把通訊核心抽象成「讀寫完整 protocol frame」的 transport trait；TCP transport 使用換行分隔，BLE transport 使用有長度、message ID、chunk index/count 的分段 frame，並處理：

- negotiated MTU 不同。
- 訊息分段與重組。
- 最大訊息大小。
- timeout 與遺失 chunk。
- 斷線與 state restoration。
- 重複 action ID，避免巨集重複執行。
- 明確的 protocol version 與錯誤回覆。

不要走 Classic Bluetooth SPP 或依賴 MFi External Accessory；也不要依賴越獄私有藍牙 API作為主要通道。越獄應主要用於安裝、自動啟動與 kiosk，而不是破壞可維護的通訊層。

## 8. Protocol v1 相容要求

目前 TCP 每一行是一個 UTF-8 JSON object，Mac/PC 端送出：

- `hello`
- `display_state`
- `page_config`
- `weather_state`
- `state`
- `ping`
- `action_result`

客戶端送出：

- `hello_ack`
- `pong`
- `action`

典型 action：

```json
{"v":1,"type":"action","id":42,"action":"toggle_mute"}
```

典型結果：

```json
{"v":1,"type":"action_result","id":42,"ok":true,"message":"完成"}
```

移植規則：

- iOS 版先完整相容 v1，不因平台不同隨意改欄位名稱。
- action 名稱是跨平台語意；macOS 實作可以不同，但名稱先保持一致。
- 未支援的 action 必須回傳 `ok:false` 和原因，不能讓按鈕看似成功。
- 新欄位要能被舊客戶端忽略；破壞性改動才升 protocol version。
- 在 Rust 與 iOS 兩端共用一批固定 JSON test vectors。
- BLE 只改 framing，不改 JSON object 的功能語意。

先把 Android `TransportServer.java` 和 Rust `protocol.rs` 的行為整理成正式 protocol 文件及 fixture，再寫 iOS parser。不可只根據畫面猜協定。

## 9. iPad 相簿時鐘移植規則

iOS Photos 不是 Android 檔案系統，也沒有 SAF 資料夾語意。建議讓使用者選擇「相簿」，並保存 Photos collection/asset identifier，而不是保存裸檔案路徑。

必須做到：

- 使用 `PHAsset`／`PHFetchResult` 非同步列舉。
- 使用 `PHCachingImageManager` 或等效方法取得接近顯示尺寸的圖片。
- 使用 ImageIO thumbnail/downsample，避免整張原始相片常駐記憶體。
- 限制預取與 decoded image cache；收到記憶體警告立即清理。
- 相簿有上萬張照片時，不應阻塞主執行緒，也不應每次滑頁重新全量掃描。
- 使用 identifier 與增量索引；不要因為限制首批數量而讓其餘照片永遠不出現。
- 切換頁面時取消已不需要的 image request。
- UI 不顯示冗長掃描進度，但要保持可操作並提供必要錯誤提示。

字型、天氣、地名與鬧鐘應遵循 v9.0.10 已確認的視覺規則；Storopia 仍屬私人版本資產，不得混入公開發行版。

## 10. 分階段執行計畫

### Phase 0：盤點

- 記錄 iPad 型號、iOS、越獄、SSH、Lightning data 狀態。
- 記錄 Mac 型號、macOS、Xcode、Rust。
- 核對 repository 與 Git 工作樹。

驗收：資料完整，沒有修改裝置韌體或破壞現有版本。

### Phase 1：ARMv7 smoke test

- 建立最小 UIKit App。
- M 晶片編譯 ARMv7。
- 封裝、安裝、啟動、抓 log。

驗收：iPad 顯示畫面、按鈕改變文字；產物經 `lipo/file/otool` 驗證。

### Phase 2：macOS Bridge 最小移植

- 讓 `protocol`、`weather`、`metrics` 在 `aarch64-apple-darwin` 編譯。
- 抽離 Windows-only modules。
- 建立 macOS console mode，先不做 menu bar。
- 用 loopback fake client 驗證 hello/state/ping/action/result。

驗收：Windows 既有測試仍通過，macOS 測試新增並通過。

### Phase 3：USB 核心控制台

- iPad TCP server 或等價連線端。
- usbmuxd/iproxy connector。
- 系統頁、儲存頁、Macro、快捷頁。
- Mac actions 最小集合與權限錯誤回報。

驗收：拔插 Lightning、Bridge 重啟、iPad App 重啟後都能自動恢復；action 不重複執行。

### Phase 4：Wi-Fi 與完整 UI

- Wi-Fi connector、自動重連與設定。
- 天氣、時鐘、鬧鐘。
- Mac menu bar 與設定檔。

驗收：USB/Wi-Fi 可明確切換且互不破壞。

### Phase 5：相簿時鐘

- Photos album picker。
- 大相簿非同步索引與受限快取。
- 時鐘、字型、天氣、地名、鬧鐘排版。

驗收：用大量照片實測，不因掃描、滑頁或記憶體警告而閃退。

### Phase 6：BLE

- iPad peripheral、Mac central。
- framing、分段、重組、重連、重複 action 防護。
- 前景長時間運作與背景恢復測試。

驗收：不使用 Wi-Fi/USB，持續監控與巨集穩定；關閉再開啟藍牙後能恢復。

### Phase 7：部署與維護

- 越獄環境自動啟動/kiosk；必須可關閉或復原。
- 公開版與私人 Storopia 版分離。
- 文件、版本、SHA-256、安裝與移除流程。
- 在 GitHub 建立清楚提交；未經使用者要求不要建立公開 release。

## 11. 測試矩陣

每個 release 至少驗證：

| 類別 | 測試 |
|---|---|
| Build | macOS arm64 Bridge、iOS armv7 App、既有 Windows Bridge、既有 Android App |
| Protocol | 所有 v1 fixture、錯誤版本、未知訊息、空 action、超大訊息 |
| USB | 首次配對、拔插、睡眠喚醒、Bridge/iPad 任一端重啟 |
| Wi-Fi | IP 改變、公共網路隔離、斷線、重連 |
| BLE | MTU 差異、chunk 遺失、藍牙重啟、距離中斷、重複 action |
| UI | 1024×768 橫向、所有頁面、最大字型、天氣＋地址＋鬧鐘同時顯示 |
| Photos | 空相簿、權限拒絕、SD/匯入相片、大相簿、損壞圖片、記憶體警告 |
| Actions | 每一個 macOS action 的成功、未授權、無前景視窗等失敗狀態 |
| Long run | 亮屏至少數小時，觀察 RSS、CPU、溫度、斷線次數與相片快取 |

不要只在模擬器驗收。核心結論必須來自第一代 iPad mini 實機和目標 M 晶片 Mac。

## 12. Git 與安全規則

- 開始正式實作時建立 `codex/ipad-mini1-macos-port` 分支，除非使用者另有指定。
- 每個 Phase 使用小而可回復的提交。
- 提交前列出 staged files，避免把 log、密碼、SSH key、SDK、Xcode 或越獄套件二進位提交。
- 不得提交 Apple SDK 或從 Xcode 擷取的受授權檔案。
- 不得把私人 Storopia 字型放入公開版或公開 release。
- 不得刪除 Android/Windows 現有功能來換取 macOS 編譯成功。
- 不得對 iPad 執行 restore、erase、update iOS 或更換越獄，除非使用者針對該操作明確授權並已有可恢復備份。
- 變更 macOS Accessibility、Automation、Screen Recording、Login Item 等權限時，清楚說明原因及還原方式。

## 13. 遇到阻礙時的判斷順序

ARMv7 無法建置時依序檢查：

1. binary target triple 和 deployment target。
2. SDK 是否存在且可讀。
3. clang 是否仍接受 `armv7`。
4. linker、`libarclite`、bitcode、modules 或舊 ABI 問題。
5. Theos 實際選到哪個 Xcode/toolchain。
6. 用單次 `DEVELOPER_DIR` 或 `PREFIX` 切換相容 toolchain。
7. 是否能以更純粹的 Objective-C／非 ARC 最小程式縮小問題。
8. M 晶片路線確實無解後，才評估 Intel Mac＋舊 macOS。

BLE 不穩時依序區分：

1. 未掃描到 advertisement。
2. 已發現但無法 connect。
3. service/characteristic discovery 失敗。
4. write/notify 流量控制問題。
5. framing/reassembly 問題。
6. iOS 前景/背景狀態造成的限制。
7. protocol parser 或 action 重複問題。

不要把所有失敗都籠統歸因為「iPad ROM 太舊」或「越獄不穩」。每個結論要附 log、重現步驟和排除證據。

## 14. 參考資料

- Apple Core Bluetooth Overview：<https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothOverview/CoreBluetoothOverview.html>
- Apple Core Bluetooth Background Processing：<https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html>
- Apple ARMv7：<https://developer.apple.com/documentation/xcode/writing-armv7-code-for-ios>
- Apple QA1910：<https://developer.apple.com/library/archive/qa/qa1910/>
- Apple Photos/PHAsset：<https://developer.apple.com/documentation/photos/phasset>
- Apple External Accessory：<https://developer.apple.com/documentation/externalaccessory>
- Xcode 系統需求：<https://developer.apple.com/xcode/system-requirements>
- Theos macOS 安裝：<https://theos.dev/docs/installation-macos>
- Theos variables：<https://theos.dev/docs/variables>
- Theos commands：<https://theos.dev/docs/commands>
- libimobiledevice/usbmuxd：<https://github.com/libimobiledevice/usbmuxd>

## 15. 完成定義

不能只以「成功編譯」宣告完成。最低完成條件是：

- 第一代 iPad mini 上的 ARMv7 App 可以長時間穩定運作。
- M 晶片 Mac Bridge 為原生 arm64，沒有依賴 Windows VM。
- USB 至少是一條可靠、可重連且不依賴 Wi-Fi 的通道。
- Wi-Fi 與 BLE 依使用者確認的產品範圍完成；若延期，要明確標示未完成。
- Protocol v1 的 metrics、weather、page config、ping、action/action_result 都有測試。
- 核心 macOS actions 有正確權限處理和失敗回覆。
- 相簿大量照片不阻塞、不反覆掃描、不因 cache 無限制成長而閃退。
- Android 與 Windows v9.0.10 仍可建置，既有使用者不受影響。
- 建置、安裝、移除、故障排除、版本與 Git 提交都有文件。

最重要的策略是：先證明 M 晶片上的 ARMv7 工具鏈，再做 USB 核心控制台，最後做 BLE。不要一開始同時處理舊 SDK、完整 UI、照片、macOS 權限和 BLE，否則任何失敗都難以定位。
