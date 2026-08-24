# 特定裝置版：Redmi／Android 4.2 私有相簿

這個分支目前的穩定版本是 `42.0.6-redmi42`。

- Git 分支：`codex/special-redmi-android42`
- 對應手機：第一代 Redmi，Android 4.2.2
- 依賴：`PhonePrivateAlbum` 的 `com.quietphoto.privatealbum.photos` Provider
- 用途：只服務這台舊手機及其既有的私有相簿配置

這是裝置專用版本，不是通用功能的基線。未來通用版應從整合前的
`b69fe3e` 基線另開分支，避免把舊手機專用 Provider、Android 4.2 相容
處理和相關選項帶進所有新裝置。

本版新增私有相簿來源資料夾勾選；`42.0.x-redmi42` 是這台手機專用的
獨立版號線，與通用版 `9.x` 不連續。需安裝本版才會看到資料夾篩選。

`42.0.6` 將第三頁設定整理成可展開的「時鐘與版面、相簿與播放、天氣、
電源與夜間、鬧鐘」五區，並把儲存操作固定在畫面底部；設定鍵值與功能不變。

`42.0.2` 在暫離相簿頁時保留目前照片，只停止背景掃描與動畫；重新進入
會立即顯示原畫面，再於背景更新相簿索引。

## 目前發行方式

- `build.ps1` 只建置 Private flavor，不再產生 Public APK。
- 日常安裝使用 `dist\QuietPanel.apk`；帶版號的封存檔是
  `dist\QuietPanel-v42.0.6-redmi42-Private-Storopia.apk`。
- ADB Bridge 使用 `dist\QuietPanelBridge.exe`；Wireless Bridge 使用
  `dist\QuietPanelBridge-Wireless.exe`。
- ADB 與 Wireless Bridge 共用單一執行個體鎖，避免兩個 Bridge 同時向手機
  傳送資料。
- 相片微幅平移以 5 FPS 更新；低耗電模式仍完全停止平移。
- 第四頁保留 CAPTURE 與 PASTE 快捷鍵，不再顯示 YouTube 按鈕；巨集頁原有
  YouTube 動作仍保留。

重建與安裝：

```powershell
.\build.ps1
.\dist\Install-Android.cmd
```
