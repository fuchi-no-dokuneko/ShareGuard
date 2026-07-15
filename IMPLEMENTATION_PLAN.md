# ShareGuard / Canonical Share implementation plan

Status: approved pre-code plan  
Authority date: 2026-07-15  
Product authority, in precedence order:

1. `canonical_share_android_implementation_spec_v1.amendment2.txt`
2. `canonical_share_android_implementation_spec_v1.ammend.txt`
3. `canonical_share_android_implementation_spec_v1.txt`

This plan was written before application or build code. The three specification files are the sole
authority for product behavior. External documentation is used only to select current, maintained
Android APIs and dependency coordinates.

## 1. Effective product contract

- Build a Kotlin and Jetpack Compose Android application. Java interoperability is allowed only through
  explicit typed boundaries that retain null-safety, immutable contexts, cancellation, and verification.
- Accept exactly one source per session: plain text or one image. Copy accepted provider content into a
  bounded, app-private immutable snapshot before processing. Never trust a MIME claim, URI, filename,
  metadata field, OCR result, URL, source byte, or source pixel.
- Execute one visible typed sequence. There are no branches, loops, arbitrary ports, hidden paths,
  scripts, or production plug-ins. Mandatory blocks are pinned and content changes invalidate every
  dependent downstream result.
- Produce canonical text, a fresh rebuilt image, both from one canonical revision, or an explicitly
  experimental derivative. Every app transformation creates a change-ledger entry and exposes possible
  semantic impact for review.
- Run all OCR, barcode, Unicode, URL, image, and verification work locally. The default application has
  no `INTERNET` permission, no analytics, no account, no cloud backend, and no runtime model download.
- Verify the exact final text representation or encoded image bytes. Assurance is computed from evidence
  and can only be maintained or lowered. The UI never says safe, anonymous, clean, or watermark removed.
- After mandatory verification, reopen and digest-check the final representation, then transactionally
  commit an encrypted Saved Result in app-private storage. The previous Explicit Save flow means export
  of a user-selected external copy; it is not a second internal save.
- Keep source material, OCR views, detailed before-values, render intermediates, reports, and share cache
  transient and separated from Saved Results. Disable Android cloud backup and device-transfer extraction
  for all managed content by default.
- Elapsed Since Import is an approximate, non-negative reference based on ordinary wall and monotonic
  clocks. It is never artifact content, never blocks sharing by itself, and never claims timing protection.
- Verification applies only to exact Managed Artifact content controlled by the app. Shared/exported
  External Copies are not monitored and require re-import for a new assessment.
- “Delete permanently” means idempotent logical deletion of all app-addressable records, artifacts,
  previews, indexes, summaries, timing data, share derivatives, pending references, and per-result keys.
  The app makes no physical flash-sanitization claim.

## 2. Documented implementation decisions

These decisions fill implementation gaps without weakening a mandatory control:

- Application ID: `app.shareguard.canonical`; product label: `Canonical Share`; repository name:
  `ShareGuard`. Minimum SDK is 23 because current bundled ML Kit and Room require it. Compile/target SDK
  is 36, matching the installed SDK. AGP 9.3.0, Gradle 9.5.1, JDK toolchain 17, and stable pinned
  dependencies will be used.
- Use AGP built-in Kotlin for Android modules and the Kotlin JVM plugin only for pure JVM modules.
  Compose uses the Kotlin-matched Compose compiler plugin and a pinned Compose BOM.
- Use manually wired dependency injection. This keeps object ownership and privacy boundaries visible and
  avoids another code-generation/runtime dependency.
- Use AndroidX Room for transactional metadata, app-private internal files for artifacts, Android Keystore
  AES-GCM per Saved Result, and SHA-256 content digests. A record is query-visible only in `COMMITTED`
  state after file sync, final-location reopen, digest/revision verification, metadata commit, and a final
  visibility transition. Interrupted records are quarantined by the integrity sweep.
