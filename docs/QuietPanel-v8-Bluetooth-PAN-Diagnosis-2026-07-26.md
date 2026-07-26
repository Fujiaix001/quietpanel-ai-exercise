# QuietPanel v8 藍牙 PAN 完整診斷與修復報告

日期：2026-07-26  
目標設備：Windows 10 電腦 `DESKTOP-4AJ66M5`、Xiaomi 2013023／Android 4.2.2 手機  
最終版本：QuietPanel 8.1.6

## 結論

問題不是單一的「藍牙壞掉」，而是五個相互疊加的問題：

1. 原 Bridge 把 PAN 介面或裝置存在誤認為已連線，實際上沒有呼叫正確的 Windows PAN 連線流程。
2. 手機的 MediaTek Android 4.2.2 每次收到 PAN 要求都只給約 10 秒人工授權；配對或 `setTrust(true)` 都不會記住允許。
3. 手機真正建立的介面叫 `btn0`，位址是 `192.168.44.1/24`，但同一套 MediaTek 韌體啟動的 DHCP 範圍卻是 `192.168.5.x`／`192.168.6.x`。Windows 因此拿到 APIPA `169.254.x.x`，PAN 雖為 Up，TCP 仍完全不通。
4. 這台電腦的舊 Intel 藍牙驅動與 A2DP 喇叭連線會妨礙控制器重啟；反覆、逾時的 PAN 連線又會使控制器出現 BTHUSB Event 3。
5. Wi-Fi 不是可用備援：手機為 `10.40.243.66/16`，電腦為 `140.112.144.210/16`，不在相同區網。

已完成的修復是：Bridge 使用真正的 Windows PAN 連線 API、限制重試頻率且不與既有 TCP 工作階段並行；Android App 只對「已配對且裝置類別為電腦」的連入要求呼叫這台 MTK ROM 的授權 Binder；Windows PAN 固定為 `192.168.44.2/24`；Bridge 固定先嘗試 `192.168.44.1`。最後已多次建立真實 TCP 工作階段，手機顯示 `IP LIVE` 與即時 CPU／記憶體數據；介面卡冷重啟後的 8.1.6 最終驗收沒有新增 BTHUSB 錯誤。

## 實測設備與已確認資料

| 項目 | 實測值 |
| --- | --- |
| Windows | Windows 10 19045，64 位元 |
| 電腦藍牙 | Intel USB `VID_8087&PID_07DC`，MAC `0C:8B:FD:05:1F:EE` |
| 藍牙驅動 | 原 20.60.0.4（2018），更新為 20.100.5.1（2019-04-17） |
| 手機 | Xiaomi 2013023，Android 4.2.2／API 17，ROM `4.2.2-JHBMIBL31.0` |
| 手機藍牙 | MAC `68:DF:DD:0C:C1:AE` |
| MTK 藍牙套件 | `/system/app/MtkBt.apk`，`com.mediatek.bluetooth` |
| QuietPanel | `com.quietpanel.client`，原 8.1.5／8150，修正版 8.1.6／8160 |
| PAN 位址 | 手機 `btn0 = 192.168.44.1/24`；Windows PAN `192.168.44.2/24` |
| 資料通道 | TCP 27183；圖片 TCP 27184；探索 UDP 27185 |

