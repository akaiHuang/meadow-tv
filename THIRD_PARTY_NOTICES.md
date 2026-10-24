# 第三方元件、資料與圖片

此文件記錄預覽版 `v0.8.16-preview.1` 的主要第三方來源，**不替本專案自有程式碼指定新的 MIT、Apache 或其他整體授權**。目前尚未提供整體 LICENSE；公開可見不等同完整開源授權。各第三方元件仍適用自己的授權與條件。

## WebRTC Android

Android App 使用 `io.github.webrtc-sdk:android:144.7559.15`。此 Maven artifact 的 POM 宣告 **BSD-3-Clause**；提供打包流程的 `webrtc-sdk/android` 倉庫使用 **MIT**。打包倉庫的 MIT 不能取代 WebRTC 原生程式庫及其第三方元件的授權。

- [上游 Android 打包專案](https://github.com/webrtc-sdk/android)
- [指定版本 Maven POM](https://repo1.maven.org/maven2/io/github/webrtc-sdk/android/144.7559.15/android-144.7559.15.pom)
- [WebRTC BSD 授權原文](third_party/licenses/WEBRTC-BSD-3-CLAUSE.txt)
- [WebRTC 額外專利條款](third_party/licenses/WEBRTC-PATENTS.txt)
- [Android 打包專案 MIT 原文](third_party/licenses/WEBRTC-SDK-MIT.txt)
- [上游其他元件 notices](https://github.com/webrtc-sdk/android/tree/main/Licenses)

上述原文分別取自 [WebRTC LICENSE](https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE)、[PATENTS](https://webrtc.googlesource.com/src/+/refs/heads/main/PATENTS) 及 [SDK LICENSE](https://github.com/webrtc-sdk/android/blob/main/LICENSE)。WebRTC 編譯產物還包含其他上游元件，不能將整個原生二進位檔簡化標為「只有 MIT」。

## Material Symbols

部分介面圖示衍生自 Google Material Symbols，依 Apache License 2.0 保留其授權。公開版的中性 TV／播放備援圖形不是影音平台官方 logo。

- [Material Symbols 上游](https://github.com/google/material-design-icons)
- [Apache 2.0 授權原文](third_party/licenses/MATERIAL-SYMBOLS-APACHE-2.0.txt)

## AirPlay 是獨立接收器

Meadow TV 的投放頁是指南與服務發現介面，**未在 APK 內包入 UxPlay 或下列接收器的原生程式碼**。實際接收需另外安裝第三方 App，其相容性與授權獨立處理。

- [android-airplay-server](https://github.com/jqssun/android-airplay-server)，套件 `io.github.jqssun.airplay`：[GPL-3.0](https://github.com/jqssun/android-airplay-server/blob/main/LICENSE)
- [UxPlay](https://github.com/FDH2/UxPlay)：[GPL-3.0](https://github.com/FDH2/UxPlay/blob/master/LICENSE)

本次不重打包上述接收器 APK。日後若散布其程式碼或二進位檔，仍需依其對應授權處理，不能套用 Meadow 自有程式碼的條款取代。

## 公開目錄、天氣與封面

| 來源 | 用途 | 權利與使用界線 |
|---|---|---|
| [Netflix 台灣](https://www.netflix.com/tw/browse/genre/83) | 公開節目目錄與官方圖片網址 | 不代表會員帳戶可播放，也不是 Netflix 官方合作產品 |
| [HBO Max 台灣影集](https://www.hbomax.com/tw/zh/series)／[電影](https://www.hbomax.com/tw/zh/movies) | 台灣公開精選與官方圖片網址 | 內容有效期間與實際訂閱依平台為準 |
| [Apple TV 台灣](https://tv.apple.com/tw) | 公開節目與官方圖片網址 | 不提供或驗證個人 Apple 帳戶權限 |
| [Apple Podcasts Search API](https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/index.html)／發布者 RSS | 節目探索、封面與公開單集音源 | 仍受 Apple／發布者條件限制；查得到不等同取得任意重分發權利 |
| [Google Chromecast Home](https://clients3.google.com/cast/chromecast/home) | 環境攝影背景來源 | 攝影圖權利仍屬原權利人，不套用本專案程式碼授權 |
| [Open-Meteo](https://open-meteo.com/) | 目前天氣及七日預報 | 資料依 [CC BY 4.0 與署名條件](https://open-meteo.com/en/licence)；免費 API 另有[非商業使用及速率條件](https://open-meteo.com/en/terms) |

公開包不內附大量官方海報快取，也未提供官方 Netflix、HBO Max、Apple TV 或 YouTube logo 素材包。App 優先讀取使用者已安裝 App 的 banner，缺少時使用中性備援；目錄封面於執行時向對應官方 CDN 取得。這些圖片、影片、Podcast 及商標仍屬各權利人，不能因其網址公開而推定可任意使用或再散布。

## 截圖與自有素材

`docs/screenshots/` 展示開發版的實際 Android TV 模擬器畫面，其中可見的影音品牌、節目封面與 Podcast 圖像僅為功能情境；它們不是獨立素材包，也不納入 Meadow 自有程式碼的授權。公開版已改用中性品牌備援，因此外觀可能不同。

Meadow 標誌、結晶釉、原創音效及自有生成的天氣氛圍圖，與第三方平台素材分開處理。它們的公開展示不構成對平台商標或內容的授權，亦不替整個專案指定新的授權。

Apple、tvOS、AirPlay、YouTube、Netflix、HBO Max 等名稱或商標屬各權利人。Meadow TV 是獨立個人專案，未經上述平台授權、贊助或背書；它不是 Apple tvOS。[Apple 第三方商標與圖片指南](https://www.apple.com/legal/intellectual-property/guidelinesfor3rdparties.html)亦將名稱參照與圖形／logo 使用分開規範。

## 建置工具與 Mac companion

- Gradle wrapper、Android Gradle Plugin、JUnit、Robolectric，以及下載使用的 Android SDK，仍依各自上游條款提供；本倉庫不重新授權 Google Android TV 系統映像。
- Go adapter 的公開模組相依由 `bridge/go.mod`／`go.sum` 記錄，原授權不變。Node sidecar 使用 Node.js 內建模組；Node.js 與 Codex 本身不是本專案的程式碼。
- 預編譯 Mac companion 使用未隨本倉庫提供的 Meadow Core。此倉庫只提供 adapter 原始碼，不 vendor Core，也不宣稱可以僅靠公開 checkout 重建完整 companion。重建需另具合法存取權並設定 `MEADOW_CORE_DIR`。
- 預編譯檔所使用的 Meadow Core checkout 附有 [Apache 2.0 授權與原有署名](third_party/licenses/MEADOW-CORE-APACHE-2.0.txt)。此處保留依賴的授權原文，不將它套用到本專案其他自有程式碼，也不包含該私有 checkout 的原始碼。
- 使用本機 Codex／ChatGPT 登入仍受該產品與帳戶條件限制；發佈檔不包含 Codex 執行檔、登入憑證或付費 API key。
