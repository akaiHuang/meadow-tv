# 開發與建置

Android TV App 可從本倉庫獨立建置。若只想安裝使用，請直接下載 [Release](https://github.com/akaiHuang/meadow-tv/releases) 並依[安裝教學](GETTING-STARTED.zh-TW.md)設定。

## Android 開發環境

| 項目 | 版本／設定 |
| --- | --- |
| JDK | 17 |
| Android SDK platform／Build Tools | 34／34.0.0 |
| Gradle wrapper／Android Gradle Plugin | 8.9／8.5.2 |
| minSdk／targetSdk | 28／34 |
| WebRTC Android | `io.github.webrtc-sdk:android:144.7559.15` |
| 測試 | JUnit 4.13.2、Robolectric 4.14.1 |

```sh
git clone https://github.com/akaiHuang/meadow-tv.git
cd meadow-tv
```

安裝 JDK 17 與 Android SDK，將 `ANDROID_HOME` 設為自己的 SDK 目錄。使用 Android Studio 的 macOS 環境可參考：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

首次建置需要網路下載相依套件。Windows 請改用 `gradlew.bat`，並依本機環境設定 Java 與 SDK。

## 建置 APK

預設建置 ARMv7：

```sh
./gradlew --no-daemon assembleDebug
```

產物為 `app/build/outputs/apk/debug/app-debug.apk`。

ARM64 裝置或 Apple Silicon 模擬器可指定：

```sh
./gradlew --no-daemon -PmeadowAbis=arm64-v8a assembleDebug
```

同時支援 ARMv7 與 ARM64 的 Release APK：

```sh
./gradlew --no-daemon -PmeadowAbis=armeabi-v7a,arm64-v8a assembleRelease
```

產物為 `app/build/outputs/apk/release/app-release.apk`。目前預覽版使用開發簽章；自行建置的 APK 可能與 Release 簽章不同，Android 無法直接用不同簽章覆蓋安裝。卸載會清除 App 資料，更新前請先確認需要保留的收藏與清單。

## 執行檢查

```sh
./gradlew --no-daemon testDebugUnitTest lintRelease
python3 -m unittest discover -s tools/tests
node --test bridge/voice/*.test.mjs
```

影像測試使用中性測試素材。App 執行時優先顯示已安裝 App 的 banner，目錄封面依可用來源取得；公開原始碼不包含平台官方素材包。

## Mac 中控

一般使用者請使用預編譯的 macOS ARM64 中控：

```sh
python3 tools/download-companion.py --tag v0.8.16-preview.1
```

設定、配對及啟動方式見[安裝教學](GETTING-STARTED.zh-TW.md)。

### 從原始碼建置 adapter

需要 Apple Silicon Mac、Go 1.25.0，以及另行取得的 Meadow Core。Core 未包含在本倉庫中，因此無法僅靠公開原始碼重建完整中控。

將 `MEADOW_CORE_DIR` 設為已取得存取權的 Core Go 模組目錄，再執行：

```sh
export MEADOW_CORE_DIR="/path/to/authorized-core/go"
python3 tools/build-bridge.py
```

該目錄需包含 Core 的 `go.mod`。建置工具會暫時連結本機模組，不會複製 Core 原始碼或改寫已提交的模組設定。

可依環境指定執行工具：

| 變數 | 用途 |
| --- | --- |
| `MEADOW_CORE_DIR` | Core Go 模組目錄，僅建置時使用 |
| `CODEX_BINARY` | Codex 執行檔 |
| `NODE_BINARY` | Node.js 執行檔 |
| `ADB_BINARY` | ADB 執行檔 |

授權範圍與第三方條款見[第三方來源](../THIRD_PARTY_NOTICES.md)。本專案尚未指定整體程式碼授權。
