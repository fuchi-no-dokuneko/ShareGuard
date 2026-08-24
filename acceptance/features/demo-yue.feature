@demo @cantonese @android
Feature: Canonical Share 粵語產品介紹

  Scenario: 介紹經審核標準文字流程及受管理範圍
    Given the configured Android app is installed
    And all configured app data is cleared
    When I begin a recorded demo
    And I launch the configured Android app
    Then Android text "Canonical Share" is visible
    When I narrate in "yue-HK" for at least 11 seconds:
      """
      Canonical Share 係離線優先嘅 Android 應用程式，分享文字或者圖片之前，會先建立同驗證一份經審核嘅版本。
      """
    And I tap Android text "Paste or enter text"
    Then Android text "Text source" is visible
    When I narrate in "yue-HK" for at least 10 seconds:
      """
      你可以喺呢度輸入文字。字元數量同隱藏字元顯示功能，可以幫你發現平時睇唔到嘅內容。
      """
    And I replace Android editable field 1 with "Visit https://example.com/news?utm_source=demo"
    And I set the Android checkbox beside "Reveal spaces, line endings and invisible characters" to checked
    And I tap Android text "Output: text"
    Then Android text "Choose output" is visible
    When I narrate in "yue-HK" for at least 12 seconds:
      """
      你要清楚揀想保留乜嘢。標準文字會捨棄原圖像素同樣式，重建圖片就會建立全新而受控制嘅表示方式。
      """
    And I tap Android text "Canonical text"
    And I tap Android text "Choose preset"
    Then Android text "Choose a versioned workflow" is visible
    When I narrate in "yue-HK" for at least 9 seconds:
      """
      每個流程預設都有版本，亦會列明審核工作、網址處理方法、輸出類型同原圖像素界線。
      """
    And I tap Android text "Open workflow"
    Then Android text "Sequential workflow" is visible
    When I narrate in "yue-HK" for at least 9 seconds:
      """
      執行之前，你可以逐項查看必要步驟嘅用途、發現、改動、警告同限制。
      """
    And I tap Android text "Run"
    Then Android text "Review findings" is visible
    When I narrate in "yue-HK" for at least 11 seconds:
      """
      程式唔會靜靜雞改變意思。呢個追蹤欄位一定要由你明確決定，先可以繼續。
      """
    And I tap Android text "Remove known tracking fields"
    Then Android text "All decisions recorded" is visible
    When I tap Android text "Apply reviewed decisions"
    Then Android text "Review possible semantic impact" is visible
    When I narrate in "yue-HK" for at least 11 seconds:
      """
      語意影響畫面會先列出完整結果，同埋每一項會改變位元組嘅轉換，之後先做最終驗證。
      """
    And I tap Android text "Approve, verify and save"
    Then Android text "Verified and saved" is visible
    When I tap Android text "Verification report"
    Then Android text "Verification report" is visible
    When I narrate in "yue-HK" for at least 12 seconds:
      """
      報告會記錄已執行嘅檢查同已聲明限制。保證只適用於呢份受管理成品，唔代表之後修改過嘅副本，亦唔係匿名保證。
      """
    And I tap Android text "Back to result"
    And I tap Android text "Saved Results"
    Then Android text "Text result" is visible
    When I narrate in "yue-HK" for at least 10 seconds:
      """
      已驗證結果會加密保存在應用程式私人儲存。你可以再檢查、改名、重新驗證、匯出、分享或者明確刪除。
      """
    And I save an acceptance screenshot named "cantonese-demo-saved-result"
    And I finish the recorded demo
