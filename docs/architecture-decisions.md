# Canonical Share architecture decisions

Status: controlling implementation decisions
Last reviewed: 2026-07-15

## Authority and precedence

The product authority is, in descending order:

1. `canonical_share_android_implementation_spec_v1.amendment2.txt`
2. `canonical_share_android_implementation_spec_v1.ammend.txt` (Amendment 1)
3. `canonical_share_android_implementation_spec_v1.txt` (baseline)

When two requirements conflict, the higher item in that list controls. Requirements that do not conflict remain in force. In particular, Amendment 1 replaces the baseline's explicit-only internal-save model, and Amendment 2 clarifies the limits of claims that an ordinary Android application can make.

The Android application layer and Jetpack Compose UI are implemented in Kotlin. Java-compatible processing or library modules may be used behind explicit typed interfaces. Interoperability must preserve null safety, immutable execution contexts, cancellation, and verification boundaries.

## AD-001: Verified results are saved automatically

### Decision

After final serialization, mandatory final verification, assurance computation, and a successful reopen and digest check of the exact representation to be retained, the app automatically commits a `SavedResult` in app-private persistent storage.

The baseline **Explicit Save** action means **export a user-selected copy to platform storage**. It is not a second in-app save and is not required before managed sharing.

Only the verified output and the minimum metadata needed to identify, display, revalidate, share, export, migrate, and delete it are persistent. Source text and images, provider URIs, source filenames, OCR views, source crops, clipboard alternatives, temporary renders, sensitive change-ledger before-values, session paths, destinations, and removed source metadata are excluded.

A result is not visible as a normal Saved Result until the durable-commit protocol in AD-006 succeeds. If verification succeeds but persistence fails, the verified result may remain transiently available for retry or an explicitly labelled transient share; it must not be described as saved.

Clearing a transient session does not delete its committed Saved Result. Editing artifact content starts a new session and produces a new Saved Result; it never mutates the verified artifact in place. Rename, favourite, sort, and display-label changes affect management metadata only.

### Assumptions and consequences

- Persistent artifact storage, transient session storage, preview storage, and share-cache storage use separate app-private roots.
- Automatic cloud backup and synchronization of Saved Results are disabled in the default edition.
- Schema migrations do not rewrite verified artifact bytes unless the rewritten bytes go through the applicable verification path.
- A compact verification summary may persist only when it does not reproduce sensitive source values.
- Artifact references are internal opaque identifiers, never provider URIs or externally supplied paths.

## AD-002: Deletion is logical deletion, not physical sanitization

### Decision

User-facing **Delete permanently** means logical deletion of everything for the result that remains addressable by the app:

- Saved Result metadata and lifecycle records;
- managed artifact files;
- previews and search-index entries;
- verification summaries and display labels;
- timing metadata;
- associated share-cache derivatives;
- per-result encryption keys, when used; and
- pending migration or recovery references.

Deletion does not claim verified overwriting of flash cells, control of storage-controller remapping or wear levelling, or forensic sanitization of inaccessible remnants. It also cannot remove copies previously exported, shared, copied, screenshotted, backed up outside the approved product design, or edited by another app.

### Failure behavior

On a partial failure, the record immediately leaves normal Saved Results and enters a non-shareable `DELETION_PENDING`-equivalent lifecycle state. Retry is idempotent. Verified sharing stays blocked, and diagnostics expose neither content nor sensitive paths. The integrity sweep continues retrying or quarantines the record for explicit recovery.

### Assumptions and consequences

- “Immediate” deletion means immediate loss of normal app visibility and shareability plus prompt best-effort removal of app-addressable data.
- Deleting a protected per-result key is part of logical deletion but does not justify a physical-erasure claim.
- Confirmation UI distinguishes in-app deletion from external-copy retention.

## AD-003: Elapsed Since Import is an advisory reference timer

### Decision

The Import Anchor is created exactly once, after accepted content has been copied into an immutable internal representation and sealed. For provider content, this is after the authoritative snapshot checks in AD-007. Opening a screen, receiving an unaccepted URI, starting to type, or rejecting malformed input does not create an anchor.

