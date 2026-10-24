# 安全、隱私與配對

適用於 `v0.8.16-preview.1` 的 Meadow TV 個人本機預覽版。Android／adapter 原始碼公開；Mac companion 使用 Release 的預編譯程式，私有 Meadow Core 不另公開。本文件描述資料與權限邊界，不將本機架構視為完全離線或第三方服務的資料保留保證。

## 帳號只在自己的 Mac 登入

在 Mac 使用 `codex login` 開啟官方 ChatGPT 瀏覽器登入，並以 `codex login status` 確認。Meadow 安裝流程不要求 OpenAI API key，也不要求你向作者提供帳號、密碼、驗證碼或存取權杖。

Codex 的登入憑證由 Codex 管理，可能存在作業系統憑證儲存，或 `~/.codex/auth.json` 等本機快取。**`auth.json` 應視同密碼**，不要放入電視、專案、Git、Issue、聊天、安裝包或可分享的雲端資料夾。[OpenAI 官方 Authentication 文件](https://developers.openai.com/codex/auth#credential-storage) 說明登入快取與憑證儲存方式。

電視只得到該台中控的連線端點、獨立配對 token 與 TLS 憑證指紋，不會得到你的 Codex 登入快取。影音 App 各自的會員與登入仍由各服務管理。

官方 [Codex App Server 文件](https://developers.openai.com/codex/app-server#auth-endpoints) 將這類既有整合的 app-server 認證限於本機／開源應用；請勿把這套個人中控改成公開網站、多人代管代理或轉售別人的帳號／額度。方案存取、模型、額度與資料處理仍受 OpenAI 及工作區政策約束。

## 配對提供什麼保護

| 機制 | 用途與限制 |
| --- | --- |
| 獨立 bearer token | Mac 驗證電視請求的秘密。拿到它的人可能冒用電視請求，不應公開、截圖或寫入公開設定。它不是 Codex OAuth token。 |
| TLS 憑證 pin | 電視以已配對的 SHA-256 憑證指紋確認 Mac 中控；不接受任意憑證，也不以 HTTP 或忽略 TLS 錯誤來修復連線。pin 不是密碼或私鑰。 |
| 已授權 ADB 配對 | `pair` 需使用電視已允許的 ADB 裝置，經受 shell 權限限制的 receiver 寫入設定；不是知道電視 IP 就能配對。 |
| 私人 LAN 與來源限制 | App 的 bridge 連線限私人 IPv4／loopback、HTTPS，拒絕轉址；中控另有 token、來源與 Origin 檢查。這些不是公開網際網路服務的防護設計。 |

這不是雙向 TLS；電視以 pin 驗證中控，電視請求以 bearer token 驗證。請只在你信任的家庭／私人 LAN 使用，不要做路由器埠轉發、公開 tunnel 或將 ADB／中控埠暴露到網際網路。

安裝工具不會自動改防火牆、變更 Wi-Fi 或安裝背景 daemon。Mac companion 由你在終端機前景啟動；遇到 macOS 本機網路／TCC 提示，使用系統提供的授權流程，不以管理員命令、解除隔離或停用安全檢查繞過。

## 電視權限與可見資料

- **麥克風：**選配語音按鈕實際啟動收音時才使用；未授權仍可使用文字。Android 是否提供遙控器音源仍依裝置而定。
- **無障礙服務：**由你手動選擇是否開啟。啟用後，可按工具請求讀取目前介面的可見文字、描述、套件／視窗名稱、資源 ID、焦點及可操作節點，並執行受支援的導覽／按鈕操作；也會攔截指定的遙控器快捷鍵。
- **網路、音訊、前景服務與喚醒：**用於中控、公開節目資料、串流、音量與播放；通知／前景任務用於顯示持續執行的工作。睡眠定時使用相應鬧鐘能力。
- **天氣：**依使用者選擇的城市查詢，App 未要求裝置定位權限。

無障礙樹按需讀取，不是持續上傳螢幕影片。這個版本以節點樹操作，不具擷取 DRM 影片像素的能力。標記為 password 的節點會清除文字／描述，敏感頁面與部分敏感操作有阻擋；**其他可見文字仍可能送給 Mac 與 Codex**。網站／App 若沒有正確標記欄位，不能保證所有帳號、個資或私人訊息都被遮罩。請勿把登入、付款或私人對話頁當作自動操作測試。

關閉無障礙服務後，這些讀取、跨 App 操作與快捷鍵攔截會停止；Meadow 自身的方向鍵介面、文字聊天與可用的媒體功能可獨立使用。不要透過批次停用系統套件或取得 root 來強行開啟權限。

## 資料會去哪裡

### 文字與電視工具

使用者送出的文字、受限長度的近期對話、公開節目目錄與任務所需的目前 UI 狀態，由電視經配對 HTTPS 送到 Mac，再由本機 Codex app-server 送往 OpenAI。每個工具的真實操作結果／回執再回到中控與模型，供它決定下一步。

文字基礎功能與實驗語音分開；語音不可用時不以 Google TTS 假裝 Codex 聲音。支援什麼模型、是否有額度及工作區權限，應依自己的登入狀態確認。

### 選配語音

按下語音鍵才開啟 AudioRecord，音訊以記憶體中的 PCM frame 經電視→配對 Mac→Codex realtime 傳送。轉錄再交給同一個 Meadow Harness，回覆由 realtime 的 cove 語音經 WebRTC 播回電視。

目前程式沒有將原始 PCM／錄音檔寫入磁碟的路徑，但語音與轉錄會送給 OpenAI；這不等於供應商不保留資料。語音連線也可能連到 Google STUN 協助 WebRTC 連線，這與 Google 語音辨識／TTS 不同。這一版沒有 Google 語音備援，也不因語音失敗無限自動重試。

相容基準包含實驗版 `0.164.0-alpha.11.1` realtime schema；每個 Codex 帳號不一定有語音資格。離開首頁／切換 App 會停止語音，閒置約三分鐘斷線；已交接的受限電視任務可繼續完成並留下回執。

### 其他服務

不是所有網路流量都只到 Mac：Podcast 搜尋／查詢、RSS、出版商音源與封面、公開影音圖資及 Open-Meteo 天氣等服務，也可能直接收到一般連線資訊與相應搜尋詞。Netflix、YouTube 等 App 另有各自的帳號、DRM、網路與隱私政策。

## 本機保存哪些資料

| 位置 | 可能保存的內容 |
| --- | --- |
| Mac 的 Codex 私有資料／作業系統憑證儲存 | Codex 登入、設定，以及 Codex 自己管理的使用紀錄。由 Codex 與使用者的工作區政策管理，不隨 Meadow 配對傳給電視。 |
| 專案 `artifacts/private/bridge` | `token`、`cert.pem`、`key.pem`、`pairing.json`、`companion.json`、中控工作資料及可能的診斷檔。 |
| 專案 `artifacts/private/bin` | 從同版 Release 校驗後下載的 companion 與其資產。二進位檔不是帳號登入憑證，但不要以它取代來源與雜湊核對。 |
| Android App 私有儲存空間 | bridge 端點／token／pin、個人檔案與偏好、播放清單、Podcast 收藏、節目／圖資快取，以及最新電視任務文字回執。 |
| 執行中的記憶體 | 受限近期對話、按需 UI 節點快照、暫時 PCM frame 等。 |

中控安裝工具使用私有資料夾／檔案權限；這不能取代 Mac 使用者帳號與磁碟安全。Android 的配對 token 保存在 App 私有偏好中，不應宣稱它額外經 Android Keystore 加密。App 關閉 Android 自動備份；自行備份、root 或擷取儲存空間仍可能取得資料。

CLI fallback／診斷可能留下含有模型回覆或工具內容的事件檔、stderr 與 metadata，例如 `last-inference-events.jsonl`。日誌不應整包公開，也不要假定標成 debug 的內容就沒有敏感資料。

## 不應上傳的內容

請勿將下列資料提交到 Git、Release、Issue、聊天、公開網頁或分享資料夾：

- `auth.json`、整個 `.codex`、ChatGPT／Codex OAuth 或 access token、帳號密碼與驗證碼。
- Meadow 配對 token、含端點／token／pin 的配對設定、`.state`、`artifacts/private` 或其他完整私有 runtime 資料夾。
- `keys`／`certs` 中的本機私密資料、`key.pem`、憑證私鑰；支援回報也不需要整份 `cert.pem`。
- ADB 的 `adbkey`／`adbkey.pub`／`adbkeys` 與電視授權資訊、Android 簽章 keystore（如 `.jks`、`.keystore`）及其密碼。
- `.env`、完整終端機輸出、Codex／companion／ADB 日誌、轉錄、UI 快照、個人影音紀錄或未遮罩截圖。

`.gitignore` 只是防止誤加入的第一道措施；已被追蹤的檔案或 Git 歷史不會因加入忽略規則就自動消失。

## 停止、撤銷與輪換配對

**暫停使用：**在中控終端機按 Ctrl-C；若不再需要跨 App 功能，從電視設定關閉 Meadow 無障礙服務。要恢復原首頁，也從系統預設首頁設定切回先前的 Launcher。

**取消或輪換 Meadow 配對：**

1. 先停止所有使用這份私有狀態的中控程序。
2. 只刪除這份 Meadow 專案內的 `artifacts/private/bridge`，包含舊 token、憑證及設定。不要刪除 Codex 的登入快取，也不要刪除整個使用者資料夾。
3. 重新執行 `python3 tools/companion.py setup --tv ...`，再對已授權的電視執行 `python3 tools/companion.py pair --serial ...`，最後重新 `start`。新的 token／憑證將取代舊配對。
4. 不再使用的電視應移除 Meadow 儲存的配對；Android 的「清除儲存空間」或卸載 App 也會刪除播放清單、收藏與偏好，請先確認並恢復原首頁。

配對輪換不是登出 Codex。只有你要撤銷這台 Mac 本身的 Codex 登入時，才另外依官方帳號流程登出。若遺失電視或懷疑配對 token 外洩，應先停止中控、輪換配對，並移除不再信任的電視來源；若 Codex 憑證可能外洩，則另依 OpenAI 官方帳號流程撤銷，兩者不能互相取代。

ADB 權限也獨立管理：在電視開發人員選項關閉不再需要的偵錯／撤銷偵錯授權。撤銷 ADB 不等於刪除既有 Meadow 配對；取消 Meadow 配對也不會自動撤銷 ADB。

## 回報問題或漏洞

一般問題請提供版本、裝置類型、Android 版本、使用文字或實驗語音、可重現步驟，以及**已遮罩**的簡短錯誤碼。以 `doctor` 的布林檢查結果及人工整理的步驟為主，送出前仍自行檢查，不要整包上傳私有狀態或日誌。

若發現憑證／token 洩漏或可繞過配對的漏洞，請使用專案 GitHub 頁面可用的私密安全回報管道；沒有可用管道時，先用不含秘密、個資或可利用細節的一般 Issue 請維護者提供私密聯絡方式。不要在公開 Issue 貼出可用的權杖、登入檔或受害者資料。
