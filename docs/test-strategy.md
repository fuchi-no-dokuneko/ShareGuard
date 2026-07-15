# Canonical Share test strategy

## 1. Purpose and authority

This document defines how Canonical Share is tested and what evidence is required before a test may be described as executed or passed. It covers all three product authorities. Precedence from highest to lowest is Amendment 2, Amendment 1, then the baseline specification.

The strategy is deliberately stricter than “the test task was green.” Canonical Share makes constrained claims about exact managed artifacts, local processing, transactional storage, logical deletion, and post-output verification. A test result is useful only when it identifies the exact code revision, environment, fixture, command, and observable result that support the claim.

The following specification boundaries apply to all tests:

- Deletion means logical deletion of every app-addressable record, file, index, preview, key, cache, and recovery reference. Tests must not claim physical flash sanitization.
- “Time since import” is a non-negative, best-effort reference based on ordinary system clocks. Tests must not claim exact real elapsed time after arbitrary clock changes.
- Assurance applies only to the exact Managed Artifact controlled by the app. An External Copy is outside that boundary until re-imported and reprocessed.
- Share completion or chooser callbacks are not proof that a receiving app finished reading.
- The dependency map is complete for dependencies introduced or declared by exercised pipeline blocks. It is not proof that all hidden platform dependencies are known.
- Detector misses mean “not detected,” never “absent” or “clean.”
- Runtime offline behavior is distinct from build-time dependency resolution.
- No product threshold is accepted without a versioned corpus and a recorded benchmark.

## 2. Audited execution environment

The initial local environment audit found:

| Capability | Audited state | Consequence |
| --- | --- | --- |
| Host | Ubuntu 24.04.4 LTS, x86_64, 6 vCPU, 15 GiB RAM | Suitable for Gradle, JVM tests, static analysis, corpus tooling, and host-side artifact inspection. |
| JDK | OpenJDK 21.0.11 at `/usr/lib/jvm/java-21-openjdk-amd64` | Local and CI builds must still use the JDK version pinned by the project/toolchain configuration. |
| Gradle | Global Gradle 9.5.1 at `/home/vmadmin/install/opt/gradle-9.5.1` | The project wrapper, not this global installation, is authoritative for execution evidence. |
| Kotlin | No standalone `kotlinc` or `kotlin`; Gradle embeds Kotlin 2.3.20 | Application Kotlin must be compiled through the pinned Gradle/Kotlin plugin. A standalone compiler is not required. |
| Android SDK | `/home/vmadmin/install/opt/android-sdk`; platform 36 revision 2, extension 17; build-tools 36.0.0; command-line tools 20.0; platform-tools 37.0.0 | Local Android compilation for `compileSdk 36` is available. Other exact SDK platforms are not installed. |
| Emulator | Emulator 36.6.11 is installed, but there is no system image and no AVD | No local emulator test may currently be claimed. |
| Acceleration/display | `/dev/kvm` exists but the current user cannot read or write it; `DISPLAY` and `WAYLAND_DISPLAY` are unset | Local `connectedCheck`, reboot, clock, process-death, cross-app, and other device-backed claims are blocked. |
| Android Studio/NDK | Android Studio and SDK NDK are absent | Use CLI builds. Native-from-source tests require a separately approved and pinned NDK installation. |
| Network | GitHub, Google Maven, Maven Central, Gradle Plugin Portal, Gradle services, and Android SDK metadata were reachable with valid TLS | Online build dependency resolution is available; this does not prove runtime offline behavior or a complete offline Gradle cache. |
| GitHub | GitHub CLI authentication and API reads work over HTTPS | This does not prove that a future repository has Actions enabled, that workflow permissions are sufficient, or that secrets/signing material exist. |

Until the local emulator constraints change and a new audit is recorded, local execution is limited to host/JVM lanes. Device-backed execution belongs on a managed GitHub-hosted Android emulator or an explicitly recorded physical-device run.

## 3. Test claim vocabulary and evidence gate

Use only these states in reports:

| State | Meaning |
| --- | --- |
| `PLANNED` | Requirement is mapped, but executable test code does not yet exist. |
| `IMPLEMENTED_NOT_RUN` | Test code exists and was reviewed, but there is no qualifying execution evidence for the stated revision/environment. |
| `EXECUTED_PASS` | Qualifying evidence proves the exact test ran and met every asserted condition. |
| `EXECUTED_FAIL` | Qualifying evidence proves the exact test ran and at least one condition failed. |
| `EXECUTED_ERROR` | The test started but the harness or environment could not establish a result. This is not a pass. |
| `SKIPPED` | The harness recorded a skip and a concrete reason. A required skipped test blocks the associated gate. |
| `NOT_APPLICABLE` | A traceable requirement-to-configuration rule proves the test does not apply. |

“Tests added,” “workflow created,” “compiled,” “runner was green,” and “no failure was observed” are not execution claims.

Before a test is claimed `EXECUTED_PASS`, retain all of the following:

1. Repository commit SHA and a clean/dirty-worktree statement. If dirty, retain the patch digest and patch artifact.
2. Exact command and Gradle task path, complete exit code, start/end time, and whether retries occurred.
3. Project Gradle wrapper version and distribution checksum; JDK vendor/version; AGP, Kotlin, Compose, SDK platform, build-tools, and relevant dependency-lock versions.
4. For Android tests: runner image, emulator version, API level, ABI, device profile, locale, time zone, font scale, orientation, animation policy, boot mode, and whether app/device data was wiped.
5. Test binary identity: APK/test-APK hashes or JVM test JAR/classpath lock digest.
6. Exact test IDs and machine-readable JUnit/XML result files showing run, pass, fail, error, and skip counts.
7. Fixture or corpus version, provenance manifest, content digest, expected-result revision, and deterministic seed where a seed is relevant.
8. Required domain artifacts: final artifact digests, parsed inventories, change-ledger checks, trace-event schema checks, storage inventories, packet-capture summary, accessibility report, screenshots, or log-canary search results as applicable.
9. Untruncated logs sufficient to diagnose failure, while enforcing the content-free logging policy. Sensitive fixtures must never be used merely to improve diagnostics.
10. A requirements mapping showing which acceptance criteria the test proves and which it does not prove.

CI must upload evidence with an always-run artifact step even when an earlier step fails. A rerun is a separate execution: retain the first failure and label the rerun. Flaky-pass reporting is prohibited.

## 4. Execution lanes

| Lane | Runs in current local environment | Runs on managed GitHub emulator | Purpose |
| --- | --- | --- | --- |
| Static/host (`H`) | Yes | Yes, before emulator boot | Formatting, lint, dependency policy, manifest/backup/network-permission inspection, SBOM/licence checks, source scans, artifact parsing, reproducibility tooling. |
| JVM unit/property (`J`) | Yes | Yes | Models, pipeline rules, serializers, clock logic with fakes, transaction state machines, assurance rules, corpus transforms, parser adapters, mutation tests. |
| Robolectric where justified (`R`) | Yes | Optional | Narrow Android-framework behavior that is deterministic without a real system service. It never substitutes for required instrumentation. |
| Managed instrumentation (`I`) | No, currently blocked | Yes | Compose UI, ContentResolver, FileProvider, Room/SQLite, Work/lifecycle behavior, Keystore, Sharesheet intent flow, accessibility semantics, app-private storage. |
| Destructive managed instrumentation (`D`) | No, currently blocked | Yes, isolated serial emulator | Process death, reboot, clock/time-zone mutation, low storage, interrupted transactions/deletion/migration, package-data and cache lifecycle. |
| Cross-app managed instrumentation (`X`) | No, currently blocked | Yes, with a purpose-built receiver APK | Scoped URI grants, receiver reads, MIME behavior, byte equality, best-effort cleanup boundaries. |
| Corpus/benchmark (`C`) | Yes for JVM/host portions | Yes for Android/OCR/render portions | Adversarial corpus, convergence, OCR disagreement, renderer determinism, resource and detector calibration. |
| Physical-device supplemental (`P`) | Only when separately attached and recorded | No | OEM codecs/fonts, Photo Picker/provider variation, real TalkBack/usability, storage/Keystore behavior, performance. This supplements rather than erases emulator evidence. |

The managed emulator is authoritative only for the Android behavior it actually exercises. Host unit results must not be relabelled as device results, and emulator results must not be relabelled as physical-device coverage.

## 5. Core test matrix

### 5.1 JVM unit, property, integration, and mutation matrix

