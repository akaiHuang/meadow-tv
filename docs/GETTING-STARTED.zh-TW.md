# Meadow TV：從零開始安裝

本指南對應 **`v0.8.16-preview.1`**，公開專案是 [akaiHuang/meadow-tv](https://github.com/akaiHuang/meadow-tv)。這是個人區域網路使用的預覽版：Android TV 提供首頁、媒體與操作介面，Apple Silicon Mac 在前景執行中控，使用你自己登入的 Codex 帳號。

先跑通文字聊天、開啟 App 與 Podcast 搜尋。**Codex 語音是選配實驗功能**；有 Codex 文字存取權不代表一定能使用即時語音。安裝這個 App 不會重刷 Android、修改核心、清除系統套件或變更 Wi-Fi。

## 1. 準備設備與工具

| 項目 | 需要準備的內容 |
| --- | --- |
| 電視 | Android TV 9／API 28 以上，ARMv7 或 ARM64；能以遙控器操作，允許安裝 APK。其他晶片與廠商功能仍需各機型驗證。 |
| Mac | Apple Silicon Mac，能連到電視的同一個私人區域網路；使用期間保持開機、可連網及中控程序運作。 |
| 帳號 | 你自己的 ChatGPT 帳號，具有 Codex 存取權；組織帳號還需工作區允許。 |
| Mac 工具 | Python 3、Node.js／npm、Codex CLI、Android SDK Platform-Tools 的 `adb`，以及產生本機 TLS 憑證用的 `openssl`。一般使用者不需要 Go、Android Studio 或 Meadow Core 原始碼。 |
| 影音服務 | YouTube、Netflix、HBO Max、Apple TV 等 App 另行安裝及登入；Meadow 不提供這些服務的會員資格。 |

在 Mac 的「終端機」確認工具可執行：

```sh
python3 --version
node --version
npm --version
adb version
openssl version
```

缺少 `adb` 時，從 [Android 官方 SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) 下載 Mac 版，將解壓後的 `platform-tools` 加入終端機的 `PATH`。不必為了安裝 APK 下載整套 Android Studio。

## 2. 自己註冊與登入 Codex

1. 在 **Mac 的瀏覽器**開啟 [chatgpt.com](https://chatgpt.com)，自行註冊或登入。
2. 確認該帳號／工作區目前具備 Codex 存取權。可用方案、模型與額度會變動，以帳號內顯示及官方說明為準；本專案不代辦帳號、不提供共用登入。
3. 在終端機安裝 CLI：

   ```sh
   npm install -g @openai/codex
   codex --version
   ```

4. 啟動 ChatGPT 瀏覽器登入：

   ```sh
   codex login
   ```

   在開啟的官方網頁選擇自己的帳號並完成授權。密碼、驗證碼只輸入官方登入頁，不輸入電視或 Meadow 設定。

5. 回到同一個 Mac 使用者的終端機檢查：

   ```sh
   codex login status
   ```

   應顯示已透過 ChatGPT 登入。若顯示尚未登入，先完成這一步；若使用的是其他認證方式，依官方登入說明切換到你要使用的 ChatGPT 帳號。

這條安裝流程**不需要 OpenAI API key**。Codex 自行管理 Mac 上的登入快取／作業系統憑證儲存。不要把 `auth.json`、存取權杖或整個 `.codex` 目錄複製到電視、放進專案或上傳 GitHub。

以上指令已核對 [OpenAI 官方 Codex CLI 安裝文件](https://learn.chatgpt.com/docs/codex/cli) 與 [Authentication：ChatGPT 登入、登入狀態及憑證儲存](https://developers.openai.com/codex/auth)。CLI 登入與桌面 App 的登入不應只憑印象視為相同，請以這個終端機的 `codex login status` 為準。

## 3. 下載同一版的安裝檔與工具

開啟 [v0.8.16-preview.1 Release](https://github.com/akaiHuang/meadow-tv/releases/tag/v0.8.16-preview.1)。

- 下載 Android 安裝檔 **`MeadowTV-0.8.16-preview.1-universal.apk`**，預設放在 Mac「下載項目」。
- 下載該 tag 的 **Source code (zip)** 並解壓。這個資料夾含 Android／adapter 原始碼與使用者安裝工具。
- Mac 中控使用預編譯套件 **`MeadowTV-companion-macos-arm64.tar.gz`**，下一步由下載工具取得並依同版 `SHA256SUMS` 校驗。

在終端機先輸入 `cd `（尾端有一個空白），再把解壓後的專案資料夾拖進終端機，按 Enter。後續 `python3 tools/...` 指令都從這個專案根目錄執行。

```sh
python3 tools/download-companion.py --tag v0.8.16-preview.1
```

成功後，中控程式放在專案內的 `artifacts/private/bin`。下載校驗失敗或找不到該 tag 的資產時，先停止安裝，不要混用其他版本或來路不明的中控程式。

**Meadow Core 不包含在公開原始碼中**，一般使用者直接使用 Release 的預編譯 Mac companion。需要自行建置 Android／adapter 的開發者請看 [BUILD.md](BUILD.md)，不要把沒有私有 Core 的環境誤當作完整中控重建環境。

## 4. 授權 ADB，安裝到電視

### 先讓 Mac 成為已授權的裝置

在電視的系統設定開啟開發人員選項及偵錯；選項位置與能否經網路連線由廠商決定。只對自己的 Mac 確認「允許 USB 偵錯」，可勾選一律允許這台電腦。

若電視已提供並啟用網路 ADB，依電視顯示的 IP 與埠號連線。例如，電視明確使用 `5555` 時：

```sh
printf '電視的區域網路 IP：'
read -r TV_IP
adb connect "${TV_IP}:5555"
adb devices
```

若出現 `unauthorized`，請看電視上的授權視窗。只有狀態變成 `device` 才繼續。若電視沒有網路偵錯，先使用該機型支援的 USB／廠商偵錯流程；只有 IP 位址不能取得電視控制權。新版 Android TV 的無線偵錯配對埠也可能不同，不要一律套用 `5555`。可參考 [Android 官方 ADB 連線與授權說明](https://developer.android.com/tools/adb)。

### 明確指定要安裝的那台電視

從 `adb devices` 複製該台電視的序號；可能是 `IP:埠號`，也可能是 USB 序號：

```sh
printf 'adb devices 顯示的電視序號：'
read -r TV_SERIAL
adb -s "$TV_SERIAL" install -r "$HOME/Downloads/MeadowTV-0.8.16-preview.1-universal.apk"
adb -s "$TV_SERIAL" shell am start -n com.akai.livingtv/.MainActivity
```

若 APK 放在別的資料夾，請替換安裝檔路徑。看到 `Success` 只表示 APK 已安裝；下一步還要配對及實際操作。更新若顯示簽章不相容，不要直接清除資料；先確認來源與簽章，卸載會移除 App 內的配對、播放清單與偏好。

此時 Meadow 可當作一般 App 開啟，**不必立刻設為預設首頁，也不必先開無障礙服務**。

## 5. 建立 Mac 中控，配對電視

確定 `TV_IP` 是電視的私人區域網路 IPv4；若前一步用 USB，請從電視的網路資訊查到後輸入：

```sh
printf '電視的區域網路 IP：'
read -r TV_IP
python3 tools/companion.py setup --tv "$TV_IP"
python3 tools/companion.py pair --serial "$TV_SERIAL"
```

`setup` 會建立獨立的本機配對 token、TLS 憑證／私鑰與設定。`pair` 透過已授權 ADB，只把 **Mac 連線端點、配對 token、TLS 憑證指紋**交給 Meadow TV；不傳送 Codex 帳號、密碼或 `auth.json`。工具收到電視明確接受設定的結果，才會回報配對成功。

Mac 同時有 Wi-Fi、有線網路或 VPN，導致自動選錯連線位址時，可明確指定電視能到達的 Mac LAN IP：

```sh
printf '電視可連到的 Mac 區域網路 IP：'
read -r MAC_IP
python3 tools/companion.py setup --tv "$TV_IP" --host "$MAC_IP" --model gpt-6.1-sol
python3 tools/companion.py pair --serial "$TV_SERIAL"
```

`--host`、`--model` 都是選配；模型仍須你的 Codex 帳號／工作區有權使用。不要把公網 IP、路由器外部位址或 API key 填入這裡。完整參數可用 `python3 tools/companion.py --help` 及各子命令的 `--help` 查看。

## 6. 在前景啟動，確認健康狀態

```sh
python3 tools/companion.py start
```

保持這個終端機的中控程序執行；按 **Ctrl-C** 才停止。預覽版不預設安裝背景 daemon，也不會自動開路由器轉發、防火牆規則或用管理員權限繞過 macOS 限制。若 macOS 詢問本機網路權限，請依系統介面授權你正在使用的終端機；遇到 TCC／權限錯誤先依系統正常流程處理，不要改成背景服務或停用安全檢查。

再開一個終端機視窗，同樣切到專案根目錄，執行：

```sh
python3 tools/companion.py doctor
```

依結果檢查工具、ChatGPT 登入方式與中控 health。`doctor` 是基本診斷，不能證明電視、第三方 App 或帳號語音功能全部正常；接著完成下面的操作驗證。

## 7. 先跑通三件事

### 回到 Meadow，開啟 YouTube

用遙控器方向鍵移到 YouTube，按確認。應開啟已安裝的 YouTube 電視 App。用返回鍵回到 Meadow；尚未設為預設首頁時，也可從電視的 App 清單重新開啟 Meadow。

如果卡片不存在，先到電視官方商店安裝／啟用該 App。Meadow 不會替你取得影音會員，也不保證每家 App 接受同樣的深層連結。

### 用文字測試 Meadow

開啟右上角 Meadow，確認面板顯示文字已連線，輸入「請開啟 YouTube」並送出。要核對的是 **App 是否真的開啟**；「已送出」不等於「已完成」。回到 Meadow 後，跨 App 任務的結果可能以「上次任務」呈現，這是保存的回執，不會自動重新執行。

語音未就緒時，文字仍可使用。面板不會把沒有播放出來的內容假稱為已朗讀。

### 搜尋並播放一個 Podcast

在播放清單／我的節目中進入搜尋，或在 Meadow 文字框輸入：

> 幫我找幾個台灣心理學 Podcast，列出可以選的節目。

先確認結果有實際的節目／單集，再選擇一集加入清單或要求播放。核對播放介面的標題、進度及實際聲音；搜尋結果不是已播放的證明。搜尋、RSS 或音源出版商有時不可用，應保留失敗提示，不要反覆重送來消耗額度。

## 8. 選配：預設首頁與無障礙服務

這兩項分開選擇，安裝／配對不會自動替你開啟。

### 把 Meadow 設為首頁

先記下原本的首頁 App 名稱，再到電視系統設定的「預設應用程式／首頁」手動選擇 Meadow。位置依電視品牌而異，有些韌體不提供這個選項。

**復原：**回到同一個系統設定，將首頁切回原本的 App，或清除 Meadow 的預設開啟設定後重新選擇。不要為了切換首頁停用或移除原廠 Launcher。

### 允許 Meadow 讀取目前介面與跨 App 操作

若要使用按鍵捷徑、讀取目前畫面上的可操作節點、讓 Meadow 在其他 App 中導覽，請到電視系統設定的「無障礙」手動啟用 Meadow 的服務。這個版本可能仍顯示服務名稱 **「Living TV 遙控器」**；請核對它屬於你剛安裝的 Meadow App。

它能讀取可見介面的文字與節點並執行已支援的操作，且可能改變 Home／選單／輸入源／語音／指定影音快捷鍵的行為。先閱讀 [安全與資料流說明](SECURITY.md)，再決定是否授權。

**復原：**到同一個無障礙設定關閉服務，相關快捷鍵攔截與跨 App 讀取／操作就會停止。這不會刪除你在 Codex 的登入。某些操作在未授權或第三方 App 不提供可操作節點時會失敗，不代表需要 root 或改 Android 核心。

## 9. 選配實驗：Codex 語音

文字功能使用相容的 Codex app-server；語音另依賴實驗中的 realtime schema，這一版的相容基準是 **`0.164.0-alpha.11.1`**。一般 npm 版 CLI 可以作為文字功能的起點，但不能只憑版本號較新就推定支援語音。帳號／工作區還需能使用對應 realtime 功能；不保證每個 Codex 訂閱都已開放。

本機整合使用 app-server 的登入與串流事件；官方也說明 schema 隨所執行的版本產生，可參考 [Codex App Server](https://developers.openai.com/codex/app-server)。不要從不明來源下載 alpha binary，也不需要自己拼接 API 請求。

在語音已就緒的面板按「按此開始講話」，才會開啟麥克風。第一次會詢問 Android 收音權限；說完可再按一次送出，或等候較長停頓。聊天、搜尋與電視操作走同一個 Meadow 任務流程，回覆聲音使用 Codex realtime 的 cove，**沒有 Google 語音辨識或 Google TTS 備援**。

若顯示「語音暫不可用」，先使用文字；只有你再按語音按鈕才重試，不會因失敗無限重連。成功開啟面板也不等於已確認遙控器麥克風可用。離開首頁／切換 App 會停止語音，已交接的電視任務可繼續並保存回執；約三分鐘沒有使用會斷線。

## 常見問題

| 狀況 | 先做這件事 |
| --- | --- |
| `codex`／`node`／`python3`／`adb`／`openssl` 找不到 | 安裝對應工具，重新開終端機，確認其版本指令可執行。 |
| 瀏覽器已登入，CLI 仍未登入 | 用同一個 Mac 使用者執行 `codex login` 與 `codex login status`，不要搬別人的登入檔。 |
| `unauthorized`／`offline` | 看電視授權視窗，確認正確序號與偵錯模式，再用 `adb devices` 核對；不要先改 Wi-Fi 或重設電視。 |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | 核對是否為本版 ARMv7＋ARM64 APK；x86 模擬器需要另建對應 APK，不能用它推論實機相容性。 |
| 配對成功，但 health 或文字離線 | 保持 Mac 中控前景執行、同一個可互通的 LAN；檢查 Mac 本機網路權限、正確 `--host`、VPN／訪客網路隔離。 |
| DHCP 換了 IP | 查新的 TV／Mac 位址，重新 `setup`，再對正確序號 `pair`；不要停用 TLS 校驗。 |
| TLS 指紋不符 | 核對是否換過中控／憑證。確認是自己的 Mac 後重新配對，不能改成忽略憑證。 |
| 模型沒有權限／額度用完 | 查看自己的 Codex 帳號、工作區及可用模型／額度；不要以重連、換 API key 或反覆送出繞過限制。 |
| 文字正常，語音失敗 | 保留文字使用；分別檢查相容 schema、帳號開放狀態、Android 收音權限與遙控器輸入來源。 |
| 跨 App 操作找不到節點 | 確認無障礙服務已由你手動啟用，且目前頁面可操作；帳密／購買／系統敏感頁不應用來當成功測試。 |
| Mac 拒絕執行下載程式 | 核對 Release 來源與雜湊，依 macOS 正常安全介面處理；不使用解除隔離、關閉 Gatekeeper 或 TCC 繞過指令。 |

## 停止使用、解除配對或回復原狀

1. 在執行中控的終端機按 **Ctrl-C**。電視上的文字／語音連線會停止；本版沒有另行安裝的背景 daemon 要移除。
2. 不再需要跨 App 操作時，到電視無障礙設定關閉 Meadow 服務；需要原首頁時，從系統設定切回先前記下的首頁 App。
3. 要取消或輪換配對，先停止中控，再刪除**這份專案內的** `artifacts/private/bridge` 私有狀態資料夾，重新執行 `setup`、`pair`，最後重新 `start`。這會換掉舊配對 token 與憑證；不要刪除 Codex 的登入資料。
4. 若也要移除電視保存的配對，在 Android 的 Meadow App 設定清除儲存空間或解除安裝；這也會刪除 Meadow 的播放清單、收藏與偏好。先恢復首頁及關閉無障礙服務，再決定是否清除。

分享問題前請先看 [SECURITY.md](SECURITY.md)。不要把私有狀態、登入檔、完整日誌或配對資訊直接貼進 Issue。