- Store full change-ledger before/after values only in the transient session. Persist only content-free
  counts, categories, decision linkage, verifier status, and concise assurance rationale.
- Reject animated image inputs in v1 with a clear message. This avoids an arbitrary frame-selection rule.
- Unknown non-text regions never default to source-pixel retention. The default full-rebuild policy is an
  explicit placeholder/removal review. Retention requires a per-region user decision and limits assurance.
- Package built-in presets as versioned application resources and validate their canonical serialization
  against build-time SHA-256 values. Community presets may be imported as untrusted configuration but
  cannot enter a high-assurance path or execute code.
- There is no unverified emergency export after a fatal mandatory-verification failure. Recoverable
  transient verified output may be shared only when its exact representation is still valid and the
  persistence step alone failed.
- BOTH stores text and image from one canonical revision. Managed Share lets the user select text, image,
  or a standards-compliant combination; if target compatibility is not safely knowable, separate shares
  are offered rather than silently dropping or changing an artifact.
- Use the system Photo Picker, Storage Access Framework `CreateDocument` for external export, Android
  Sharesheet, and a narrow read-only content provider/FileProvider share cache with temporary read grants.
  Cache expiration is documented, conservative, best effort, and never described as receiver completion.
- Optional bounded import/share jitter is off by default, generated fresh with `SecureRandom`, executed on
  a cancellable background coroutine, and not persisted. Its operational bounds are a UX constant, not a
  security threshold or claim, and are covered by cancellation/no-leak tests.
- Rebuilt image layout is a generic bundled document/card/message renderer. It never traces source UI.
  Platform shaping uncertainty is disclosed; every output scalar must pass bundled-font glyph coverage or
  image export stops for review instead of falling back to an OEM font.
- Resource limits are derived at runtime from decoded headers, available app heap/storage, checked integer
  arithmetic, and platform decoder behavior. Fixed detector/OCR-confidence thresholds remain disabled
  until a checked-in corpus benchmark supports them.

## 3. Project structure and boundaries

- `:app` — manifest, application container, navigation host, lifecycle wiring, app startup/sweeps.
- `:core:model` — immutable execution, canonical document, finding, decision, ledger, verification,
  Saved Result, artifact-manifest, timer, and dependency-lineage models.
- `:core:pipeline` — block contracts/registry, typed sequence validation, presets, executor, cancellation,
  invalidation, assurance ceiling/classifier, content-free trace sink.
- `:core:session` — ephemeral workspace, source snapshot/seal, import clock anchor, stale-session purge.
- `:core:security` — digests, Keystore crypto boundary, root/path checks, no-network and logging guards.
- `:core:storage` — Room database/migrations, encrypted artifact repository, transactional commit,
  integrity sweep, logical deletion, preview/share-cache repositories, cleanup work.
- `:core:ui` — accessible theme, common status/header/timer/review/assurance components and copy rules.
- `:block:text` — Unicode inspection, ICU spoof/script checks, protected spans, reviewed normalization.
- `:block:url` — maintained parsing/public-suffix adapter, inventories, policy, stable serializer.
- `:block:image` — bounded probe/decode/orientation/metadata/regions/dependency map.
- `:block:ocr` — bundled local ML Kit OCR views/adapters/consensus/layout and bundled barcode scanning.
- `:block:render` — fresh canvas, bundled fonts, generic layouts, redaction/placeholders, derivative path,
  fresh serialization and strict metadata allowlist.
- `:block:verify` — manifest/revision/container/Unicode/URL/OCR/source-reference/dependency/barcode/
  idempotence checks, assurance computation, human-readable report.
- `:feature:entry`, `:feature:workflow`, `:feature:review`, `:feature:output`, `:feature:saved` — Compose
  screens and immutable UI state; no file parsing, bitmap mutation, or assurance calculation in UI.
- `:test-corpus` — licensed/generated adversarial fixtures and convergence expectations.
- `:benchmark` — corpus-backed resource/OCR/diagnostic measurements; results never silently become claims.