| ID/family | Required coverage | Primary lane | Required evidence beyond JUnit |
| --- | --- | --- | --- |
| `UT-MODEL` | Immutability of `ExecutionContext`, `SavedResult`, `ArtifactManifest`, findings, decisions, and append-only ledger; schema round-trip; invalid/cyclic references; canonical revision lineage; BOTH artifact consistency. | J | Serialized fixture/round-trip digest and negative-fixture results. |
| `UT-PIPE` | Exactly one source/final preparation, no branch/cycle, type predicates, mandatory blocks, valid ordering, version recognition, preset migration, assurance ceiling propagation, no post-verification transform, fatal failure blocking. | J | Expected/actual block manifest and deliberately mutated presets. |
| `UT-INVALIDATE` | Source, language, URL, review-decision, renderer, and filename changes invalidate exactly the required downstream state. Timer-only UI updates do not invalidate artifacts. | J | Before/after revision graph. |
| `UT-CANCEL` | Cancellation stops new work, cancels bounded delay, marks partial artifacts invalid, calls cleanup, releases handles, and never exports partial verified output. | J/I | Scheduler trace with content-free reason codes and temporary-artifact inventory. |
| `UT-BLOCK-*` | Every block has positive, negative, failure, cleanup, invalidation, change-ledger, and verifier tests. Every verifier has at least one deliberately failing fixture. | J/R/I/C according to block | Block ID/version mapping and pass/fail fixture pair. |
| `UT-TEXT` | Scalar inventory, grapheme boundaries, malformed encodings, normalization deltas/idempotence, approved ignorables, whitespace/line endings, punctuation, confusables, genuine multilingual content, protected code/maths/identifiers, wrap/paragraph decisions. | J/C | Exact code-point diffs, exception-set revision, Unicode library/data version. |
| `UT-URL` | Extraction, maintained parser stability, IDN/confusable host, public-suffix boundary, query/path/subdomain/fragment/userinfo inventories, percent encoding, strict/origin-only transforms, functionality review, stable serializer/reparse. No network resolution. | J/C | Parsed component manifest and decision-ledger comparison. |
| `UT-IMAGE-PARSE` | Bounded header probes, MIME/signature conflicts, dimensions/resource plans, orientation transform, metadata/chunk/thumbnail inventory, channel/alpha/color model, malformed containers. | J/R/C | Independent parser output for final bytes; allocation/resource assertions. |
| `UT-OCR-LAYOUT` | OCR consensus alignment, disagreement preservation, script conflicts, text geometry, reading order, QR/barcode routing, unknown-region handling, source dependency model. | J/C/I | Approved consensus/reading-order fixture and disagreement report. |
| `UT-CANONICAL` | Semantic build, independent schema validation, serialize/deserialize equality, diff coverage, every app-applied transformation represented once in the change ledger, semantic-impact flag reviewable. | J | Revision diff-to-ledger bijection report. |
| `UT-RENDER` | Fresh buffer ownership, bundled-font coverage, no silent device-font fallback, deterministic semantic/layout invariants, opaque redaction, alpha flatten, canonical color, metadata allowlist, declared retained-region lineage. | J/C/I | Font manifest; operation/dependency log; exact final-byte parser inventory. |
| `UT-VERIFY` | Manifest/revision/metadata/Unicode/URL/OCR/source-reference/dependency/barcode/region/idempotence checks; `PASS`, residual, review, N/A, not-run, fail, error semantics. | J/C/I | Deliberately failing fixture for each verifier and independent final-artifact inspection. |
| `UT-ASSURANCE` | Every AS-0–AS-4 rule, contradictory/unknown state downgrade, derivative ceiling, retained-pixel ceiling, revalidation never promotes, user/display metadata cannot promote. | J | Exhaustive decision table with branch coverage. |
| `UT-ANCHOR` | Import Anchor created exactly once only after accepted/sealed input; immutable across session/result; never inferred from source metadata. | J/I | Fake-clock event timeline and persisted-field comparison. |
| `UT-CLOCK` | Same-boot monotonic calculation, restored wall-clock calculation, backward clamp, forward change, time-zone/DST presentation, confidence transitions, no share block solely for reduced confidence, timer excluded from artifact/name/share data. | J/I/D | Injected clock sequence and expected duration/confidence table. |
| `UT-PERSIST` | Operational durable-write state machine: write, flush/sync, metadata commit, reopen, digest/revision match, then visibility; every interruption point; transient retry on failure. | J/I/D | State-transition trace and storage/DB inventory at each fault point. |
| `UT-MIGRATE` | SavedResult/database/file migrations, unknown legacy import time, artifact byte immutability, migration requiring explicit revalidation if bytes change, failed/downgrade/partial migration quarantine. | J/I/D | Pre/post schema and artifact digests plus migration fixture version. |
| `UT-DELETE` | Full logical-deletion inventory, single/bulk/idempotent retry, pending lifecycle on partial failure, per-result key deletion, saved/transient separation, external-copy disclaimer. | J/I/D | Before/after enumeration of every managed root/index/key alias without sensitive paths in user-visible output. |
| `UT-CORRUPT` | Byte, digest, MIME/container, revision/linkage, record/path, preview, key, orphan file/record, incomplete transaction, and migration corruption. | J/I/D | Mutation manifest and proof of quarantine/no verified share. |
| `UT-SHARE` | MIME/package construction, text-only representation, same-revision BOTH mode, content URI scope/flags, artifact digest, external-copy boundary, no target/time history, optional jitter bounds/cancellation. | J/I/X | Intent field manifest and shared-byte digest comparison. |
| `UT-TRACE` | Required phase/transformation coverage and prohibited trace fields; session-local IDs only; production persistence disabled; session cleanup clears temporary traces. | J/I | Trace schema validation and canary search with zero prohibited matches. |
| `UT-UI-STATE` | Saved/Verified/Shared distinctions, timing status, errors, empty/loading/quarantine states, assurance consequences, no anonymity/physical-erasure/receiver-completion claims. | J/R/I | Golden state table; screenshots are supplemental, not sole proof. |
| `UT-MUTATION` | Mutate block order/version, allowlists, parser output, canonical revision, dependency flags, Intent MIME, cleanup policy, assurance inputs, verification linkage. The appropriate validator must fail. | J/C | Mutation score and surviving-mutant review; no arbitrary target threshold without corpus evidence. |

### 5.2 Android instrumentation and end-to-end matrix

