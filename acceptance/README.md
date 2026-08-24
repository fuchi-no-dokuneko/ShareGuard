# Canonical Share acceptance pipelines

These three Gherkin pipelines describe the observable Android product:

- `run-uat.sh` is the granular daily human/AI acceptance checklist.
- `run-demo-en.sh` is the timed English product-introduction recording.
- `run-demo-yue.sh` is the timed Traditional Chinese Cantonese introduction.

## Validate or run

Dry validation parses the controlled Gherkin syntax and proves that every step has exactly one binding. It does not open Android and is not a product pass.

```bash
acceptance/run-uat.sh --dry-run
acceptance/run-demo-en.sh --dry-run
acceptance/run-demo-yue.sh --dry-run
```

A real run requires Python 3, one authorized ADB device, and the debug package `app.shareguard.canonical.debug`. Override `ADB_SERIAL`, `ADB`, `ACCEPTANCE_APP_PACKAGE`, or `ACCEPTANCE_APP_ACTIVITY` when needed. The runner preflights every requirement before executing the first scenario; it never runs a partial suite after failed preflight.

```bash
acceptance/run-uat.sh
acceptance/run-demo-en.sh
acceptance/run-demo-yue.sh
```

The image picker, document picker, sender, integrity, and multi-selection scenarios require a device-specific wrapper named by `ACCEPTANCE_FIXTURE_COMMAND`. The executable receives one of these actions in `ACCEPTANCE_FIXTURE_ACTION`:

```text
send-combined-text-and-image
send-multiple-images
select-static-png
select-animated-image
complete-image-canonical-text-workflow
complete-image-rebuilt-workflow
complete-image-both-workflow
create-verified-text-result
create-text-and-image-saved-results
create-two-verified-text-results
select-two-saved-results
cancel-document-picker
save-document-copy
create-and-corrupt-saved-result
induce-text-verification-failure
verify-secure-window-blocks-capture
verify-capture-restored
```

Fixture actions must use generated nonsensitive content and public UI or the repository's test build. They must not write a production database directly. The daily run is complete only when every unfiltered scenario executes.

Real runs write `checklist.json`, `summary.md`, screenshots, and UAT-only `sonar-test-execution.xml` under `build/reports/acceptance/<suite>/`. Dry runs write only `binding-validation.json`, which must never be submitted as UAT evidence.

## Recording hooks

`DEMO_RECORD_START_COMMAND` starts recording and returns. `DEMO_RECORD_STOP_COMMAND` finalizes it. `DEMO_TTS_COMMAND` receives `DEMO_TTS_LANGUAGE`, `DEMO_TTS_TEXT`, and `DEMO_TTS_MIN_SECONDS`. With no TTS command, narration is printed and the runner still waits the declared minimum time.

## Feature coverage

| Product area | Daily scenarios | Recording introduction |
| --- | --- | --- |
| Home, privacy claims, threat model, jitter | Home screen and threat-model settings | Opening and managed-boundary narration |
| Text input, validation, hidden characters | Empty text; plain text; URL review; edit source | Full canonical-text walkthrough |
| Output and preset transitions | Empty text; derivative image | Output and versioned-preset narration |
| Ordered workflow, details, findings, semantic approval | Plain text; URL findings | Workflow, decision, and impact narration |
| Image, OCR, animated rejection, all output modes, derivative warning | Image and fixture-backed share scenarios | Product scope introduced verbally |
| Verification, assurance, reports | Plain text; damaged Saved Result | Verification-report narration |
| Saved list, search, sort, filter, layout | Saved Results view scenario | Saved Result conclusion |
| Detail, rename, favourite, edit-as-new | Saved detail scenario | Saved Result conclusion |
| Settings, waiting target, screen protection | Saved settings scenario | Managed-storage conclusion |
| Managed share and external export | Share and export scenarios | Managed-boundary narration |
| Single, bulk, and delete-all confirmation | Two deletion scenarios | Managed-storage conclusion |
| Persistence, transient cleanup, package permissions | Relaunch and privacy scenarios | Offline-first introduction |
