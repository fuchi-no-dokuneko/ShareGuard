# Requirements traceability

This matrix is a release index, not a replacement for the three controlling specifications. A row is
complete only when its implementation, focused tests and applicable Android integration tests all pass.

| Requirement family | Primary implementation | Required evidence |
|---|---|---|
| Immutable source, findings, decisions, Canonical Document, ledger, output and assurance models | `core/model` | invariant, serialization, immutable-collection, reference and contradiction tests |
| Typed sequential engine, all normative descriptors, preset order/digest, cancellation and invalidation | `core/pipeline` | catalog, mutation, order/type, review-gate, cancellation, trace-canary and classifier tests |
| TXT-001..017, CAN text helpers | `block/text` | TC-TXT-001..020, protected-span, language, idempotence and convergence tests |
| URL-001..015 | `block/url` | TC-URL-001..015, maintained parser/public-suffix, policy, reparse and convergence tests |
| IMG-001..018 | `block/image` | TC-IMG resource/container/metadata/channel/region cases and bounded-decode tests |
| Local OCR, layout, QR/barcode and consensus | `block/ocr` | bundled-model inventory, ambiguity, reading-order, barcode routing and no-network tests |
| REN-001..011, OUT-IMG and DER-001..006 | `block/render` | fresh-canvas/nonalias, bundled-font coverage, no fallback, allowlist, derivative ceiling tests |
| REV/VER final layers and assurance report | `block/verify` | failing verifier, exact-byte reopen, source-reference, lineage, OCR/barcode and report tests |
| Provider snapshot, Import Anchor, timer and transient cleanup | `core/session`, `core/security`, `ShareGuardViewModel` | fake-clock model tests, process-abandonment purge, new-activity restoration, and the two-phase managed-emulator reboot probe |
| PST-001..007, encrypted durable storage and logical deletion | `core/storage` | Room migration, every repository interruption checkpoint, process-kill at metadata commit, reopen/digest, Android Keystore loss, corruption, orphan, ENOSPC and bulk-deletion tests |
| UX-SCR-001..023 and settings | `feature/*`, `core/ui`, `app` | state/reducer, Compose semantics, large-text/landscape and API 23/36 instrumentation tests |
| Default offline/backup/log/trace posture | manifest, CI scripts, threat/dependency records | merged-manifest/APK checks, backup checks, lint, CodeQL, dependency review and canaries |
| Android sharing boundary | `feature/output`, production `FileProvider`, test-APK receiver | Intent-field unit tests, system chooser monitor, distinct-package exact-byte read, no-grant denial, write denial, and path-scope rejection |
| Public release | `.github/workflows`, `scripts/ci` | green tag run, public visibility, tag commit, APK/AAB/CycloneDX SBOM/checksum asset verification |

The test corpus manifest carries stable fixture identifiers and expected semantics. Tests must select
fixtures by ID so a passing count cannot hide a missing required case.
