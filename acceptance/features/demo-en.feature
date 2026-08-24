@demo @english @android
Feature: English introduction to Canonical Share

  Scenario: Introduce the reviewed canonical-text workflow and managed boundary
    Given the configured Android app is installed
    And all configured app data is cleared
    When I begin a recorded demo
    And I launch the configured Android app
    Then Android text "Canonical Share" is visible
    When I narrate in "en-US" for at least 11 seconds:
      """
      Canonical Share is an offline-first Android app for reviewing one text or image source before sharing a verified representation.
      """
    And I tap Android text "Paste or enter text"
    Then Android text "Text source" is visible
    When I narrate in "en-US" for at least 10 seconds:
      """
      Start with text here. The character count and optional hidden-character view help expose content that ordinary rendering can conceal.
      """
    And I replace Android editable field 1 with "Visit https://example.com/news?utm_source=demo"
    And I set the Android checkbox beside "Reveal spaces, line endings and invisible characters" to checked
    And I tap Android text "Output: text"
    Then Android text "Choose output" is visible
    When I narrate in "en-US" for at least 12 seconds:
      """
      Choose what the app should preserve. Canonical text discards source pixels and styling, while rebuilt-image choices create fresh controlled representations.
      """
    And I tap Android text "Canonical text"
    And I tap Android text "Choose preset"
    Then Android text "Choose a versioned workflow" is visible
    When I narrate in "en-US" for at least 9 seconds:
      """
      Every preset is versioned and lists its review burden, URL policy, output type, and source-pixel boundary.
      """
    And I tap Android text "Open workflow"
    Then Android text "Sequential workflow" is visible
    When I narrate in "en-US" for at least 9 seconds:
      """
      The ordered workflow exposes every mandatory block before execution, including its purpose, findings, changes, warnings, and limits.
      """
    And I tap Android text "Run"
    Then Android text "Review findings" is visible
    When I narrate in "en-US" for at least 11 seconds:
      """
      Findings never change meaning silently. This tracking field needs an explicit decision before the review can continue.
      """
    And I tap Android text "Remove known tracking fields"
    Then Android text "All decisions recorded" is visible
    When I tap Android text "Apply reviewed decisions"
    Then Android text "Review possible semantic impact" is visible
    When I narrate in "en-US" for at least 11 seconds:
      """
      The semantic-impact screen lists the complete proposed result and every byte-changing transformation before final verification.
      """
    And I tap Android text "Approve, verify and save"
    Then Android text "Verified and saved" is visible
    When I tap Android text "Verification report"
    Then Android text "Verification report" is visible
    When I narrate in "en-US" for at least 12 seconds:
      """
      The report records the executed checks and declared limitations. Assurance applies only to this exact managed artifact, not to later edits or anonymity.
      """
    And I tap Android text "Back to result"
    And I tap Android text "Saved Results"
    Then Android text "Text result" is visible
    When I narrate in "en-US" for at least 10 seconds:
      """
      Verified results remain encrypted in app-private storage. From here they can be reviewed, renamed, revalidated, exported, shared, or explicitly deleted.
      """
    And I save an acceptance screenshot named "english-demo-saved-result"
    And I finish the recorded demo