| ID/family | Scenario and assertions | Lane | Current local | Managed GitHub emulator |
| --- | --- | --- | --- | --- |
| `IT-ENTRY-TEXT` | `ACTION_SEND`, paste, and editor import; styled/HTML alternatives and unrelated extras discarded; malformed/oversized input rejected without raw logs. | I | Blocked | Required |
| `IT-ENTRY-IMAGE` | Content-URI and Photo Picker import; signature over filename/MIME; bounded app-private snapshot; provider no longer required after seal; broad media permission absent. | I/X | Blocked | Required |
| `IT-PROVIDER-RACE` | Provider changes/fails during delivery; only the completed internal snapshot becomes authoritative; optional bounded delay is off UI thread, cancellable, and followed by internal reopen/length/digest/location checks. No provider-stability claim. | D/X | Blocked | Required |
| `IT-WORKFLOW-UI` | Ordered cards only, no free graph/branch/cycle, invalid move rejected, mandatory blocks pinned, statuses and Input → Output visible, upstream changes invalidate UI state. | I | Blocked | Required |
| `IT-REVIEW` | Character, URL, OCR, layout, region, semantic-diff, and assurance-consequence gates; unresolved semantic risk blocks applicable lock/export. | I | Blocked | Required |
| `IT-FINAL-BYTES` | Serialize, reopen exact final bytes, independent scans, preview digest equals share digest, no source URI/name/path/provider or Import Anchor leakage. | I/C | Blocked | Required |
| `IT-SAVE-VISIBILITY` | Saved Result appears exactly once only after complete durable-write protocol; failed/partial records remain hidden or quarantined; app restart retains committed items. | I/D | Blocked | Required |
| `IT-SAVED-UI` | List/detail/search/sort/filter/list-grid/favorite/rename/preview toggle/storage/error states; rename/favorite do not change artifact bytes. | I | Blocked | Required |
| `IT-REVALIDATE` | Stale/unknown/migrated records revalidate before managed share; byte/digest/MIME/revision failure removes verified badge/share path and never substitutes another artifact. | I/D | Blocked | Required |
| `IT-EXPORT-COPY` | Explicit Save is implemented only as user-selected export; safe app-generated name; source filename/timer absent; reopened exported bytes equal the verified export-time artifact; later External Copy edits remain out of scope. | I/X | Blocked | Required |
| `IT-PROCESS-DEATH` | Kill at import, block apply, verification, every persistence boundary, preview creation, share-cache creation, deletion, and migration. Restart/sweep yields only committed valid results or quarantined/deletion-pending state. | D | Blocked | Required, isolated |
| `IT-REBOOT` | After committed save, reboot emulator; monotonic reference is unavailable, wall-clock restoration is non-negative and honestly classified; result and integrity state survive; no exact-time claim. | D | Blocked | Required, isolated |
| `IT-CLOCK` | Same-boot wall-clock backward/forward changes, time-zone and DST transitions, background/foreground, process restore, and reboot. Timer remains advisory, non-negative, and UI-only; reduced confidence does not automatically block share. | D | Blocked | Required, isolated |
| `IT-LOW-STORAGE` | Exhaust app/filesystem quota at artifact write, flush, DB commit, preview, and share-cache creation; no normal visible result after failed persistence; retry is safe. | D | Blocked | Required, isolated |
| `IT-KEY` | Keystore-backed key availability, invalidation/loss, ciphertext never shown as valid, per-result key removed when used for logical deletion. | D/P | Blocked | Required where emulator supports behavior; supplement physically |
| `IT-MIGRATION` | Install historical fixture, upgrade in place, interrupt migration, reopen, verify artifacts unchanged unless revalidated, integrity sweep catches orphan/mismatch, backup remains disabled. | D | Blocked | Required, isolated |
| `IT-CORRUPTION` | Mutate managed artifact/preview/record/share cache after setup; reopen/revalidate/sweep quarantines it, verified share is blocked, authoritative artifact is never replaced by preview/cache. | D | Blocked | Required, isolated |
| `IT-DELETE` | Single, bulk, delete-all, partial fault, retry, concurrent open/share attempt, session purge versus Saved Result separation; every app-addressable reference/key/cache removed and item immediately non-shareable. | D | Blocked | Required, isolated |
| `IT-SHARE-TEXT` | System Sharesheet launch with only approved `text/plain`; no hidden HTML/Import Anchor; no custom target UI or persistent target/recipient event. | I/X | Blocked | Required |
| `IT-SHARE-IMAGE` | Scoped read-only content URI, temporary read permission, no file URI/broad grant, receiver can read exact managed bytes, unauthorized paths are unreadable. | X | Blocked | Required |
| `IT-SHARE-BOTH` | Supported bundle uses artifacts from one canonical revision; unsupported receiver behavior is explained, never silently mismatched. | X | Blocked | Required |
| `IT-SHARE-CACHE` | Authoritative artifact is preferred; required temporary derivative is app-private, conservative, retryably cleaned, and never presented as proof of receiver completion. | D/X | Blocked | Required |
| `IT-EXTERNAL-COPY` | Exported/shared copy is labelled outside managed assurance; external edit is not monitored; re-entry requires fresh import/verification. | I/X | Blocked | Required |
| `IT-CLEANUP` | Startup stale-session purge, normal/cancel/crash purge, OCR/render/report memory release, output/share cache policy, retryable content-free failure; committed Saved Results remain separate. | I/D | Blocked | Required |
| `IT-TRACE` | Debug block-phase and transformation trace coverage; prohibited-field/canary scan; production persistent tracing disabled; in-memory trace cleared with session. | I/D | Blocked | Required |
| `IT-PRIVACY-SURFACES` | Backup exclusion, app-private roots, notification content absent by default, configured screenshot/recent-app-preview policy, preview privacy toggle, and no sensitive management/timing data in logs. | I/D | Blocked | Required |
| `IT-OFFLINE` | Strict text and image/OCR workflows complete with network denied; no model download; manifest has no `INTERNET`; traffic capture records zero attempted egress. | D/C | Blocked | Required release gate |
| `IT-LOG-CANARY` | Canary source text, URI, filename, metadata, URL, OCR crop, session path, label, timing and destination probes do not appear in logs, traces, reports, persistent store, Intent extras, or caches after purge unless approved visible output. | I/D/C | Blocked | Required release gate |
| `IT-BACKUP` | Backup/data-extraction rules exclude Saved Results, keys, transient sessions, and share cache by default; restore cannot silently recreate a verified result. | I/D | Blocked | Required |
| `IT-ACCESSIBILITY` | Compose semantics, focus order, natural duration labels, rate-limited timer announcements, large text, landscape, color-independent state, actions/dialogs/errors and source-region overlays. | I/P | Blocked | Automated required; manual TalkBack supplemental |
| `IT-RESOURCE` | Huge/malformed source stops before unsafe allocation; bounded thumbnail/OCR views; cancellation and cleanup under pressure; safe alternative output offered when feasible. | I/C | Blocked | Required |

