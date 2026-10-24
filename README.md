# Meadow TV

**Android TV 原生首頁，搭配可選的個人 Codex 中控。**

用遙控器在常用 App、影片、Podcast 與天氣之間切換。介面採毛玻璃、膠囊按鈕與清楚的焦點狀態；Meadow 助理可透過 Mac companion 讀取電視狀態，再由模型選擇可用工具。

這是 Android TV launcher 與個人 companion 的預覽專案，**不是 Apple tvOS、刷機韌體或修改後的 Android 核心**。

[下載 v0.8.16-preview.1](https://github.com/akaiHuang/meadow-tv/releases/tag/v0.8.16-preview.1) · [安裝教學](docs/GETTING-STARTED.zh-TW.md) · [自行建置](docs/BUILD.md) · [安全與限制](docs/SECURITY.md) · [驗證紀錄](docs/VERIFICATION.md) · [第三方來源](THIRD_PARTY_NOTICES.md)

> **公開範圍與授權**：本倉庫公開 TV App 與 adapter 原始碼，尚未指定整體程式碼授權，不能視為 MIT／Apache 或完整開源發布。Mac companion 含未隨倉庫公開的 Meadow Core；一般使用者可使用預編譯的 macOS ARM64 companion，僅憑此倉庫無法完整重建它。各第三方元件仍適用原有授權。

![Meadow TV 原生首頁，HBO Max 精選與常用 App](docs/screenshots/home.png)

截圖為開發版的 Android TV 模擬器原生畫面。公開版優先顯示已安裝 App 的 banner，沒有 banner 時使用中性文字／圖形備援；**不內附官方品牌 logo 或大量海報快取**。節目封面首次使用時從對應官方 CDN 取得，因此公開版備援樣式會和截圖不同。

## 可以做什麼

- **原生電視首頁**：方向鍵操作、常用 App、70% 置中玻璃子頁、膠囊列與圓形 X。原廠 Android 設定仍由系統顯示。
- **台灣公開節目目錄**：本次快照共 71 筆，HBO Max 24、Netflix 24、Apple TV 23。屬公開目錄精選，並非個人會員推薦，也不代表帳戶已驗證可播放。
- **Podcast 與收藏**：搜尋 Apple Podcasts 公開目錄、收藏節目、查看單集、加入播放清單。收藏與待播分開保存，加入不會自動播放；不需要 Spotify。
- **七日天氣**：Open-Meteo 預報與獨立城市卡片。移動焦點先瀏覽，確認才保存城市；背景是靜態氛圍圖，非即時攝影或影片。
- **可選的 Meadow 助理**：透過 Mac 上已登入的 Codex 處理文字與實驗語音。BM25 只檢索已註冊工具，模型決定何時讀取 Android 畫面、搜尋節目或操作電視；索引本身不執行動作。

首頁、天氣與 Podcast 可獨立使用。需要 Codex 的功能才必須啟動並配對 Mac companion。

## 畫面

<table>
  <tr>
    <td width="50%"><strong>Meadow 助理</strong><br><img src="docs/screenshots/meadow.png" alt="Meadow 助理原生介面，保留既有任務與連線狀態" width="100%"></td>
    <td width="50%"><strong>Podcast 播放器</strong><br><img src="docs/screenshots/player.png" alt="Podcast 封面、單集資訊與原生播放控制" width="100%"></td>
  </tr>
  <tr>
    <td><strong>播放清單</strong><br><img src="docs/screenshots/queue.png" alt="Podcast 與影片分頁、待播清單及我的節目入口" width="100%"></td>
    <td><strong>設定子頁</strong><br><img src="docs/screenshots/settings.png" alt="70% 毛玻璃設定面板、膠囊選項與圓形關閉鍵" width="100%"></td>
  </tr>
  <tr>
    <td><strong>七日天氣</strong><br><img src="docs/screenshots/weather.png" alt="原生七日天氣預報與其他城市入口" width="100%"></td>
    <td><strong>獨立城市視窗</strong><br><img src="docs/screenshots/cities.png" alt="直接浮在首頁上的城市照片卡片，可水平切換" width="100%"></td>
  </tr>
</table>

首頁、Meadow、播放器、清單與設定截圖來自 0.8.16；天氣與城市截圖來自 0.8.12。Meadow 圖中的連線狀態與上次任務，**不是這輪完成新語音輸入、轉錄或可聽到朗讀的證據**。以上皆非實體電視驗收畫面。

## 開始使用

1. 到 [Release](https://github.com/akaiHuang/meadow-tv/releases/tag/v0.8.16-preview.1) 下載符合裝置 ABI 的 APK，依[安裝教學](docs/GETTING-STARTED.zh-TW.md)設定。
2. 先確認方向鍵、App 開啟、天氣與 Podcast 瀏覽正常，再選擇是否設定 Mac companion。
3. Mac companion 的預編譯包僅提供 **macOS Apple Silicon／ARM64**。一般使用者不需要 Go，但仍需要 Python 3、Node.js 與已登入、可用的 Codex。

下載中控的命令：

```sh
python3 tools/download-companion.py --tag v0.8.16-preview.1
```

下載包為 `MeadowTV-companion-macos-arm64.tar.gz`，腳本會使用同版 `SHA256SUMS` 核對檔案。接著依教學設定電視位址與 ADB 序號；命令分成明確的步驟：

```sh
# 先將 TV_IP、ADB_SERIAL 設為自己的電視位址與 adb devices 所列序號。
python3 tools/companion.py setup --tv "$TV_IP"
python3 tools/companion.py pair --serial "$ADB_SERIAL"
python3 tools/companion.py doctor
python3 tools/companion.py start
```

`setup` 準備本機配對，`pair` 只對指定裝置送出配對設定，`start` 在前景執行；按 Ctrl-C 停止。**Mac 必須保持開機，使用助理時中控必須持續運行。** 背景自動啟動仍有 macOS TCC 權限問題，本預覽版不將它列為可用的預設流程。

## 使用前要知道

- 目標為 Android TV 9／API 28 以上；開發測試使用 Android TV 模擬器，不能等同各款電視的硬體與韌體。
- Codex 語音是版本相關的實驗整合，依本機 app-server schema 與帳戶實際能力而定，**不是所有訂閱必定可用**。沒有付費 API key 回退。
- 實體遙控器麥克風、辨識品質、可聽到的 cove 聲音、HDMI 小窗／靜音與投放音視訊，仍需實機驗證。
- 影音 App 的 DRM、會員可播權限、自繪且未暴露節點的畫面，不在一般 Android UI 工具的保證範圍。
- 外部 Android 系統設定與電源畫面沒有換皮；本專案沒有解鎖 bootloader、root 或更換 kernel／driver。
- 過去開發版曾遇到一次根因未定的播放 ANR，不能把介面截圖或本地測試當作完整播放穩定性保證。
- Release APK 使用開發簽章；簽章私鑰不公開。自行建置通常會使用不同的本機簽章，詳見[建置說明](docs/BUILD.md)。

## 原始碼與相依範圍

| 項目 | 本倉庫提供 | 建置／執行條件 |
|---|---|---|
| Android TV App | Java、資源、測試與 Gradle wrapper | JDK 17、Android SDK 34；可獨立建置 |
| Mac adapter | Go adapter、Node sidecar 與工具契約 | 重新建置需另具 Meadow Core 存取權 |
| Mac companion 預編譯包 | 透過 Release 下載 | macOS ARM64、Python 3、Node.js、Codex |
| Meadow Core | 未提供、未 vendor | 開發者需自行設定 `MEADOW_CORE_DIR` |
| AirPlay 接收器 | 未內附 | 另外安裝獨立接收器；見[第三方來源](THIRD_PARTY_NOTICES.md) |

這不是零相依，也不是整個系統都可由公開原始碼重建的套件。授權與平台圖片／商標的界線見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## English summary

Meadow TV is an Android TV launcher with an optional personal macOS Codex companion. It is not Apple tvOS, a ROM, or a kernel replacement. The TV app and adapter source are public, but no overall project license has been assigned. The companion depends on private Meadow Core, so it cannot be rebuilt from this repository alone; a macOS ARM64 preview binary is provided. Voice and physical-TV hardware behavior remain experimental and are not universally guaranteed.
