# v0.8.16-preview.1 驗證紀錄

日期：2026-10-25。此公開預覽與開發版截圖來自同一個 Android TV MVP；公開版另移除品牌原圖／大量海報快取、使用可攜的安裝工具，並增加語音失敗時保留文字功能。

| 檢查 | 結果 |
| --- | --- |
| Android API28 原生單元／版面／焦點測試 | 157 項通過 |
| Android release lint | 0 errors、46 warnings |
| Go adapter 測試 | 36 項通過；維護者使用另具存取權的 Core 執行 |
| Node sidecar fixture | 21 項通過，無真實帳號或模型呼叫 |
| Python 公開目錄／安裝工具／發佈防呆 fixture | 22 項通過 |
| 新配對資料與 TLS | 全新本機 state 啟動成功；檢查 CA、hostname、pin 後才送出配對 token |
| 錯誤憑證 | 本機 TLS fixture 確認錯誤 CA、hostname 或 pin 不會收到 Authorization |
| Codex 訂閱文字 | 公開 companion 執行檔以本機已登入的 ChatGPT 認證完成一次簡短回覆；0 次 TV tool call |
| Android 安裝包 | release、ARMv7 + ARM64 universal；簽章驗證通過，未包含 debug probe |
| Mac 中控包 | macOS ARM64；僅執行檔與公開目錄資料／更新工具三個檔案，無配對或登入資料 |
| 截圖 | 七張原生模擬器畫面經人工檢查，PNG 未含文字／EXIF metadata |

安裝包及中控壓縮檔的 SHA-256 位於同版 Release 的 `SHA256SUMS`。下載器會先比對中控雜湊，再解壓允許的檔案；不接受路徑、符號連結或額外檔案。

已檢查公開來源及發布包中的可辨識憑證格式，並在本機比對已知操作員帳密／配對值；沒有將登入快取、配對 state、私鑰、原始 log 或內部 Git 歷史納入此倉庫。這是有範圍的檢查，不代表能辨識任意未知秘密。後續提交仍應執行 `python3 tools/check-public-files.py --mode tracked`。

未在此公開版重做實體電視安裝、遙控器麥克風、音訊聽感、完整影音起播／DRM、HDMI 小窗或投放相容性驗收；過去 ANR 的根因仍未確認。Mac 背景自啟的 TCC 限制也沒有在此版宣稱修復。測試程序已停止，沒有留下語音或模型測試工作。
