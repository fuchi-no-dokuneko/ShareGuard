@daily @uat @android @serial
Feature: Daily acceptance of Canonical Share
  Canonical Share must keep source material local, require explicit review,
  verify the exact output before saving, and describe every boundary honestly.

  Scenario: Home screen and threat-model settings are clear and persistent
    Given the configured Android app is installed
    And all configured app data is cleared
    When I launch the configured Android app
    Then Android text "Canonical Share" is visible
    And Android text "Paste or enter text" is visible
    And Android text "Choose one image" is visible
    And Android text "Saved Results" is visible
    And Android text containing "Processing stays on this device" is visible
    When I tap Android text "About and threat model"
    Then Android text "About and threat model" is visible
    And Android text containing "does not guarantee anonymity" is visible
    And Android text containing "Deletion removes the in-app result" is visible
    When I set the Android checkbox beside "Optional local share jitter" to checked
    And I press Android back
    And I tap Android text "About and threat model"
    Then the Android checkbox beside "Optional local share jitter" is checked
    When I save an acceptance screenshot named "home-threat-model"

  Scenario: Empty text cannot advance and output choices remain reversible
    Given all configured app data is cleared
    When I launch the configured Android app
    And I tap Android text "Paste or enter text"
    Then Android text "Text source" is visible
    And Android text "Accept text" is disabled
    And Android text containing "0 Unicode code points" is visible
    When I replace Android editable field 1 with "Daily acceptance text"
    Then Android text "Accept text" is enabled
    And Android text containing "21 Unicode code points" is visible
    When I set the Android checkbox beside "Reveal spaces, line endings and invisible characters" to checked
    And I tap Android text "Output: text"
    Then Android text "Choose output" is visible
    And Android text "Canonical text" is visible
    And Android text "Rebuilt image" is visible
    And Android text "Text and rebuilt image" is visible
    And Android text "Appearance-preserving image — Experimental" is not visible
    When I tap Android text "Rebuilt image"
    And I tap Android text "Choose preset"
    Then Android text "Choose a versioned workflow" is visible
    And Android text "Text reconstruction" is visible
    When I press Android back
    Then Android text "Choose output" is visible
    When I press Android back
    Then Android text "Text source" is visible
    And Android editable field 1 has value "Daily acceptance text"

  Scenario: A plain text source is reviewed, verified, saved, and reported
    Given all configured app data is cleared
    When I launch the configured Android app
    And I tap Android text "Paste or enter text"
    And I replace Android editable field 1 with "A short local note"
    And I tap Android text "Accept text"
    Then Android text "Sequential workflow" is visible
    And Android text "PRESET-TT-BALANCED" is visible
    And Android text "SYS-001" is visible
    When I tap Android text "SYS-001"
    Then Android text "Purpose" is visible
    And Android text "Findings" is visible
    And Android text "Changes" is visible
    And Android text "Settings" is visible
    And Android text "Verification" is visible
    When I tap Android text "Back to workflow"
    And I tap Android text "Run"
    Then Android text "Review possible semantic impact" is visible
    And Android text containing "Every application transformation" is visible
    When I tap Android text "Approve, verify and save"
    Then Android text "Verified and saved" is visible
    And Android text containing "Canonical text generated and verified" is visible
    When I tap Android text "Verification report"
    Then Android text "Verification report" is visible
    And Android text "Final assurance" is visible
    When I tap Android text "Back to result"
    And I tap Android text "Saved Results"
    Then Android text "Saved Results" is visible
    And Android text "Text result" is visible
    And exactly 1 visible Android elements have text "Share"

  Scenario: URL findings block approval until an explicit decision is recorded
    Given all configured app data is cleared
    When I launch the configured Android app
    And I tap Android text "Paste or enter text"
    And I replace Android editable field 1 with "https://example.com/news?utm_source=acceptance"
    And I tap Android text "Accept text"
    And I tap Android text "Run"
    Then Android text "Review findings" is visible
    And Android text containing "decision" is visible
    And Android text "Apply reviewed decisions" is disabled
    When I tap Android text "Show expert details" if it is visible
    Then Android text containing "Evidence:" is visible
    When I tap Android text "Remove known tracking fields"
    Then Android text "All decisions recorded" is visible
    And Android text "Apply reviewed decisions" is enabled
    When I tap Android text "Apply reviewed decisions"
    Then Android text "Review possible semantic impact" is visible
    And Android text containing "utm_source" is visible
    When I press Android back
    Then Android text "Canonical Share" is visible

  Scenario: Text can be verified as a freshly rebuilt image
    Given all configured app data is cleared
    When I launch the configured Android app
    And I tap Android text "Paste or enter text"
    And I replace Android editable field 1 with "Rebuild this local sentence"
    And I tap Android text "Output: text"
    And I tap Android text "Rebuilt image"
    And I tap Android text "Choose preset"
    Then Android text "Text reconstruction" is visible
    When I tap Android text "Open workflow"
    And I tap Android text "Run"
    Then Android text "Review possible semantic impact" is visible
    When I tap Android text "Approve, verify and save"
    Then Android text "Verified and saved" is visible
    And Android text "Exact final rebuilt image" is visible
    And Android text "Export rebuilt image copy" is visible
    And Android text "Export canonical text copy" is not visible

  Scenario: Text and rebuilt-image output exposes both verified artifacts
    Given all configured app data is cleared
    When I launch the configured Android app
    And I tap Android text "Paste or enter text"
    And I replace Android editable field 1 with "Create two verified representations"
    And I tap Android text "Output: text"
    And I tap Android text "Text and rebuilt image"
    And I tap Android text "Choose preset"
    Then Android text "Text plus reconstruction" is visible
    When I tap Android text "Open workflow"
    And I tap Android text "Run"
    Then Android text "Review possible semantic impact" is visible
    When I tap Android text "Approve, verify and save"
    Then Android text "Verified and saved" is visible
    And Android text "Canonical text" is visible
    And Android text "Exact final rebuilt image" is visible
    And Android text "Export canonical text copy" is visible
    And Android text "Export rebuilt image copy" is visible

  Scenario: Editing from finding review returns to the unchanged source
    Given all configured app data is cleared
    When I launch the configured Android app
    And I tap Android text "Paste or enter text"
    And I replace Android editable field 1 with "https://example.com/?utm_source=edit"
    And I tap Android text "Accept text"
    And I tap Android text "Run"
    Then Android text "Review findings" is visible
    When I tap Android text "Edit source"
    Then Android text "Text source" is visible
    And Android editable field 1 has value "https://example.com/?utm_source=edit"

  Scenario: Android shared text enters the text workflow without a chooser
    Given all configured app data is cleared
    When I share Android text "Shared acceptance note" directly to the configured app
    Then Android text "Text source" is visible
    And Android editable field 1 has value "Shared acceptance note"
    When I press Android back
    Then Android text "Canonical Share" is visible

  @requires-fixture-sender
  Scenario: A combined text and image share requires an explicit source choice
    Given all configured app data is cleared
    When I run acceptance fixture action "send-combined-text-and-image"
    Then Android text "Choose one source" is visible
    And Android text containing "processes exactly one source" is visible
    When I tap Android text "Use shared text"
    Then Android text "Text source" is visible
    When I run acceptance fixture action "send-combined-text-and-image"
    And I tap Android text "Use shared image"
    Then Android text "Image source" is visible
    When I tap Android text "Reject source"
    And I run acceptance fixture action "send-combined-text-and-image"
    And I tap Android text "Discard both"
    Then Android text "Canonical Share" is visible

  @requires-system-picker @requires-image-fixtures
  Scenario: Image intake reports detected facts and supports rejection
    Given all configured app data is cleared
    When I launch the configured Android app
    And I tap Android text "Choose one image"
    And I run acceptance fixture action "select-static-png"
    Then Android text "Image source" is visible
    And Android text "Detected format" is visible
    And Android text "Pixel dimensions" is visible
    And Android text "Source metadata entries" is visible
    And Android text containing "Local OCR review required" is visible
    When I tap Android text "Reject source"
    Then Android text "Canonical Share" is visible

  @requires-system-picker @requires-image-fixtures
  Scenario: Animated image input cannot continue
    Given all configured app data is cleared
    When I launch the configured Android app
    And I tap Android text "Choose one image"
    And I run acceptance fixture action "select-animated-image"
    Then Android text containing "unsupported" is visible
    And Android text "Continue" is disabled
    When I tap Android text "Reject source"
    Then Android text "Canonical Share" is visible

  @requires-system-picker @requires-image-fixtures
  Scenario: Derivative image mode requires a specific warning acknowledgement
    Given all configured app data is cleared
    When I launch the configured Android app
    And I tap Android text "Choose one image"
    And I run acceptance fixture action "select-static-png"
    And I tap Android text containing "Output:"
    Then Android text "Appearance-preserving image — Experimental" is visible
    When I tap Android text "Appearance-preserving image — Experimental"
    And I tap Android text "Choose preset"
    Then Android text "Experimental derivative" is visible
    When I tap Android text "Open workflow"
    And I tap Android text "Run"
    Then Android text "Experimental derivative warning" is visible
    And Android text "Approve, verify and save" is disabled
    And Android text containing "cannot exceed AS-1" is visible
    When I set the Android checkbox beside "I understand this export retains source-pixel relationships" to checked
    Then Android text "Approve, verify and save" is enabled
    When I tap Android text "Approve, verify and save"
    Then Android text "Verified and saved" is visible
    And Android text "Exact final derivative image" is visible
    And Android text containing "Re-encoded derivative" is visible
    And Android text "Export derivative image copy" is visible

  @requires-system-picker @requires-image-fixtures
  Scenario: Image OCR can produce verified canonical text
    Given all configured app data is cleared
    When I run acceptance fixture action "complete-image-canonical-text-workflow"
    Then Android text "Verified and saved" is visible
    And Android text "Canonical text" is visible
    And Android text "Export canonical text copy" is visible
    And Android text "Export rebuilt image copy" is not visible

  @requires-system-picker @requires-image-fixtures
  Scenario: Image OCR can produce a fully rebuilt image
    Given all configured app data is cleared
    When I run acceptance fixture action "complete-image-rebuilt-workflow"
    Then Android text "Verified and saved" is visible
    And Android text "Exact final rebuilt image" is visible
    And Android text "Export rebuilt image copy" is visible
    And Android text "Export canonical text copy" is not visible

  @requires-system-picker @requires-image-fixtures
  Scenario: Image OCR can produce matched text and rebuilt-image artifacts
    Given all configured app data is cleared
    When I run acceptance fixture action "complete-image-both-workflow"
    Then Android text "Verified and saved" is visible
    And Android text "Canonical text" is visible
    And Android text "Exact final rebuilt image" is visible
    And Android text "Export canonical text copy" is visible
    And Android text "Export rebuilt image copy" is visible

  @requires-fixture-sender
  Scenario: Unsupported multiple-image shares fail closed and can be discarded
    Given all configured app data is cleared
    When I run acceptance fixture action "send-multiple-images"
    Then Android text "Processing stopped" is visible
    And Android text containing "MULTIPLE_IMAGES_NOT_SUPPORTED" is visible
    And Android text containing "Mandatory verification was not weakened" is visible
    When I tap Android text "Discard session"
    Then Android text "Canonical Share" is visible

  @requires-failure-fixture
  Scenario: A text processing failure can recover only as a new editable source
    Given all configured app data is cleared
    When I run acceptance fixture action "induce-text-verification-failure"
    Then Android text "Processing stopped" is visible
    And Android text "Recover as editable text" is visible
    And Android text containing "Mandatory verification was not weakened" is visible
    When I tap Android text "Recover as editable text"
    Then Android text "Text source" is visible
    And Android editable field 1 has value "Recoverable fixture text"

  @requires-saved-fixture
  Scenario: Saved Results can be searched, sorted, filtered, and shown as a grid
    Given all configured app data is cleared
    When I run acceptance fixture action "create-text-and-image-saved-results"
    And I tap Android text "Saved Results"
    Then Android text "Saved Results" is visible
    When I replace Android editable field 1 with "Text result"
    Then Android text "Text result" is visible
    When I tap Android text containing "Sort:"
    And I tap Android text "oldest"
    Then Android text "Sort: oldest" is visible
    When I tap Android text containing "Filter:"
    And I tap Android text "text"
    Then Android text "Filter: text" is visible
    And Android text "Text result" is visible
    When I tap Android text containing "Layout:"
    Then Android text "Layout: grid" is visible
    When I relaunch the configured Android app
    And I tap Android text "Saved Results"
    Then Android text "Layout: grid" is visible
    And Android text "Sort: oldest" is visible

  @requires-saved-fixture
  Scenario: Saved detail metadata can be renamed, favourited, and edited as new
    Given all configured app data is cleared
    When I run acceptance fixture action "create-verified-text-result"
    And I tap Android text "Saved Results"
    And I tap Android text "Text result"
    Then Android text "Text result" is visible
    And Android text "Show local import date" is visible
    When I tap Android text "Show local import date"
    Then Android text "Hide local import date" is visible
    When I tap Android text "Favourite"
    Then Android text "Remove favourite" is visible
    When I tap Android text "Rename"
    Then Android text "Rename Saved Result" is visible
    When I replace Android editable field 1 with "Acceptance result"
    And I tap Android text "Save label"
    Then Android text "Acceptance result" is visible
    When I tap Android text "Edit as a new result"
    Then Android text "Text source" is visible
    And Android editable field 1 has value "A short local note"
    When I press Android back
    And I tap Android text "Saved Results"
    Then Android text "Acceptance result" is visible

  @requires-saved-fixture
  Scenario: Saved settings persist presentation, privacy, and waiting-target choices
    Given all configured app data is cleared
    When I run acceptance fixture action "create-verified-text-result"
    And I tap Android text "Saved Results"
    And I tap Android text "Settings"
    Then Android text "Saved Results settings" is visible
    And Android text "Require device authentication" is disabled
    When I set the Android checkbox beside "Show content previews" to checked
    And I set the Android checkbox beside "Block screenshots and obscure recent-app previews" to unchecked
    And I set the Android checkbox beside "Confirm before sharing prior to your target" to checked
    And I tap Android text "grid"
    And I tap Android text "name"
    And I tap Android text "Configure local target"
    Then Android text "User-defined waiting target" is visible
    When I replace Android editable field 1 with "5"
    And I tap Android text "Save local target"
    Then Android text containing "Optional waiting target: 5 min" is visible
    When I relaunch the configured Android app
    And I tap Android text "Saved Results"
    And I tap Android text "Settings"
    Then the Android checkbox beside "Show content previews" is checked
    And the Android checkbox beside "Block screenshots and obscure recent-app previews" is unchecked
    And the Android checkbox beside "Confirm before sharing prior to your target" is checked
    And Android text containing "Optional waiting target: 5 min" is visible
    When I press Android back
    Then Android text "Generic text icon" is visible

  @requires-saved-fixture @requires-system-sharesheet
  Scenario: Managed sharing states the boundary and permits returning without sharing
    Given all configured app data is cleared
    When I run acceptance fixture action "create-verified-text-result"
    And I tap Android text "Saved Results"
    And I tap Android text "Share"
    Then Android text "Before sharing" is visible
    And Android text containing "exact copy generated now" is visible
    And Android text "Return without sharing" is visible
    When I tap Android text "Return without sharing"
    Then Android text "Saved Results" is visible
    When I tap Android text "Share"
    And I tap Android text "Share now"
    Then the configured Android app is not foreground

  @requires-saved-fixture @requires-system-picker
  Scenario: External export cancellation preserves the in-app Saved Result
    Given all configured app data is cleared
    When I run acceptance fixture action "create-verified-text-result"
    And I tap Android text "Saved Results"
    And I tap Android text "Text result"
    And I tap Android text "Export canonical text copy"
    And I run acceptance fixture action "cancel-document-picker"
    Then Android text "Text result" is visible

  @requires-saved-fixture @requires-system-picker
  Scenario: Successful external export is disclosed during later deletion
    Given all configured app data is cleared
    When I run acceptance fixture action "create-verified-text-result"
    And I tap Android text "Saved Results"
    And I tap Android text "Text result"
    And I tap Android text "Export canonical text copy"
    And I run acceptance fixture action "save-document-copy"
    Then Android text "Text result" is visible
    When I tap Android text "Delete"
    Then Android text "Delete permanently from Canonical Share?" is visible
    And Android text "An exported external copy may still exist." is visible
    When I tap Android text "Cancel"
    Then Android text "Text result" is visible

  @requires-saved-fixture
  Scenario: Single and bulk deletion require explicit confirmation and support cancellation
    Given all configured app data is cleared
    When I run acceptance fixture action "create-two-verified-text-results"
    And I tap Android text "Saved Results"
    And I tap Android text "Text result"
    And I tap Android text "Delete"
    Then Android text "Delete permanently from Canonical Share?" is visible
    And Android text "Delete in-app result" is visible
    And Android text containing "external copy" is visible
    When I tap Android text "Cancel"
    Then Android text "Text result" is visible
    When I press Android back
    And I run acceptance fixture action "select-two-saved-results"
    Then Android text "Delete 2 selected" is visible
    When I tap Android text "Delete 2 selected"
    Then Android text "2 selected results" is visible
    When I tap Android text "Delete in-app result"
    Then Android text containing "No verified results match this view" is visible

  @requires-saved-fixture
  Scenario: Delete-all uses the same confirmation and removes only managed local results
    Given all configured app data is cleared
    When I run acceptance fixture action "create-two-verified-text-results"
    And I tap Android text "Saved Results"
    And I tap Android text "Settings"
    And I tap Android text "Delete all Saved Results"
    Then Android text "Delete permanently from Canonical Share?" is visible
    And Android text "2 selected results" is visible
    When I tap Android text "Cancel"
    Then Android text "Saved Results" is visible
    When I tap Android text "Settings"
    And I tap Android text "Delete all Saved Results"
    And I tap Android text "Delete in-app result"
    Then Android text containing "No verified results match this view" is visible

  @requires-integrity-fixture
  Scenario: A damaged Saved Result is blocked until successful revalidation
    Given all configured app data is cleared
    When I run acceptance fixture action "create-and-corrupt-saved-result"
    And I tap Android text "Saved Results"
    Then Android text "Revalidate before sharing" is visible
    When I tap Android text "More"
    Then Android text "Revalidate" is visible
    And Android text "Share" is disabled
    When I tap Android text "Revalidate"
    Then Android text containing "Integrity:" is visible

  Scenario: Transient source state clears while verified Saved Results survive relaunch
    Given all configured app data is cleared
    When I run acceptance fixture action "create-verified-text-result"
    And I tap Android text "Paste or enter text"
    And I replace Android editable field 1 with "Unsaved transient words"
    And I relaunch the configured Android app
    Then Android text "Canonical Share" is visible
    And Android text "Unsaved transient words" is not visible
    When I tap Android text "Saved Results"
    Then Android text "Text result" is visible

  @requires-saved-fixture @requires-secure-window-check
  Scenario: Sensitive-screen protection blocks screenshots and recent-app previews
    Given all configured app data is cleared
    When I run acceptance fixture action "create-verified-text-result"
    And I tap Android text "Saved Results"
    And I tap Android text "Settings"
    And I set the Android checkbox beside "Block screenshots and obscure recent-app previews" to checked
    And I press Android back
    And I tap Android text "Text result"
    Then Android text "Text result" is visible
    When I run acceptance fixture action "verify-secure-window-blocks-capture"
    And I press Android back
    And I tap Android text "Settings"
    And I set the Android checkbox beside "Block screenshots and obscure recent-app previews" to unchecked
    And I press Android back
    And I tap Android text "Text result"
    And I run acceptance fixture action "verify-capture-restored"

  Scenario: Installed privacy boundary excludes network and broad-media permissions
    Given the configured Android app is installed
    Then the app does not request Android permission "android.permission.INTERNET"
    And the app does not request Android permission "android.permission.ACCESS_NETWORK_STATE"
    And the app does not request Android permission "android.permission.READ_EXTERNAL_STORAGE"
    And the app does not request Android permission "android.permission.READ_MEDIA_IMAGES"
