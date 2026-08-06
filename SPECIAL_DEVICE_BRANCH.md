# 特定裝置版：Redmi／Android 4.2 私有相簿

這個分支的版本標記是 `v42.0.1-redmi42-private-album`。

- Git 分支：`codex/special-redmi-android42`
- 對應手機：第一代 Redmi，Android 4.2.2
- 依賴：`PhonePrivateAlbum` 的 `com.quietphoto.privatealbum.photos` Provider
- 用途：只服務這台舊手機及其既有的私有相簿配置

這是裝置專用版本，不是通用功能的基線。未來通用版應從整合前的
`b69fe3e` 基線另開分支，避免把舊手機專用 Provider、Android 4.2 相容
處理和相關選項帶進所有新裝置。

本版新增私有相簿來源資料夾勾選；`42.0.x-redmi42` 是這台手機專用的
獨立版號線，與通用版 `9.x` 不連續。需安裝本版才會看到資料夾篩選。
