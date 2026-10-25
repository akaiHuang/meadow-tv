# 安裝 Meadow TV，連接你的 Codex

本指南適用於 **v0.8.16-preview.1**。你可以先安裝電視 App，使用首頁、天氣與 Podcast；想使用 Meadow 助理時，再完成 Mac 與 Codex 的連接。

## 準備什麼

| 項目 | 需求 |
| --- | --- |
| 電視 | Android TV 9 以上，ARMv7 或 ARM64，支援安裝 APK 與 ADB 偵錯。 |
| Mac | Apple Silicon Mac，與電視連上同一個私人區域網路。使用助理時需保持開機。 |
| 帳號 | 你自己的 ChatGPT 帳號，具有 Codex 存取權。 |
| 工具 | [Python 3](https://www.python.org/downloads/macos/)、[Node.js／npm](https://nodejs.org/en/download)、[Android SDK Platform-Tools（ADB）](https://developer.android.com/tools/releases/platform-tools)、OpenSSL。 |

影音服務的 App 與會員需另外安裝及登入。

## 1. 註冊帳號，登入 Codex

到 [ChatGPT](https://chatgpt.com) 註冊或登入自己的帳號，確認帳號或工作區具有 Codex 存取權。方案與額度以官方及帳號頁面顯示為準。

在 Mac 開啟「終端機」，安裝 Codex：

```sh
npm install -g @openai/codex
```

接著執行：

```sh
codex login
```

在瀏覽器完成 **Sign in with ChatGPT**，回到終端機確認登入狀態：

```sh
codex login status
```

顯示已透過 ChatGPT 登入後即可繼續。這個流程**不需要 API key**，也不需要在電視上輸入 ChatGPT 密碼。

更多登入方式可參考 [OpenAI 官方安裝教學](https://learn.chatgpt.com/docs/codex/cli)與[登入說明](https://learn.chatgpt.com/docs/auth#sign-in-with-chatgpt)。

## 2. 下載 Meadow TV

到 [Release 下載頁](https://github.com/akaiHuang/meadow-tv/releases/tag/v0.8.16-preview.1)，取得：

- **MeadowTV-0.8.16-preview.1-universal.apk**：電視安裝檔，先存到 Mac 的「下載項目」。
- [**專案工具與原始碼（ZIP）**](https://github.com/akaiHuang/meadow-tv/archive/refs/heads/main.zip)：另行下載並解壓縮，裡面有連接工具與最新教學。

在終端機輸入 `cd `（尾端保留一個空白），把解壓縮後的資料夾拖進去，再按 Enter。接下來的工具指令都在這個資料夾執行。

下載 Mac 中控：

```sh
python3 tools/download-companion.py --tag v0.8.16-preview.1
```

工具會自動核對下載檔案。看到下載成功後，再繼續下一步。

## 3. 連接電視，安裝 App

先從 [Android 官方 Platform-Tools](https://developer.android.com/tools/releases/platform-tools) 安裝 Mac 版 ADB，並將解壓縮後的資料夾加入終端機的 `PATH`。可用以下指令確認：

```sh
adb version
```

在電視開啟「開發人員選項」與偵錯功能，從網路資訊查看電視的區域網路 IP。各品牌的設定位置不同。

若電視提供網路 ADB，使用電視顯示的埠號連接。以下以 `5555` 為例；若電視顯示其他埠號，請替換：

```sh
printf '電視的區域網路 IP：'
read -r TV_IP
adb connect "${TV_IP}:5555"
adb devices
```

在電視上允許這台 Mac 的偵錯請求。`adb devices` 中，電視狀態應為 `device`。若電視使用 USB 偵錯或無線配對，請依[官方 ADB 說明](https://developer.android.com/tools/adb)及機型支援的方式連接。

從 `adb devices` 複製電視序號，安裝並開啟 Meadow：

```sh
printf '電視序號：'
read -r TV_SERIAL
adb -s "$TV_SERIAL" install -r "$HOME/Downloads/MeadowTV-0.8.16-preview.1-universal.apk"
adb -s "$TV_SERIAL" shell am start -n com.akai.livingtv/.MainActivity
```

若安裝檔不在「下載項目」，請替換 APK 路徑。安裝完成後，就能先體驗 Meadow 首頁、天氣與 Podcast。

## 4. 配對 Mac 與 Meadow

在相同的終端機中輸入電視 IP，完成配對：

```sh
printf '電視的區域網路 IP：'
read -r TV_IP
python3 tools/companion.py setup --tv "$TV_IP"
python3 tools/companion.py pair --serial "$TV_SERIAL"
```

配對設定由工具自動處理，不需要手動複製登入資料。

若 Mac 同時使用有線網路、Wi-Fi 或 VPN，可指定電視能連到的 Mac 位址：

```sh
printf 'Mac 的區域網路 IP：'
read -r MAC_IP
python3 tools/companion.py setup --tv "$TV_IP" --host "$MAC_IP"
python3 tools/companion.py pair --serial "$TV_SERIAL"
```

需要指定模型時，在 `setup` 加上 `--model 模型名稱`，並選擇帳號可使用的模型。

## 5. 啟動 Meadow 助理

```sh
python3 tools/companion.py start
```

保持這個終端機視窗開啟。若 macOS 詢問本機網路權限，請允許正在使用的終端機存取本機網路。停止中控時，在此視窗按 **Ctrl-C**。

回到電視，開啟右上角 **Meadow**。文字連線就緒後，可以試試：

> 幫我找幾個台灣心理學 Podcast。

> 請開啟 YouTube。

> 看看目前的播放清單。

語音就緒時，按「按此開始講話」開始收音；第一次使用請允許麥克風權限。語音屬實驗功能，可用性依 Codex 版本、帳號與遙控器而異。顯示「語音暫不可用」時，仍可使用文字。

離開首頁或切換 App 會停止語音連線，閒置約三分鐘也會自動斷線。

## 讓 Meadow 成為電視首頁

在電視的「預設應用程式／首頁」設定選擇 Meadow。各品牌位置不同，部分機型可能不提供此選項；需要切回時，在相同位置選回原本的首頁。

若想使用遙控器快捷鍵與跨 App 操作，到系統「無障礙」啟用 Meadow 服務。此版本的服務名稱可能顯示為 **Living TV 遙控器**。啟用後，Meadow 可讀取目前介面的文字與可操作項目，並執行支援的導覽操作；你隨時可以在相同設定中關閉。

## 常見問題

| 問題 | 處理方式 |
| --- | --- |
| 指令顯示找不到工具 | 確認 Python 3、Node.js、ADB、OpenSSL 已安裝並加入 `PATH`，再重新開啟終端機。 |
| Codex 顯示未登入 | 執行 `codex login`，完成瀏覽器登入，再用 `codex login status` 確認。 |
| 電視顯示 `unauthorized` | 在電視上允許偵錯請求，再執行 `adb devices`。 |
| 配對後仍顯示離線 | 確認中控正在執行、Mac 與電視可以互連，並檢查本機網路權限與 VPN 設定。 |
| Mac 或電視換了 IP | 用新位址重新執行 `setup`、`pair`，再啟動中控。 |
| 模型不可用或額度不足 | 查看 Codex 帳號的可用模型、方案與剩餘額度。 |
| 文字正常，語音無法使用 | 檢查麥克風權限與遙控器支援情況；版本相容資訊見[相容性說明](VERIFICATION.md)。 |
| 無法操作其他 App | 檢查 Meadow 無障礙服務是否啟用；部分 App 的畫面不支援自動導覽。 |
| 更新時顯示簽章不相容 | 確認安裝來源。解除安裝會清除 Meadow 的收藏與偏好，請先保留需要的資料。 |

需要檢查連線時，可另開終端機，切到專案資料夾執行：

```sh
python3 tools/companion.py doctor
```

不再使用時，先停止 Mac 中控，再依需要關閉電視無障礙服務、切回原本首頁或解除安裝 Meadow。清除 App 資料會一併刪除配對、收藏與播放清單。

更多資料使用方式請參考[隱私與安全](SECURITY.md)。回報問題時，只需提供版本、機型與問題畫面，請勿分享密碼或完整診斷紀錄。