### 5.3 Specification-to-suite traceability

| Specification family | Owning suites |
| --- | --- |
| Baseline pipeline rules `PV-001..020` | `UT-PIPE`, `UT-INVALIDATE`, `UT-CANCEL`, `UT-BLOCK-*`, `IT-WORKFLOW-UI`, `IT-CLEANUP` |
| Baseline verifiers `VER-001..015` and verification layers A–E | `UT-VERIFY`, `UT-ASSURANCE`, `IT-FINAL-BYTES`, `IT-REVALIDATE`, `IT-OFFLINE`, `IT-LOG-CANARY`, corpus/mutation suites |
| Baseline functional acceptance `AC-F-001..012` | `IT-ENTRY-*`, `IT-WORKFLOW-UI`, `IT-REVIEW`, `IT-FINAL-BYTES`, sharing suites, `IT-CLEANUP` |
| Baseline security acceptance `AC-S-001..012` | `IT-OFFLINE`, `IT-LOG-CANARY`, `IT-FINAL-BYTES`, `UT-ASSURANCE`, `UT-RENDER`, `UT-SHARE`, `IT-TRACE` |
| Baseline UX acceptance `AC-U-001..010` | `UT-UI-STATE`, `IT-WORKFLOW-UI`, `IT-REVIEW`, `IT-ACCESSIBILITY`, manual UX/TalkBack evidence |
| Baseline work-package tests `WP-020..180` | Core unit/instrumentation families, required baseline corpus, release/security/physical-device gates |
| Amendment 1 functional acceptance `AC-PF-001..010` | `UT-PERSIST`, `IT-SAVE-VISIBILITY`, `IT-SAVED-UI`, `IT-EXPORT-COPY`, `IT-DELETE`, managed-sharing suites |
| Amendment 1 timing acceptance `AC-PT-001..012` | `UT-ANCHOR`, `UT-CLOCK`, `IT-CLOCK`, `IT-PROCESS-DEATH`, `IT-REBOOT`, `IT-ACCESSIBILITY` |
| Amendment 1 security acceptance `AC-PS-001..012` | `IT-FINAL-BYTES`, persistence/migration/corruption/deletion suites, `IT-BACKUP`, `IT-LOG-CANARY`, `UT-ASSURANCE` |
| Amendment 1 UX acceptance `AC-PU-001..010` | `IT-SAVED-UI`, `IT-ACCESSIBILITY`, `UT-UI-STATE`, sharing and deletion UI suites |
| Amendment 1 explicit tests in A11 | Unit, migration, process-death, reboot, clock-change, corruption, deletion, sharing, low-storage, key, accessibility, and canary matrices in this document |
| Amendment 2 acceptance `AC-A2-001..015` | Language/static checks; deletion, clock, external-copy, share-cache, provider-race, trace, dependency-map, durable-write, corruption/recovery, and export-copy suites |

## 6. Specialized fault matrices

### 6.1 Migration matrix

For every shipped schema version `N`, retain a non-sensitive, provenance-recorded fixture and test `N → current`. Add fixtures before merging the migration that supersedes them.

| Mutation/transition | Required result |
| --- | --- |
| Empty install → current | Empty store opens without phantom records. |
| Prior metadata schema → current | Every committed record appears exactly once with correct revision and assurance ceiling. |
| Prior artifact manifest → current | References and digests migrate; exact artifact bytes remain identical unless an explicit byte-changing migration revalidates them. |
| Legacy missing Import Anchor | Use `UNKNOWN_LEGACY_IMPORT_TIME`; do not invent time or promote confidence. |
| Migration interrupted before/after each commit boundary | Startup sweep detects incomplete work and quarantines or retries idempotently. |
| Orphan file/record/preview | Reconcile without substituting content; content-free diagnostics only. |
| Digest/revision/MIME mismatch | Block verified sharing and expose safe recovery/delete actions. |
| Downgrade to older schema | Refuse or isolate safely; never reinterpret newer verified bytes as valid older state. |
| App update/process death after migration | Reopen and revalidate according to migration state. |
| Backup/restore attempt | Default exclusion is proven; no automatic cloud-restored verified record. |

### 6.2 Process-death and reboot matrix

Each checkpoint is a separate test with a fresh emulator/data state:

1. Source copied but not sealed.
2. Import Anchor created.
3. A block has temporary artifacts but has not committed a new context.
4. Final serialization completed but verification did not.
5. Verification completed but assurance/reopen did not.
6. Artifact file created but flush/sync did not complete.
7. File completed but metadata transaction did not commit.
8. Metadata committed but reopen/digest check did not complete.
9. Saved Result committed but preview generation did not complete.
10. Temporary share derivative exists.
11. Deletion removed only some references.
12. Migration wrote only part of its new representation.

At restart, the only valid normal Saved Result is one that completed the operational durable-write protocol. All other persistent residue is removed, retried, quarantined, or deletion-pending. No partial artifact may regain a verified badge by existence alone.

