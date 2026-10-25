# 相容性與已知限制

適用版本：**v0.8.16-preview.1**。

## 支援環境

| 項目 | 支援範圍 |
| --- | --- |
| 電視 App | Android TV 9 以上；Release APK 包含 ARMv7 與 ARM64。 |
| Mac 中控 | Apple Silicon Mac；需要 Python 3、Node.js 與已登入的 Codex。 |
| 基本功能 | 遙控器導覽、App 捷徑、公開節目目錄、Podcast 搜尋與播放清單、七日天氣。 |
| Codex 文字 | 透過 Mac 中控使用自己的 Codex 帳號。可用模型與額度依帳號及工作區設定而定。 |
| Codex 語音 | 實驗功能，依 Codex 版本、帳號權限及電視麥克風相容性而定。語音不可用時可繼續使用文字。 |

## 使用限制

- Meadow TV 是 Android TV 首頁 App；原廠設定、系統電源畫面與韌體仍由電視廠商提供。
- Mac 中控使用期間需保持開機，並在終端機持續執行。目前預覽版採手動啟動。
- 各電視的遙控器麥克風、HDMI 小窗、投放與音訊行為可能不同，仍需依機型確認。
- 影音服務的訂閱與播放權限由各平台管理。公開節目目錄不代表個人帳號一定可以播放該內容。
- 跨 App 操作需要無障礙權限；第三方 App 若未提供操作項目，部分要求可能無法完成。
- 播放穩定性仍在改善中。若遇到播放無回應，請重新開啟 App，並附上裝置與操作步驟回報。

## 截圖與下載

專案截圖來自 Android TV 模擬器的開發版。實際外觀會依已安裝 App、可取得的封面與裝置顯示設定有所不同。

請從 [GitHub Releases](https://github.com/akaiHuang/meadow-tv/releases) 下載安裝檔。Mac 中控下載工具會核對同版校驗碼後再安裝；安裝步驟請見[開始使用](GETTING-STARTED.zh-TW.md)。