The app stores a wall-clock anchor for restoration and human-readable display and uses a monotonic clock while it remains meaningful during the same boot. After reboot, process restoration, or loss of the monotonic reference, the app calculates a best-effort value from ordinary Android date and time and records reduced clock confidence.

Displayed duration is clamped so it cannot be negative. Manual clock changes, network-time adjustments, time-zone changes, daylight-saving transitions, reboot, and process death may reduce accuracy but do not, by themselves, block sharing.

The detail UI explains:

> Time since import is an approximate reference based on this device's system clock. It is not a security guarantee and may change when the device clock changes.

It also explains that import time is when content entered Canonical Share, not necessarily when a screenshot or original content was created.

### Security boundary

The timer is application chrome, not artifact content. It is never added to canonical text, image pixels, metadata, filenames, outgoing share text, QR codes, or watermarks. It is not an anti-hacking feature, forensic clock, anonymity control, or proof that timing correlation has been defeated.

An optional user-entered waiting target changes presentation only. It does not change assurance, and sharing remains possible before the target. No built-in security-significant waiting threshold is introduced without a separate threat-model and validation decision.

### Assumptions and consequences

- UI updates run only while relevant UI is visible and do not rewrite or reverify an artifact.
- Accessibility announcements are rate-limited to focus, explicit refresh, or meaningful state changes.
- Tests across restart and reboot verify restoration, non-negative output, and honest confidence state—not exact real elapsed time after arbitrary clock changes.

## AD-004: Assurance ends at the managed-artifact boundary

### Decision

Verification and assurance apply only to the exact Managed Artifact bytes or text representation held and controlled by Canonical Share. A badge is backed by the recorded digest, canonical revision, verification linkage, integrity state, and applicable assurance ceiling for that managed representation.

An export or share creates an External Copy. The app can state that the copy matched the verified artifact when it was generated, but it does not monitor later edits, recipient copies, destination storage, or continued byte identity. An External Copy receives a new assurance assessment only if it is re-imported and completes the applicable pipeline again.

Before managed sharing, stale or unknown integrity state, a migration, or another defined invalidation trigger requires revalidation. Revalidation may preserve or lower assurance; it never promotes beyond the originally verified class. Failure removes verified sharing capability and must not fall back to a source file or obsolete cache.

### Assumptions and consequences

- Export and share surfaces disclose the managed/external boundary.
- The boolean that an app-created export may exist is management information only. Destination, recipient, exact share time, and receiver completion are not retained.
- A derivative artifact remains subject to its derivative assurance ceiling.

## AD-005: Managed sharing minimizes duplicate data and treats cleanup as best effort

### Decision

Where practical, image sharing exposes a read-only content URI backed by the authoritative Managed Artifact, with temporary scoped read permission, instead of creating another permanent file. Text sharing uses the verified canonical text representation.

If Android interoperability requires a temporary share derivative, it is created only in an app-private share-cache root. It has a documented conservative expiry, cleanup is retryable and best effort, and stale-cache sweeps remove data no longer needed by the app. Deleting a Saved Result also targets its associated share-cache derivatives.

The app does not claim to know when a receiving app opens or finishes reading a stream, makes its own copy, completes its operation, or no longer needs permission. A chooser callback or activity result may drive transient UI only; it is not proof of receiver consumption.

Optional bounded local jitter may run before opening the Android Sharesheet. It must be cancellable, off the UI thread, require no network, and create no anonymity or anti-correlation claim. It is not an enforced waiting target or scheduled-share feature.

### Assumptions and consequences

- Temporary permission duration and cache expiry are Android lifecycle controls, not guarantees about recipient behavior.
- Share preparation may package or expose verified content but may not silently transform it after final verification. Any content-changing operation invalidates verification and must re-enter the verification path.
- Destination identity, recipient details, exact completion status, and exact share-launch time linked to an artifact are not persisted.

## AD-006: Saved Result visibility uses a two-check durable commit protocol

### Decision