Dependencies flow inward through interfaces. Core model and pipeline have no Android UI or storage access.
Infrastructure modules receive restricted handles instead of raw cross-module paths.

## 4. Incremental work packages

Each package ends with focused tests and a Git commit. A later package may not make an earlier security
boundary optional.

### Increment 0 — repository, claims, and reproducible build

- Initialize Git only in this sub-repository and configure the authenticated GitHub noreply identity.
- Add Gradle wrapper/version catalog, modular build, Android Lint, Kotlin/static checks, dependency locking,
  release shrinking, deterministic archive settings, license/SBOM reporting, and debug/release variants.
- Add privacy/threat/claim/dependency records and a release checklist.
- Prove the merged release manifest has no Internet, broad storage, backup, or analytics permissions.

### Increment 1 — immutable models and sequence engine

- Implement all normative IDs as stable block descriptors with full contract metadata.
- Implement immutable `ExecutionContext`, findings, decisions, canonical blocks, URL/image regions,
  append-only ledger, dependency map, verification results/statuses, output bundle, Saved Result models.
- Implement sequence validation, exact preset manifests, type/order constraints, review gates, invalidation,
  structured cancellation, trace events, and deterministic assurance rules AS-0 through AS-4.
- Add schema round-trip, contradiction, mutation, cancellation, mandatory-block, invalidation, trace privacy,
  and failing-verifier tests.

### Increment 2 — secure input, session lifecycle, and reference timer

- Implement direct/plain/shared text and single-image shared/Photo Picker import.
- Add bounded copy, signature/header checks, dynamic resource plan, immutable seal, app-private roots, and
  optional cancellable provider-snapshot delay. Reject malformed, polyglot, unsupported, animated, or
  multi-image input without broad permissions.
- Create one immutable wall/monotonic Import Anchor only after safe acceptance. Implement process/reboot/
  time-zone/rollback confidence behavior and accessible rate-limited display formatting.
- Add stale/normal/crash cleanup and canary tests.

### Increment 3 — canonical text and URL milestone

- Implement TXT-001..017, URL-001..015, CAN-001..003, REV-001/002/004/007/008, text serializers,
  final Unicode/URL/idempotence/reference/manifest verifiers, report, preview, and text Sharesheet flow.
- Use ICU4J SpoofChecker/normalization and OkHttp HttpUrl/public-suffix behavior; never use regex as the
  final parser or blanket script conversion.
- Build Entry, Workflow, Block Detail, Character/URL/Semantic Review, Output, Report, and About/Threat UI.
- Complete all required TC-TXT and TC-URL fixtures, convergence families, canary and mutation tests.

### Increment 4 — persistent verified results

- Implement PST-001..007, Room schema/migrations, encrypted artifacts, durable commit, safe previews,
  revalidation, search/sort/filter/list-grid, favourite/rename, storage usage, single/bulk/delete-all, and
  deletion-pending quarantine.
- Add Saved Results, Saved Result Detail, deletion, settings, final saved confirmation, and timer surfaces.
- Implement managed share, optional pre-share jitter, external-copy disclaimer, explicit SAF export, and
  best-effort share-cache sweeps.
- Add transaction interruption, process recreation, reboot-model, clock-change, migration, key loss,
  corruption, orphan, preview-substitution, deletion, scoped-URI, Sharesheet, and accessibility tests.

### Increment 5 — image, local OCR, layout, and rebuilt output

- Implement IMG-001..018 with platform decode/ExifInterface/maintained metadata parsing and source
  dependency tracking. Diagnostic LSB/frequency blocks report only `not detected`/comparative evidence and
  cannot promote assurance.
- Bundle ML Kit OCR and barcode models; generate temporary OCR views; build conservative consensus,
  geometry, reading order, QR/URL routing, non-text region review, and Canonical Document revisions.