舊驅動已先備份至 `%TEMP%\quietpanel-intel-bt-backup-20.60.0.4\ibtusb.inf_amd64_18867069f997bf39`。安裝過的 Intel CAB SHA-256 為 `331DDA00F773C024A2A072F2E05B2D82D6581383319D80C7A4C23CE0B033BF0F`。這是這台硬體的實測記錄，不代表其他 Intel 型號都應強制安裝同一套驅動；Intel 已把這一代產品列為停止支援，其他機器應先採 OEM 對應驅動。[Intel 最終可用驅動說明](https://www.intel.com/content/www/us/en/support/articles/000054980/wireless.html)、[Intel 停止支援說明](https://www.intel.com/content/www/us/en/support/articles/000030330/wireless/intel-wireless-ac-products.html)

## 全部診斷經過

### 1. 先排除防火牆與配對表象

Windows 曾再次顯示網路類型提示，先前選了「公用網路」。檢查後，Bridge 的 TCP／UDP 規則已允許 Private 與 Public，因此防火牆不是本次 PAN 無法建立的根因。後續不再用「改成私人網路」當作主要解法。

在「裝置和印表機」直接對紅米手機選擇存取點曾顯示 Windows 無法連線。重新配對後，Windows 與手機都保有認證裝置資料，但「已配對」只證明金鑰交換完成，不代表 PAN 授權、IP 配置或應用 TCP 已完成。

### 2. Windows Bridge 的第一個程式錯誤

舊實作曾把 Windows Bluetooth/PAN 查詢的非零狀態當作已連線，並回傳類似 `AlreadyConnected`。實機顯示這是假陽性：網路裝置可被列舉、藍牙 ACL 也可短暫為 Connected，但 PAN 網卡與手機 `btn0` 都尚未存在。

修正後的 `bridge/src/pan.rs`：

- 先用 `BluetoothEnumerateInstalledServices` 檢查遠端 NAP 服務，避免已啟用時再次呼叫 `BluetoothSetServiceState` 造成 Win32 error 87。
- 使用 `bthpanapi.dll` 列舉目標網路並以遠端 NAP role 進行 `BluetoothConnectToNetwork`。
- 不再以介面存在或任意非零狀態宣告成功。
- `pan_probe` 保留為低階診斷工具。

Microsoft 對服務註冊 API 的語意可參考 [BluetoothSetServiceState](https://learn.microsoft.com/en-us/windows/win32/api/bluetoothapis/nf-bluetoothapis-bluetoothsetservicestate) 與 [BluetoothEnumerateInstalledServices](https://learn.microsoft.com/en-us/windows/win32/api/bluetoothapis/nf-bluetoothapis-bluetoothenumerateinstalledservices)。`bthpanapi.dll` 的 PAN 函式不是一般公開、穩定的 Windows SDK 合約，因此未來換 Windows 大版本仍須實機回歸。

### 3. 藍牙控制器、驅動與喇叭干擾

原 Intel 驅動為 20.60.0.4。更新成與 `VID_8087&PID_07DC` 相符的 20.100.5.1 後，裝置仍曾無法立即重啟。事件與行程檢查顯示，同時連線的 `SPEAKER5.0` 正占用 A2DP，PnP restart 被 outstanding open veto。使用者關閉喇叭後，`pnputil /restart-device` 成功。

診斷期間還觀察到：若手機的 10 秒授權逾時，而 Bridge 很快再次送出連線，舊控制器可能出現 BTHUSB Event 3（送往介面卡的命令逾時、無回應）。因此 Bridge 8.1.6 改為：

- 先嘗試既有 IP／探索；只有沒有可用 TCP 時才碰 PAN。
- PAN 平常只有一個背景呼叫；若舊 `bthpanapi` 卡住，8 秒後最多送出一次受控喚醒，不再無限盲目並行。
- 失敗後由 8 秒指數退避，最高 300 秒。
- TCP 已連線時完全不重送 PAN connect。

### 4. 真正的手機端阻塞：每次都要 PAN 授權

ADB logcat 證實 Windows 要求確實到達手機。MediaTek 的固定流程為：

```text
EVENT_PAN_MMI_CONNECTION_AUTHORIZE_IND
State:0->1
genPanNotification
（約 10 秒內未允許）再次 AUTHORIZE_IND → 拒絕 → 斷線
```

手機通知內容為「選擇接受來自 DESKTOP-4AJ66M5 的群組臨機操作網路要求」，對話框為「接受群組臨機操作網路授權要求」，按鈕是「拒絕／允許」。人工在期限內按「允許」後，立即得到：

```text
authorizeRspAction
EVENT_PAN_MMI_CONNECT_IND
State:1->2
```

這證明 Windows 角色與實際連線呼叫可以工作，也證明先前失敗的直接原因之一是手機端授權逾時。

接著進行乾淨斷線再連線，MediaTek 仍再次詢問。另以 API 17 Dalvik 測試 `BluetoothDevice.setTrust(true)`：呼叫回傳 true，但 `getTrust()` 前後都為 false；真實重連也仍詢問。手機未 root（`su -c id` 被拒，`ro.secure=1`、`ro.debuggable=0`），所以不採修改 `/system/app` 的高風險方案。

### 5. 逆向確認 MTK 授權 Binder

從手機唯讀拉出 `MtkBt.apk`／`MtkBt.odex`，再以手機本身的 Dalvik class loader 反射，確認：

- 服務：`com.mediatek.bluetooth.pan.BluetoothPanService`
- 私有介面：`com.mediatek.bluetooth.pan.IBluetoothPanAction`
- 方法：`disconnectPanDeviceAction(String)`、`authorizeRspAction(String, boolean)`
- `BluetoothPanAlert` 本身也是透過 ServiceConnection 呼叫此介面。

App 現在監聽 MTK PAN `CONNECTION_STATE_CHANGED`。只有狀態為 CONNECTING、遠端已配對且 Bluetooth major class 為 COMPUTER 時，才向指定 MTK 服務送出允許；未知裝置、未配對裝置及非電腦類別不會被自動放行。

第一次封包測試把 Binder transaction 1 當成授權，手機日誌立刻顯示 `disconnectPanDeviceAction`，反而主動斷線。這個失敗也被保留為根因證據。依服務端的真實行為改為 transaction 2 後，無人值守測試在約 40 毫秒內得到：

```text
EVENT_PAN_MMI_CONNECTION_AUTHORIZE_IND
State:0->1
authorizeRspAction
QuietPanelPan: Auto-authorized paired computer ...
EVENT_PAN_MMI_CONNECT_IND
State:1->2
```

這段相容碼是針對目標 MediaTek 4.2.2 ROM；AOSP 與其他廠商的 PAN 服務不一定有這個私有介面。可對照 AOSP 的 [PanService 原始碼](https://android.googlesource.com/platform/packages/apps/Bluetooth/%2B/31be0d2/src/com/android/bluetooth/pan/PanService.java)。若服務不存在，QuietPanel 會保留系統既有行為，不會假裝成功。

### 6. PAN 已連線但 TCP 仍失敗：韌體 DHCP 與介面網段互相矛盾

人工允許後，手機建立的不是常見 `bnep0`，而是：

```text
btn0 = 192.168.44.1/24
```

同一時間，MediaTek 呼叫 netd 啟動的 DHCP 範圍卻是：

```text
tether start 192.168.5.2 192.168.5.254 192.168.6.2 192.168.6.254
```

Windows 收不到同網段租約，只得到 `169.254.254.49`。這是本案最隱蔽、也是「PAN 顯示已連線但程式仍失敗」的真正網路根因。把 Windows Bluetooth PAN 設為 `192.168.44.2/24` 後：

- `ping 192.168.44.1`：4/4 成功，0% loss，約 27–73 ms。
- TCP 27183：成功。
- TCP 27184：成功。
- 手機 `/proc/net/tcp6` 同時確認 27183（`6A2F`）與 27184（`6A30`）在 LISTEN。

因此新增 `Setup-Bluetooth-PAN.ps1/.cmd`，用網卡描述而非中文介面名稱尋找 PAN，先保存原 IP 狀態，再設定 Windows `192.168.44.2/24` 及 Bridge `phone_ip=192.168.44.1`。可用 `Setup-Bluetooth-PAN.cmd -RestoreDhcp` 回復 DHCP。

### 7. 最終端到端驗收

驗收不是只看配對圖示或 ping，而是同時確認 PAN、IP、TCP、應用協定與 UI：

1. 新 App 自行啟用 PAN tethering。
2. Bridge 發出 Windows NAP 連線。
3. App 對已配對電腦自動回覆 MTK 授權。
4. 手機建立 `btn0 192.168.44.1/24`。
5. Windows 保有 `192.168.44.2/24`。
6. `netstat` 顯示 `192.168.44.2:<動態埠> → 192.168.44.1:27183 ESTABLISHED`。
7. 手機顯示 `IP LIVE · Rust Bridge 8.1.5 已連線`，CPU、記憶體及流量數據持續更新。
8. 27184 圖片通道也曾成功連線；短連線完成後進入 TIME_WAIT 是正常現象。
9. 控制器冷重啟後的最終正式版測試沒有新的 BTHUSB Event 3。

另以 `BluetoothDisconnectFromNetwork` 乾淨斷線，確認 `btn0` 消失，再只啟動 Bridge 做第二輪；PAN、`btn0`、27183 ESTABLISHED 與 `IP LIVE` 全部再次自動恢復。這排除了沿用舊 PAN 或人工按鈕的假成功。

正式 Release 的額外回歸還發現一項 Windows 10 舊 PAN API 時序：第一個 `BluetoothConnectToNetwork` 偶爾會一直阻塞，第二個呼叫回傳 Win32 548（已有連線作業）後，第一個呼叫才完成，手機也立即建立 `btn0`。因此 8.1.6 最終版把 PAN 放在背景工作，但同一輪最多只有「原呼叫 + 8 秒後一次救援呼叫」；主循環仍每兩秒檢查 `192.168.44.1`，連上後不再送任何 PAN 呼叫。這保留舊堆疊需要的喚醒，同時消除舊版無上限工作執行緒的風險。在大量連續測試後的熱狀態，受控救援成功連線但控制器留下過一筆 Event 3；正常斷線並以 PnP 冷重啟控制器後，正式 8.1.6 單次呼叫即自行連線，沒有使用救援，也沒有新增 Event 3。

## 這台機器往後怎麼做

### 第一次或重灌後

1. 將手機與 Windows 一般藍牙配對；不要同時連線藍牙喇叭做首次診斷。
2. 安裝 `dist\QuietPanel-v8.1.6.apk`。
3. 在 `dist` 執行 `Setup-Bluetooth-PAN.cmd`。接受 Windows 管理員提示；此工具會設定 `192.168.44.2/24`，並保存 `Bluetooth-PAN-before-QuietPanel.json`。
4. 檢查 `dist\QuietPanelBridge.json`：

   ```json
   {
     "bluetooth_device": "68:DF:DD:0C:C1:AE",
     "phone_ip": "192.168.44.1"
   }
   ```

5. 開啟手機 QuietPanel，選 `AUTO` 或 `BT`，再執行 `dist\QuietPanelBridge.exe`。
6. 正常結果是手機顯示 `IP LIVE`，不是只在 Windows 顯示「已配對」。

### 日常使用

手機先開 QuietPanel、電腦再開 Bridge 即可。Bridge 在 TCP 已連線時不會持續騷擾藍牙控制器。若暫時沒有手機，重試會逐步退避，不需人工守候。

### 故障時依序檢查

```powershell
ipconfig
ping 192.168.44.1
netstat -ano | findstr 27183
```

- 沒有 Bluetooth PAN 網卡：先確認藍牙已開、手機仍配對、驅動正常。
- PAN 網卡不是 `192.168.44.2`：再執行 `Setup-Bluetooth-PAN.cmd`。
- ping 不通且手機沒有 `btn0`：是 PAN 尚未建立；關閉可能占用舊控制器的藍牙音訊裝置，再重開 Bridge。
- ping 通、27183 不通：確認 QuietPanel App 在前景或仍在執行。
- 27183 ESTABLISHED、UI 沒更新：再查 App／Bridge 版本與協定日誌，不要重新配對作為第一步。

若要取消此機專用靜態 IP：

```cmd
Setup-Bluetooth-PAN.cmd -RestoreDhcp
```

## 其他機器會不會遇到

會遇到其中一部分，但不一定是相同組合。

| 情況 | 其他機器的可能性 | 處理 |
| --- | --- | --- |
| 配對成功但 PAN 未授權 | 舊 Android／廠商客製 ROM 常見 | 看手機通知；本專案的自動允許只針對已驗證 MTK 4.2.2 介面 |
| PAN Up 但 Windows 得到 `169.254.x.x` | DHCP 壞掉或等待逾時時常見 | 先比對手機 PAN IP 與 DHCP 範圍；只有確認是本案 `192.168.44.1` 才套用此靜態設定 |
| 手機介面不是 `bnep0` | 廠商核心命名差異很常見 | 不要把診斷腳本寫死為 `bnep0`；本機是 `btn0` |
| 舊 Intel 控制器被音訊占用 | 舊驅動／多 profile 共用控制器時可能發生 | 首次測試關閉喇叭；使用該機型 OEM 最終驅動；必要時重啟裝置或 Windows |
| Windows 顯示 Connected 但 TCP 不通 | 很常見的分層誤判 | 分別驗證 ACL、PAN 介面、IP、ping、TCP 27183、App UI |
| 公用網路防火牆 | Wi-Fi 探索／入站規則可能受影響 | 只新增程式所需 TCP/UDP 規則；不要把防火牆關掉當解法 |
| 電腦本身也使用 `192.168.44.0/24` | 會造成路由衝突 | 不要執行本機預設腳本；須另行規劃兩端位址，而此未 root 手機的 PAN IP 不易更改 |
| 新 Android 或非 MTK | 私有 Binder 名稱／交易碼通常不同 | 不應複製 MTK transaction；先使用系統標準 tethering 行為並實測 |
| Windows 11／未來 Windows | 內部 `bthpanapi.dll` 相容性不能保證 | 換 OS 前做實機回歸；失敗時改用受支援的網路或裝置廠商方案 |

最重要的原則是不要把「配對」、「藍牙 ACL Connected」、「PAN 網卡 Up」、「IP 可達」與「QuietPanel TCP 已握手」混成同一個狀態。它們是連續的五層，每層都應有獨立證據。

## 程式修正摘要

| 檔案 | 修正 |
| --- | --- |
| `bridge/src/pan.rs` | 正確註冊／列舉 NAP，呼叫 Windows PAN connect，移除假成功 |
| `bridge/src/main.rs` | 固定 IP 優先、PAN 單一背景工作加一次受控喚醒、指數退避、TCP 連線時不重送 PAN |
| `android/.../BluetoothPanController.java` | 啟用 tethering、保留 proxy、限定已配對電腦、MTK transaction 2 自動授權 |
| `android/.../MainActivity.java` | 狀態由誤導的 `USB LIVE` 改為 `IP LIVE` |
| `dist/QuietPanelBridge.json` | 加入手機固定 IP `192.168.44.1` |
| `Setup-Bluetooth-PAN.*` | 備份並設定／還原 Windows PAN IPv4 |
| `build.ps1` | 從 `VERSION` 產生 8.1.6 檔名並封裝設定工具 |

## 已知限制

- 此地點沒有可讓手機與電腦位於同一區網的 Wi-Fi，因此 Wi-Fi 路徑只完成程式層測試，沒有實機端到端驗收。
- MTK 自動授權依賴目標 ROM 的私有 Binder；它不是跨 Android 廠商的標準 API。
- Windows PAN connect 使用系統內部 DLL，已在 Windows 10 19045 實測，但不承諾未來版本相容。
- Android Release 目前仍用本機 debug signing key，適合私人設備更新，不適合公開商店發行。

## 最終判定

原問題已由實機證據定位並修復。真正的主要根因是「MediaTek 每次 PAN 授權」與「`btn0 192.168.44.1` 對上錯誤 DHCP 範圍」；Windows Bridge 假成功、過度重試、老舊驅動與 A2DP 占用則使表象更混亂。最終方案不是關防火牆、反覆配對或盲目重試，而是逐層修正 PAN 建立、手機授權、IPv4 配置與 Bridge 重試策略。
