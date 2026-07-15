# Release checklist

Status: mandatory release gate
Authority: baseline section 20 plus Amendments 1 and 2

## Source and build integrity

- [ ] Release commit is clean, reviewed, tagged, and reproducible from the checked-in Gradle wrapper.
- [ ] Wrapper distribution URL is public, checksum-pinned, validated, and bounded by timeout/retries.
- [ ] Dependency coordinates are pinned; dynamic versions and unreviewed repositories are absent.
- [ ] Dependency review, license inventory, optimized APK/AAB size evidence and SBOM are current.
- [ ] Release APK and AAB are built from the tag; checksums are attached to the GitHub Release.
- [ ] R8/resource shrinking and release lint complete without suppressed security/privacy findings.

## Privacy and permissions

- [ ] Merged release manifest has no Internet, network-state, broad storage/media, analytics, account,
      location, notification-content or unapproved permission.
- [ ] `allowBackup=false`, backup rules and data-extraction rules exclude all managed roots.
- [ ] No runtime model/data download is possible; OCR/barcode models and renderer assets are bundled.
- [ ] Release tracing is default-off and content-free trace canaries pass.
- [ ] Logs, crash surfaces and reports contain no source/output content, labels, URIs, paths, destinations,
      linked timing data, keys or stable cross-session identifiers.

## Pipeline and exact-output verification

- [ ] Every catalog descriptor, version, preset order and preset digest matches the effective specs.
- [ ] Mandatory blocks cannot be removed/reordered into an invalid sequence; no branch, cycle or script.
- [ ] All transforms have ledger coverage; possible semantic impact has an approved review decision.
- [ ] Post-transform invalidation, cancellation and fatal cleanup suites pass.
- [ ] Final Unicode, URL, metadata/container, OCR/barcode, source-reference, dependency, idempotence,
      revision-link and assurance-classification verifiers pass on the exact serialized representation.
- [ ] `NOT_RUN`, `FAIL`, `ERROR`, unknown lineage, stale state or a digest mismatch blocks verified share.

## Persistence and lifecycle

- [ ] Automatic save occurs only after serialization, mandatory verification, assurance, staged reopen,
      durable write, final-location reopen/digest/revision checks and visible-state commit.
- [ ] Room schema/migration fixtures cover every released schema; migrations do not silently rewrite bytes.
- [ ] Process interruption, reboot model, clock rollback/zone change, corruption, key loss, orphan,
      preview substitution and incomplete-transaction tests pass.
- [ ] Logical single/bulk/delete-all is idempotent, immediately non-shareable on partial failure, and removes
      all app-addressable artifacts, metadata, previews, indexes, timing data, keys and share derivatives.
- [ ] Stale session, share-cache and integrity sweeps are repeatable and content-free.

## UX, accessibility and claim language

- [ ] Detected, changed and verified states are visually and semantically distinct.
- [ ] Assurance never uses absolute “safe,” “anonymous,” or universal watermark-removal language.
- [ ] Managed Artifact/External Copy, timer, provider snapshot, cache cleanup, durability and deletion limits
      are visible at the applicable decision surface.
- [ ] Timer is nonnegative, advisory, UI-only, restored across process/reboot models and rate-limited for
      accessibility; no built-in security-significant threshold is invented.
- [ ] Large text, TalkBack labels, keyboard/focus, landscape, dark mode and non-color status cues pass.
- [ ] Android Sharesheet, Photo Picker/SAF and scoped URI grants pass API 23 and API 36 tests.

## Publication

- [ ] Repository visibility is explicitly verified as public.
- [ ] `main` CI, instrumentation, CodeQL and dependency review are green.
- [ ] Tag workflow is green; release page points to the tag commit.
- [ ] APK, AAB and `SHA256SUMS` assets exist and their downloaded digests match.
- [ ] Unsigned artifacts are labelled `unsigned`; signing secrets and keystores are absent from Git/history.
- [ ] Known platform/test-environment limitations are reported without presenting compiled-only tests as run.