- Bundle licensed Noto fonts, enforce glyph coverage, allocate a fresh nonaliased canvas, render generic
  layouts/structured placeholders/redactions, flatten alpha, encode fresh output, and apply the allowlist.
- Reopen exact bytes and run metadata, Unicode, URL, OCR round-trip, barcode, region, lineage, and source
  reference audits before computing AS-3/AS-4.
- Add all required TC-IMG cases that can be generated/licensed, malformed containers, convergence,
  deterministic semantics, no-source-buffer, no-font-fallback, and verifier-failure fixtures.

### Increment 6 — derivative mode, release hardening, and publication

- Implement DER-001..006 only after canonical paths pass: fresh resample/channel handling, optional
  benchmark-backed quantization, optional ephemeral perturbation, fresh encode, mandatory warning, AS-1
  ceiling, and explicit limitations.
- Run unit, lint, release, shrinker, dependency/license, privacy-canary, migration, and compiled
  instrumentation suites. Build signed locally testable debug and unsigned/repository-signed release
  artifacts without committing secrets.
- Add GitHub CI for JVM/unit/lint/debug/release and managed-emulator instrumentation suites, including
  lifecycle, migration, clock/reboot model, sharing, URI permission, corruption, and deletion shards.
- Create a public GitHub repository, push bounded commits, verify all Actions, tag `v1.0.0`, publish APK/AAB
  assets from a clean tag build, and verify repository visibility, workflow status, release URL, and assets.

## 5. Verification matrix

Completion requires evidence for every category, not merely a successful assembly:

- **Structural:** exact preset manifests, every mandatory block/version, no branch/cycle, current revision,
  post-transform invalidation, one canonical revision for BOTH.
- **Content:** final Unicode and URL reparse, approved decision linkage, OCR round-trip, barcode re-scan,
  reading order and complete change-ledger diff coverage.
- **Container:** independent reopen/decode, strict metadata/chunk/profile/thumbnail/channel checks.
- **Provenance:** no source URI/name/provider/path, complete defined source dependency lineage, no stable
  seed, explicit platform dependency limitations.
- **Privacy:** no Internet/broad-storage/backup permission, no sensitive logs/traces/analytics, encrypted
  app-private persistence, transient purge, logical deletion, scoped sharing, no timer leakage.
- **Lifecycle:** transaction/process interruption, app recreation, reboot-model restoration, wall-clock/
  zone changes, schema migration, corruption/key loss/orphans, cleanup retry/quarantine.
- **UX/accessibility:** detected/changed/verified distinction, timing/assurance limitations, semantic review,
  source-region visibility, system Sharesheet, large text/landscape/TalkBack/non-color status.
- **Release:** lint/static checks, unit and emulator tests, release shrink/build, dependency and license
  records, SBOM, public CI, tagged GitHub Release artifacts.

## 6. Environment and execution constraints

The current host has JDK 21.0.11, Gradle 9.5.1, Android SDK/API 36 and build-tools 36.0.0. No environment
change is required. There is no system image/AVD, usable KVM permission, or display, and `adb` will not be
invoked on this VM. Local work will compile all instrumentation APKs and run JVM/Robolectric-equivalent
coverage; device-backed instrumentation will run on managed Android emulators in GitHub Actions. A test
that is only compiled is not reported as executed until its CI run succeeds.

## 7. Completion and limitation policy

- No mandatory verification, privacy control, test category, or product screen is silently deferred.
- Unknown platform behavior, detector limitations, unsupported scripts/regions, external-copy state,
  provider stability, receiver completion, physical deletion, and clock accuracy remain explicit limits.
- If a true product choice cannot be derived after three evidence-based attempts, implementation stops and
  requests the owner’s decision. Tooling failures are retried and investigated; they do not justify a
  weaker result.
- Final reporting lists changed files, tests actually executed, CI/release evidence, unresolved limitations,
  and any requirement that remains blocked by Android or test-environment constraints.
