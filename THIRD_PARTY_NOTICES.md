# 第三方來源與授權

Meadow TV 使用下列元件、資料服務與內容來源。各項目適用其原有授權與服務條款；本專案自有程式碼尚未指定整體授權。

## 程式元件

### WebRTC Android

Android App 使用 `io.github.webrtc-sdk:android:144.7559.15`。該 Maven artifact 宣告 BSD-3-Clause；`webrtc-sdk/android` 打包專案採 MIT。原生程式庫及其他上游元件仍各自適用相應條款。

- [Android 打包專案](https://github.com/webrtc-sdk/android)／[指定版本 POM](https://repo1.maven.org/maven2/io/github/webrtc-sdk/android/144.7559.15/android-144.7559.15.pom)
- [WebRTC BSD 授權](third_party/licenses/WEBRTC-BSD-3-CLAUSE.txt)／[專利條款](third_party/licenses/WEBRTC-PATENTS.txt)
- [Android 打包專案 MIT 授權](third_party/licenses/WEBRTC-SDK-MIT.txt)／[上游其他元件 notices](https://github.com/webrtc-sdk/android/tree/main/Licenses)

授權原文來源：[WebRTC LICENSE](https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE)、[PATENTS](https://webrtc.googlesource.com/src/+/refs/heads/main/PATENTS)、[SDK LICENSE](https://github.com/webrtc-sdk/android/blob/main/LICENSE)。

### Material Symbols

部分介面圖示衍生自 Google Material Symbols，依 Apache License 2.0 提供。

- [Material Symbols](https://github.com/google/material-design-icons)
- [Apache 2.0 授權](third_party/licenses/MATERIAL-SYMBOLS-APACHE-2.0.txt)

### 建置工具與 Mac 中控

Gradle、Android Gradle Plugin、JUnit、Robolectric、Android SDK 及 Go 相依套件，適用各自的授權。Go 模組版本記錄於 `bridge/go.mod` 與 `bridge/go.sum`。Node.js 與 Codex 為另行安裝的執行工具。

Mac 中控包含未隨本倉庫提供的 Meadow Core；公開 adapter 原始碼的建置方式見[開發文件](docs/BUILD.md)。此處保留該依賴的 [Apache 2.0 授權與署名](third_party/licenses/MEADOW-CORE-APACHE-2.0.txt)，此授權不延伸到本專案其他自有程式碼。

## 投放接收器

Meadow TV 提供投放指南與服務發現介面。實際接收需另行安裝第三方接收器；下列專案未隨 Meadow APK 打包，並依其各自條款提供：

- [android-airplay-server](https://github.com/jqssun/android-airplay-server)：[GPL-3.0](https://github.com/jqssun/android-airplay-server/blob/main/LICENSE)
- [UxPlay](https://github.com/FDH2/UxPlay)：[GPL-3.0](https://github.com/FDH2/UxPlay/blob/master/LICENSE)

## 資料與封面

| 來源 | 用途 | 條件 |
| --- | --- | --- |
| [Netflix 台灣](https://www.netflix.com/tw/browse/genre/83) | 公開節目目錄與官方圖片網址 | 實際可播內容依所在地與訂閱而定。 |
| [HBO Max 台灣影集](https://www.hbomax.com/tw/zh/series)／[電影](https://www.hbomax.com/tw/zh/movies) | 公開精選與官方圖片網址 | 內容有效期間及訂閱依平台為準。 |
| [Apple TV 台灣](https://tv.apple.com/tw) | 公開節目與官方圖片網址 | 播放權限由 Apple TV 管理。 |
| [Apple Podcasts Search API](https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/index.html)／發布者 RSS | 節目探索、封面與單集音源 | 適用 Apple 及各發布者條款。 |
| [Google Chromecast Home](https://clients3.google.com/cast/chromecast/home) | 環境攝影背景 | 攝影圖權利屬原權利人。 |
| [Open-Meteo](https://open-meteo.com/) | 目前天氣與七日預報 | 適用 [CC BY 4.0 署名](https://open-meteo.com/en/licence)及 API 的[使用條件](https://open-meteo.com/en/terms)。 |

App 優先讀取已安裝 App 的 banner，缺少時顯示中性圖形或文字。節目封面於執行時從對應來源取得；公開包未附平台官方 logo 或大量海報素材。

## 圖片與品牌

專案截圖呈現開發版 Android TV 模擬器的使用情境。畫面中的影音品牌、節目封面與 Podcast 圖像仍屬各權利人，不作為可重用素材提供。

Meadow 標誌、結晶釉視覺、原創音效與天氣氛圍圖屬本專案自有素材，與第三方內容分開處理。

Apple、tvOS、AirPlay、YouTube、Netflix、HBO Max 等名稱與商標屬各權利人。Meadow TV 是獨立專案，與上述平台無隸屬、贊助或背書關係；產品基於 Android TV，並非 Apple tvOS。名稱與圖片的使用亦須遵守各權利人條件，例如 [Apple 商標與圖片指南](https://www.apple.com/legal/intellectual-property/guidelinesfor3rdparties.html)。