Reboot evidence must include pre-reboot persisted fields, boot identity change, post-boot timer/confidence state, record and artifact digests, and integrity sweep results. A reboot test cannot be replaced by activity recreation or process kill.

### 6.3 Clock-change matrix

| Case | Required behavior |
| --- | --- |
| Same boot, ordinary passage | Monotonic source drives a non-negative reference display. |
| Wall clock moves backward | Display never becomes negative; confidence becomes neutral/reduced as designed. |
| Wall clock moves forward | Best-effort value updates without claiming trusted elapsed real time. |
| Time-zone change | Instant/elapsed model does not reset; localized wall-clock presentation changes correctly. |
| DST transition | No negative duration, duplicate timer reset, or unsupported exactness claim. |
| Background/process recreation | Display resumes without restarting pipeline or rewriting artifact. |
| Device reboot | Restore from wall-clock state, mark confidence honestly, continue advisory display. |
| Waiting target configured | Target changes UI only; early share remains possible; target is absent from artifact, filename, metadata, QR, and outgoing payload. |
| TalkBack active | Focus/meaningful change announces a natural duration; per-second announcements do not occur. |

### 6.4 Corruption and integrity-sweep matrix

Mutate one dimension at a time, then combine adversarially:

- managed artifact bytes, length, digest, MIME, container, metadata allowlist, canonical revision, or verification linkage;
- database row reference, lifecycle/migration state, path outside approved root, duplicate ID, or missing artifact;
- preview bytes/reference, including replacing a preview while preserving authoritative metadata;
- share-cache derivative and stale permission reference;
- encryption key alias, unavailable key, or ciphertext/plaintext mismatch;
- orphan file, orphan record, incomplete transaction, reopen failure, or migration mismatch.

Every case must prove quarantine or safe deletion, no verified share path, no artifact substitution, no assurance promotion, and content-free diagnostics. Re-running the sweep must be idempotent.

### 6.5 Logical-deletion matrix

For single, bulk, and delete-all flows, enumerate before and after:

- Saved Result metadata and lifecycle records;
- managed text/image/derivative artifacts;
- previews and search/index entries;
- verification summaries, labels, favorite state, timer data, and waiting-target association;
- associated share-cache derivatives;
- per-result encryption keys when implemented;
- pending migration/recovery references.

Inject a failure before and after each deletion class. The item must immediately become non-shareable and enter deletion-pending state; retry must be idempotent. Verify that deleting a transient session preserves its committed Saved Result, deleting a Saved Result does not require its original session, and an External Copy remains outside app control. UI and test reports must say logical removal, never physical sanitization.

### 6.6 Sharing matrix

| Dimension | Required cases |
| --- | --- |
| Source | Newly verified-and-saved result; later Managed Share; stale result requiring revalidation; invalidated result blocked. |
| Output | Canonical text, rebuilt image, derivative image, BOTH with matching revision. |
| Intent | Exact MIME, approved extras only, system Sharesheet, no custom target list, no hidden HTML. |
| URI | FileProvider/content URI, narrow authority/path, read-only temporary grant, no broad directory/file URI. |
| Receiver | Purpose-built success receiver, delayed reader, failing reader, unsupported MIME, unauthorized path request. |
| Bytes | Receiver-read digest equals the managed/export-time artifact; preview/cache cannot replace authority. |
| Privacy | No persistent destination, recipient, exact share-launch/completion history, or waiting-target value. |
| Boundary | Chooser callback is transient UI only; no claim of receiver consumption; External Copy warning displayed. |
| Cache | Prefer authoritative managed artifact; if derivative required, app-private, conservative expiry, retryable best-effort sweep. |
| Jitter | Disabled/enabled/cancelled; bounded, locally random, off UI thread, no persisted exact delay, no anonymity claim. |

## 7. Offline and privacy verification

The offline release gate has three independent layers:

1. **Static:** merged release manifest contains no `android.permission.INTERNET`; dependency and manifest scans find no unapproved networking/analytics component; bundled OCR/model inventory is recorded.
2. **Enforced runtime denial:** the emulator is configured so app egress cannot succeed. Airplane mode alone is insufficient when host/emulator paths remain available; record the actual host/emulator deny rules.
3. **Traffic observation:** capture traffic for the complete strict text workflow and every strict image/OCR script-model workflow. Record interface, capture window, packet totals, and filtered app/DNS/TCP/UDP results. Any attempted egress fails release acceptance even if the attempt could not connect.

Use only synthetic, licensed, non-sensitive fixtures during traffic capture. Confirm that no model is downloaded on demand and that missing bundled models cause the specified safe failure. Build-time Gradle downloads are outside the runtime window and must be reported separately rather than hidden.

Privacy canary tests inject unique values into source text, filename, URI, provider authority, metadata, URL/query, hidden Unicode, OCR crop, session path, display label, Import Anchor association, waiting target, and mock destination. Search logs, traces, reports, database/index, artifacts, previews, Intent payloads, share cache, crash output, and post-purge storage. A zero-match report must identify every searched surface and exact canary set.

## 8. Accessibility and UX matrix

Automated managed-emulator coverage must include:

- TalkBack/Compose semantics for input type, output type, stage, block status, findings, review actions, assurance, storage state, and verification state;
- logical traversal and focus restoration across workflow, review, final output, Saved Results, detail, deletion, errors, and pre-share UI;
- natural localized “Time since import” labels and proof that visible per-second changes do not emit disruptive accessibility announcements;
- color-independent status, contrast checks, role/state/value exposure, touch target checks, and no icon-only destructive control without a label;
- font scales through the supported large-text range, landscape, narrow width, light/dark themes, list/grid modes, long localized strings, and right-to-left layout where supported;
- safe preview disabled, generic icon fallback, source-pixel overlay, empty/loading/quarantine/storage-error/partial-deletion states;
- Share reachable without technical details and deletion wording distinguishing in-app logical deletion from External Copies;
- no UI wording that claims anonymity, exact post-clock-change time, physical sanitization, provider stability, receiver completion, detector-certified cleanliness, or continued integrity of External Copies.

Automated semantics checks do not prove real screen-reader usability. Before release, retain a manual TalkBack task report on at least one physical device for the primary text workflow, primary image workflow, Saved Results, share, deletion, and error recovery. UX comprehension studies for assurance labels, timing language, source-pixel retention, and external-copy boundaries remain a separate acceptance input.

## 9. Adversarial corpus and benchmark matrix

### 9.1 Required baseline corpus

Every case below must have a stable ID, source licence/provenance, intended visible meaning, expected canonical result or decision, expected assurance ceiling/reviews, prohibited residue, and reference renderer/data versions.

| Family | Mandatory cases |
| --- | --- |
| Text `TC-TXT-001..020` | Zero-width insertion; bidi controls; soft hyphen/word joiner; Unicode spaces; newline variants; Cyrillic-in-Latin; Greek-in-Latin; genuine Greek; genuine Cyrillic; combining variants; full/half width; quote/dash/apostrophe; ellipsis variants; whitespace-sensitive code; minus vs dash; semantic identifier confusable; styled Android span; divergent HTML clipboard; long punctuation code; cross-sample weak markers. |
| URL `TC-URL-001..015` | Known query tracker; unknown high-entropy query; path ID; subdomain ID; fragment; deceptive userinfo; IDN confusable; multi-label suffix; percent-encoded ID; short redirect; functional invite damaged by origin-only; Markdown display/target mismatch; personalized QR URL; wrapped URL; schemeless prose domain. |
| Image `TC-IMG-001..020` | Metadata ID; embedded unredacted thumbnail; alpha-hidden content; low-bit structure; repeated spatial blocks; frequency benchmark; one-pixel font variants; OCR script conflict; chat/avatar; URL/QR screenshot; map/location; multicolumn article; dark/light variants; viewport-wrap variants; malformed chunks; huge dimensions; animation; color-profile variation; unknown region; same semantics from different devices. |

### 9.2 Persistent-result and platform corpus extensions

Maintain versioned fixture families for:

- each historical database/SavedResult/ArtifactManifest schema;
- each durable-write interruption checkpoint;
- orphan file/record, wrong-root reference, digest/revision/MIME/linkage corruption, preview substitution, missing key, and partial deletion;
- wall-clock rollback/forward, time-zone/DST transition, process restoration, reboot, and unknown legacy anchor;
- text/image/BOTH/derivative managed sharing and receiver behaviors;
- offline bundled/missing OCR models for each supported script;
- large-text, RTL, long-label, empty/error/quarantine accessibility states;
- source/log/persistence/share canary sets;
- valid and malformed Intent/provider/URI/resource-guard inputs.

### 9.3 Convergence, determinism, and mutation

For every convergence family, run multiple source variants with the same approved semantics and assert exact canonical-text equality where policy permits; compare rebuilt semantic/layout invariants; and prove source-specific URLs, code points, metadata, and undeclared source pixels are absent. User-approved intentional differences must be explicit.

Renderer repeatability is evaluated only for controlled supported inputs and declared invariants. Do not invent a universal byte-equality claim where the pinned encoder/platform does not promise it. Reproducible release artifacts are a separate build claim.

Mutation runs must cover block order/version, metadata allowlist, parser components, canonical revision, dependency flags, outgoing MIME, cleanup, assurance input, and verification linkage. A surviving mutation is triaged, not hidden by a percentage. Any numeric acceptance threshold for OCR consensus, detectors, resource budgets, image quality, or mutation score requires a reviewed corpus benchmark record.

## 10. Managed GitHub emulator matrix

The project configuration must define `MIN_SDK`, `TARGET_API`, and supported ABIs; this document does not invent them. Once defined, CI uses this minimum matrix:

| Configuration | Use |
| --- | --- |
| `MIN_SDK` / supported CI ABI | Compatibility smoke, import, core workflow, storage, sharing, and accessibility semantics. |
| API 36 / x86_64 | Primary full suite because the audited compile platform is API 36. |
| Latest project-supported API, if different from 36 | Forward-compatibility smoke and platform behavior changes. |

Each emulator job must record the exact system-image package, emulator/build-tools/platform-tools versions, AVD/device profile, RAM/disk, locale/time zone, boot mode, and KVM status. Use cold boot and wiped app/device data unless a test explicitly requires persisted upgrade state. Pin workflow actions to immutable revisions and record their licences/maintenance under the dependency policy.

Destructive suites must run serially on disposable emulators. Do not shard reboot, clock, storage-exhaustion, migration, corruption, process-death, and deletion tests onto a shared stateful AVD. Cross-app tests install a versioned receiver APK and retain both package versions/hashes.

