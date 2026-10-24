# 建置 Meadow TV

此倉庫的 Android TV App 可獨立建置；Mac adapter 的完整重建另需未公開的 Meadow Core。一般使用者可直接使用 [v0.8.16-preview.1 預編譯檔](https://github.com/akaiHuang/meadow-tv/releases/tag/v0.8.16-preview.1)，安裝與配對流程見 [GETTING-STARTED.zh-TW.md](GETTING-STARTED.zh-TW.md)。

這份文件描述建置方式，不代表已指定整體原始碼授權；請先閱讀 [README](../README.md) 與[第三方條件](../THIRD_PARTY_NOTICES.md)。

## Android 建置條件

| 項目 | 版本／設定 |
|---|---|
| JDK | 17 |
| Android SDK platform | 34 |
| Android Build Tools | 34.0.0 |
| Gradle wrapper | 8.9，含發行檔 SHA-256 核對 |
| Android Gradle Plugin | 8.5.2 |
| minSdk／targetSdk | 28／34 |
| WebRTC Android | `io.github.webrtc-sdk:android:144.7559.15` |
| 單元測試 | JUnit 4.13.2、Robolectric 4.14.1 |
| App 版本 | `0.8.16`／versionCode `30` |
| 本次公開預覽 tag | `v0.8.16-preview.1` |

使用 Android Studio 管理 SDK 或將 `sdkmanager` 加入 PATH。首次建置需要網路下載 Gradle、Android 相依與測試資源；本倉庫不提供 SDK、系統映像或依賴快取。WebRTC 使用預編譯 AAR，不需要為這個 App 自行編譯 Android 核心或原生 WebRTC。

```sh
git clone https://github.com/akaiHuang/meadow-tv.git
cd meadow-tv
```

請確認目前使用的 Java 為 JDK 17，並將 `ANDROID_HOME` 指向自己的 SDK。macOS 安裝 Android Studio 的常見設定如下：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

若 SDK 位於其他位置，改用自己的路徑。`local.properties` 可在本機設定 `sdk.dir`，不應提交到倉庫。Windows 使用 `gradlew.bat`，Java／SDK 設定方式依作業系統調整。

## 建置 Android APK

預設 ABI 為 **armeabi-v7a**：

```sh
./gradlew --no-daemon assembleDebug
```

產物：`app/build/outputs/apk/debug/app-debug.apk`。

Apple Silicon 上的 ARM64 模擬器，或需要 ARM64 的目標，使用獨立的 ABI 設定：

```sh
./gradlew --no-daemon -PmeadowAbis=arm64-v8a assembleDebug
```

這些命令只建置，不會連線裝置、切換預設首頁或修改電視系統設定。API／ABI 相容不代表各電視的 HDMI、遙控器麥克風及廠商介面皆已通過驗收。

檢查與 release 建置：

```sh
./gradlew --no-daemon testDebugUnitTest lintRelease
./gradlew --no-daemon assembleRelease
```

release 產物：`app/build/outputs/apk/release/app-release.apk`。目前 `release` 使用 Android 開發簽章設定，並非正式商店簽章流程。發佈者的私鑰不隨倉庫公開；在別台電腦重建通常會使用另一把本機開發金鑰，因此雜湊與簽章不會和下載的 APK 相同。Android 不允許不同簽章直接覆蓋既有套件，更新前請依安裝教學處理並保留資料。

## 公開版圖資

公開 source 不附官方品牌 logo 原圖或大量 `assets/art` 海報快取。已安裝 App 的 banner 優先，缺少時使用中性文字／圖形；目錄圖片於首次使用時從官方 CDN 下載。無網路、來源失效或尚未取得圖片時，介面可能不會和開發版截圖相同。

本次建置流程不需要先下載整個海報素材包。測試若需要固定影像，應使用已提供的中性測試資源，不將第三方原始素材重新混入發佈包。

## 一般使用者的 Mac companion

預編譯中控只提供 macOS ARM64；一般使用者不需要安裝 Go，也不需要取得 Meadow Core。仍需 Python 3、Node.js 與已安裝、登入且可使用的 Codex。

```sh
python3 tools/download-companion.py --tag v0.8.16-preview.1
python3 tools/companion.py doctor
```

下載腳本會取得 Release 的 `MeadowTV-companion-macos-arm64.tar.gz` 與 `SHA256SUMS`，核對後放入 `artifacts/private/bin/`。配對及前景啟動請依[安裝教學](GETTING-STARTED.zh-TW.md)。Mac 必須保持開機；目前不將受到 TCC 影響的背景自啟列為可用流程。

## 開發者重建 Mac adapter

這一節需要 **Go 1.25.0 及另行取得的 Meadow Core**。Core 未公開、未 vendor，單獨 clone 本倉庫不足以重建完整 companion。請勿將範例誤認為公開 Core 的下載方式。

將 `MEADOW_CORE_DIR` 設成自己具存取權的 Core Go 模組目錄：該目錄需包含 `go.mod`，通常是 Core 專案內的 `go/`，不是外層倉庫。

```sh
export MEADOW_CORE_DIR="/path/to/authorized-core/go"
python3 tools/build-bridge.py
```

腳本使用暫存 modfile 對應本機模組，不修改已提交的 `bridge/go.mod`，也不複製 Core 原始碼進入本倉庫。目標為 macOS ARM64，產物 `artifacts/private/bin/meadow-tv-bridge` 使用 trimpath／stripped 設定。

執行路徑可用環境變數指定：

| 變數 | 用途 |
|---|---|
| `MEADOW_CORE_DIR` | 開發者自行取得的 Core Go 模組目錄 |
| `CODEX_BINARY` | 本機 Codex 執行檔；未指定時先找 PATH，再使用可用的 Mac bundle 備援 |
| `NODE_BINARY` | Node.js 執行檔 |
| `ADB_BINARY` | ADB 執行檔；也可由 PATH／Android SDK 尋找 |

語音使用本機 Codex app-server 的特定 schema，取決於版本與帳戶能力，不保證每個訂閱都具相同功能。這份文件未將實驗語音、Mac 背景 TCC、實體麥克風或可聽到 cove 的驗收列為已完成。