Android cannot provide one atomic transaction spanning arbitrary files and a metadata database. The app therefore uses an application-level staged protocol that satisfies both the precommit and postcommit reopen requirements:

1. Write artifacts to an app-private persistent staging location.
2. Complete applicable file flush or synchronization operations.
3. Compute artifact digests and confirm canonical revision consistency.
4. Reopen the staged exact representation and validate it before Saved Result commit.
5. Promote artifacts to their final app-private locations and commit metadata in a non-visible/non-shareable pending state.
6. Reopen each artifact from its final location and compare digest, canonical revision, MIME/container expectations, and verification linkage.
7. Commit the visible, shareable lifecycle state only after all checks pass.

A failure before step 7 never creates a normal visible Saved Result. Recovery removes or quarantines orphan files, orphan records, incomplete transactions, reopen failures, digest mismatches, and migration mismatches. It never repairs content by substituting another artifact.

### Claim boundary

“Durably written” means the selected Android write, flush/sync, database, final-location reopen, digest, revision, and visibility protocol completed without reported error. It does not guarantee survival against defective firmware, physical storage failure, operating-system corruption, or catastrophic power or hardware loss beyond platform guarantees.

### Assumptions and consequences

- Exact database and filesystem primitives are implementation choices and must be documented beside the repository implementation.
- All Saved Result queries exclude pending, quarantined, deletion-pending, and invalidated lifecycle states from normal shareable results.
- The integrity sweep is safe to repeat and emits only content-free diagnostics.

## AD-007: Provider imports become bounded authoritative internal snapshots

### Decision

For a third-party content URI, the app reads only through the granted Android interface, performs a resource-bounded copy into app-private session storage under an app-generated name, closes the provider stream, and treats the internal copy—not the provider—as the authoritative immutable session source.

If the optional import delay is enabled, it runs after the copy completes and before the source is sealed. It is short, bounded, locally generated, off the UI thread, cancellable with the session, not network-dependent, and not persisted as content metadata. After the delay, the app reopens the internal copy and checks readability, expected byte length, digest, and approved internal location. A mismatch blocks acceptance.

### Claim boundary

A stable internal copy proves only what the app accepted into its session. It does not prove that the provider's underlying data was immutable before or during delivery, and the optional delay is not provider attestation, watermark removal, anonymity protection, or a security proof.

### Assumptions and consequences

- The Import Anchor is recorded only after the internal snapshot is validated and sealed.
- The app never falls back to an externally supplied filesystem path.
- Resource limits and optional delay bounds require documented implementation constants and tests; the delay may be omitted because it is optional.

## AD-008: Diagnostic traces are content-free and build-scoped

### Decision

Debug or explicitly approved diagnostic builds emit structured trace events for each block phase and app-applied transformation. An event may contain only:

- a session-local event identifier;
- block identifier and version;
- phase or transformation category;
- execution revision;
- canonical revision when applicable;
- outcome class; and
- a content-free reason code.

Events never contain source/output/OCR text, image bytes, raw before/after values, filenames, content URIs, filesystem paths, display labels, URL values, content-linked import timestamps, destinations, recipients, encryption keys, or digests usable as stable cross-session identifiers.

Persistent diagnostic tracing is disabled by default in production. Temporary in-memory traces used for local troubleshooting are cleared with the session. Content-free aggregate diagnostics require a separate product and privacy approval.

Trace coverage shows which code paths the app executed; it cannot prove every natural-language semantic consequence. Separately, every app-applied transformation has a change-ledger entry, and transformations with possible semantic impact remain reviewable. Sensitive ledger representations remain transient and are not copied into Saved Result diagnostics.

### Assumptions and consequences

- Session-local trace identifiers are newly generated per session and cannot be used for cross-session correlation.
- Production builds must have a testable default-off tracing configuration.
- Tests inject content, path, URI, label, timing, destination, key, and stable-identifier canaries and verify that trace sinks contain none of them.

## References

- Amendment 1: sections A1–A13, especially A1, A3–A7, A10, and A11.
- Amendment 2: precedence clause and sections B1–B10.
- Baseline: all clauses not superseded by the two amendments.