## 11. CI matrix and gates

| Workflow/gate | Trigger | Required work | Blocks merge/release |
| --- | --- | --- | --- |
| `pr-fast` | Every pull request | Wrapper/checksum verification, clean compile, formatting/lint/static analysis, unit/property tests, manifest/backup/network permission checks, dependency locks, licence/dependency record validation. | Merge |
| `pr-device` | Every pull request once runtime exists; affected-suite sharding allowed | Managed API 36 emulator smoke, import, workflow, persistence, managed share, deletion, accessibility semantics; changed blocks include positive/negative/verifier integration tests. | Merge |
| `pr-migration` | Any schema/storage/key/lifecycle change | All historical migration fixtures plus interrupted migration and revalidation. | Merge |
| `nightly-destructive` | Scheduled and on demand | Full process-death, reboot, clock/DST/time-zone, low-storage, corruption, partial deletion, key, stale-cache, and integrity-sweep suites on isolated emulators. | Release; failures create visible triage |
| `nightly-corpus` | Scheduled and on corpus/rule/parser/OCR/render change | Full text/URL/image/persistence corpus, convergence, mutation, OCR disagreement, resource, renderer and canary suites. | Release; relevant regressions block merge when changed |
| `offline-security` | Pull request for dependency/model/manifest/network changes; nightly; release | Static no-network/analytics scan, enforced runtime denial, traffic capture, bundled-model checks, logging/trace canaries, app-private roots, backup exclusion. | Merge when affected; always release |
| `release-candidate` | Signed tag/release request | All prior gates at same SHA; clean build twice in isolated directories; artifact comparison/allowed-difference report; SBOM, licences, dependency audit, release lint, signing configuration validation, final APK/AAB hashes. | Release |
| `physical-release` | Before production release | Manual TalkBack/usability, representative OEM/provider/Photo Picker/Keystore/codec checks, performance/resource review. | Release according to documented support policy |

Every gate must use the checked-in wrapper and lock files. Dependency caches are an optimization and are never evidence that dependency resolution is reproducible. A release claim is tied to one commit SHA; results from another SHA cannot be assembled into a “green” release.

The current local GitHub CLI login does not configure Actions. Before CI is claimed operational, record repository identity, workflow permissions, branch protection/rulesets, artifact retention, concurrency/cancellation policy, Dependabot or equivalent policy if used, and signing/secret availability without exposing secret values.

## 12. Evidence artifacts by test family

| Family | Minimum retained artifacts |
| --- | --- |
| Unit/property/mutation | JUnit XML, exact Gradle task/exit code, seed, coverage where used, mutation manifest/results, fixture digests. |
| Instrumentation/UI | Android test XML, logcat filtered for harness diagnostics and scanned for canaries, screenshots/video for relevant state, APK hashes, device configuration. |
| Migration/process/reboot/clock | Pre/post DB schema summaries, record/artifact digests, lifecycle event timeline, boot/clock/time-zone state, sweep/quarantine result. |
| Corruption/deletion | Mutation or failure-injection manifest; before/after managed-root/index/key-alias inventory; verified-share availability result; idempotent retry result. |
| Sharing | Outgoing Intent manifest, URI permission checks, sender/receiver APK hashes, receiver-read artifact digest, cache inventory, external-boundary UI evidence. |
| Offline/privacy | Merged manifest, network deny configuration, packet capture and summarized zero-attempt result, bundled-model inventory, static scan, canary list and per-surface search report. |
| Accessibility | Automated accessibility/Compose semantics report, font/orientation/locale matrix, screenshots, focus/announcement trace, manual TalkBack task notes where required. |
| Corpus/benchmark | Corpus manifest/provenance/licence, case digests, expected-result revision, tool/data/model/font versions, raw measurement data, reviewed threshold decision. |
| Release/reproducibility | Wrapper and dependency locks, SBOM/licences, two isolated build logs, artifact hashes, binary diff or documented allowed differences, signing verification output. |

Evidence artifacts must not violate the app’s own privacy boundary. Use synthetic fixtures, content-free diagnostic traces, path-neutral user-visible errors, and short-lived CI artifacts with an explicit retention policy. Never upload real source material, private signing keys, access tokens, or unredacted internal paths as test evidence.

## 13. Requirement traceability and completion rule

Maintain a machine-readable traceability table alongside the test implementation with at least:

- specification/acceptance ID;
- block or feature ID;
- threat and assurance impact;
- test ID and lane;
- positive, negative, cleanup, invalidation, and verifier fixture IDs;
- required environment/API;
- most recent qualifying evidence run and result;
- residual limitation.

Every new block is incomplete until it has unit tests, an integration test in a valid sequence, a negative test, cleanup/invalidation coverage, change-ledger output, and a verifier. Every verifier is incomplete until a deliberately failing fixture proves it detects the condition. Every schema change is incomplete without migration and interruption fixtures. Every security-sensitive behavior is incomplete without an adversarial fixture.

A work package, pull request, or release may be called tested only when every mandatory mapped test is `EXECUTED_PASS` for the exact revision in a qualifying lane, or is explicitly `NOT_APPLICABLE` with a reviewed specification rule. `SKIPPED`, `IMPLEMENTED_NOT_RUN`, missing evidence, a runner error, or a narrower substitute leaves the requirement unverified and must be reported as such.
