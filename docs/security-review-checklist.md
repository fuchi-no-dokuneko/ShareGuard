# Canonical Share security review checklist

## Authority and review result

Apply specification precedence in this order: Amendment 2, Amendment 1, baseline. A checked box requires reviewable evidence; implementation intent alone is not sufficient.

- Release/build reviewed: ____________________
- Commit or artifact identifier: ____________________
- Reviewer(s): ____________________
- Corpus version: ____________________
- Built-in preset versions: ____________________
- Dependency/SBOM report: ____________________
- Review date: ____________________
- Result: [ ] Pass  [ ] Pass with declared residuals  [ ] Blocked
- Declared residuals and affected assurance ceilings: ____________________

## Verification-layer map

| Layer | Normative scope | Minimum evidence |
| --- | --- | --- |
| **A — Structural** | Valid workflow manifest; required blocks present and ordered; recognized block versions; current canonical/artifact revision; no post-verification transform. | Sequence-validator tests, manifest audit, preset migration tests, revision/digest audit, mutation tests. |
| **B — Content** | Final Unicode and URL scans; OCR round-trip; barcode rescan; approved reading order; all semantic differences ledgered and reviewed. | Exact final-text/image fixtures, OCR/URL/Unicode reports, empty second-pass ledger, review decision links. |
| **C — Container** | Actual metadata/chunk/thumbnail/profile/channel inventory; final artifact independently decodes; allowlist passes. | Reopened final bytes, independent parsers where practical, malformed-container tests, allowlist evidence. |
| **D — Provenance** | No source URI/name/provider/path; no unexplained source pixels; dependency map covers all block-defined dependencies; no stable device/session seed. | Source-reference scan, canary search, renderer operation/dependency audit, outgoing Intent inspection. |
| **E — Privacy engineering** | Offline operation; content-free logging; app-private storage; backup exclusion; cleanup/deletion; temporary share permission; transactional persistence and quarantine. | Network-capture test, dynamic log canaries, storage inventory, backup rules, deletion/integrity/process-death/share tests. |

Verification vocabulary is limited to `PASS`, `PASS_WITH_DECLARED_RESIDUAL`, `REVIEW_REQUIRED`, `NOT_APPLICABLE`, `NOT_RUN`, `FAIL`, and `ERROR`. A required `NOT_RUN`, `FAIL`, or `ERROR` prevents a verified assurance class. Verification failure downgrades or blocks; it never silently retries with weaker rules.

## Mandatory completion order

- [ ] Final content serialization completed. `[Layers B/C]`
- [ ] Mandatory verifiers inspected the exact final bytes or text representation. `[Layers A–E]`
- [ ] Assurance was computed deterministically and did not exceed the workflow ceiling. `[Layer A]`
- [ ] The final representation was reopened and its digest and canonical revision matched. `[Layers A/C]`
- [ ] The Saved Result committed transactionally and became visible only after the durable-write protocol. `[Layers A/E] [AC-PS-004, AC-A2-013]`
- [ ] Managed Share or user-selected export preparation occurred only after commit. `[Layers A/E]`
- [ ] No content-transforming block ran after verification; any such edit created a new revision and invalidated verification. `[Layer A]`

## Product and claim controls

- [ ] Input type, output type, current pipeline stage, and assurance state are always visible. `[AC-F-003, AC-U-001]`
- [ ] The UI separately labels **Detected**, **Changed**, **Verified**, **Saved**, and **Shared** where applicable. `[AC-U-002, AC-PU-004]`
- [ ] Threat model, limitations, residual findings, and derivative status are accessible. `[Layers A–E]`
- [ ] No UI/report/documentation claims “safe,” “anonymous,” “watermark removed,” guaranteed anonymity, or complete anti-forensics. `[AC-U-006]`
- [ ] Detector misses are described as “not detected,” never absence or cleanliness. `[Layer B]`
- [ ] Timing UI states that waiting is advisory and does not guarantee anonymity or defeat timing correlation. `[AC-PU-005, AC-A2-003, AC-A2-004]`
- [ ] Deletion wording distinguishes logical removal from physical flash sanitization. `[AC-A2-002]`
- [ ] Share/export wording states that later External Copy edits are not monitored. `[AC-A2-005, AC-A2-006]`
- [ ] Share-cache expiry and chooser callbacks are not described as receiver-consumption proof. `[AC-A2-007]`
- [ ] Provider snapshot stability is not presented as proof that the external provider was immutable. `[AC-A2-008, AC-A2-009]`
- [ ] Unknown platform/codec/native/hardware dependencies are disclosed as limitations. `[AC-A2-012]`

## Android platform and entry controls

- [ ] The Compose application layer is Kotlin; Java modules cross explicit typed interfaces without weakening immutability, null safety, cancellation, or verification. `[AC-A2-001]`
- [ ] Text shares enter as `TEXT` without broad media permission. `[AC-F-001]`
- [ ] Image shares use a third-party content URI with temporary input access. `[AC-F-002]`
- [ ] Photo Picker is used for in-app image selection. `[Layer E]`
- [ ] The default build has no broad media/photo permission. `[Layer E]`
- [ ] The strict/default offline build has no `INTERNET` permission and no silent model download. `[AC-S-001] [Layer E]`
- [ ] Incoming MIME, filename, metadata, URI, extras, and provider behavior are treated as untrusted. `[Layers C/D]`
- [ ] Third-party provider content is copied with bounds into app-private storage; the internal copy becomes the authoritative immutable source. `[AC-A2-008] [Layers C/D/E]`
- [ ] The internal snapshot is reopened and checked for readable state, expected length, digest, and approved path; mismatch blocks acceptance. `[Layers A/C/D]`
- [ ] Any bounded random delay runs off the UI thread, is cancellable, local/offline, nonpersistent, and makes no security or provider-attestation claim. `[AC-A2-009] [Layer E]`
- [ ] Multiple images are rejected in MVP and malformed mixed text/image input requires an explicit source choice. `[Layer A]`
- [ ] Resource guards use bounded probes before full decode and stop unsafe input without public-storage fallback. `[Layers C/E]`
- [ ] Clipboard and provider references are released when no longer needed. `[Layer E]`

## Pipeline, review, and ledger controls

- [ ] The workflow is a typed ordered sequence with one source/cursor and no branch, cycle, arbitrary wire, parallel hidden path, or executable user script. `[AC-F-004] [Layer A]`
- [ ] Mandatory blocks are pinned and cannot be removed. `[AC-F-005] [Layer A]`
- [ ] Invalid block order/type prerequisites are rejected before execution. `[AC-F-006] [Layer A]`
- [ ] Unknown block versions invalidate preset signatures until migration. `[Layer A]`
- [ ] External plug-ins cannot run in a high-assurance preset. `[AC-S-010] [Layer A]`
- [ ] Cancellation stops scheduling, invalidates partial artifacts, releases resources, deletes block temporaries, and never exposes partial output as verified. `[Layers A/E]`
- [ ] Every app-applied transformation has a `ChangeEntry` linked to block/version, canonical revision, source location, decision, before/after representation, reason, and verifier. `[AC-F-007, AC-A2-011] [Layers A/B]`
- [ ] Every possible semantic impact remains reviewable; unexplained semantic differences block canonical lock. `[AC-A2-011] [Layer B]`
- [ ] Protected code, math, identifiers, literals, and genuine multilingual spans are not destructively normalized without review. `[Layer B]`
- [ ] Every retained source region has explicit approval, overlay/notice, dependency lineage, report entry, and assurance downgrade. `[AC-F-008, AC-U-005] [Layers B/D]`
- [ ] The dependency map covers every exercised dependency defined by pipeline blocks: pixels, OCR text, layout, retained metadata, bundled assets, renderer primitives, decisions, and revisions. `[AC-S-007, AC-A2-012] [Layer D]`
- [ ] The dependency map does not claim knowledge of hidden OS/codec/native/hardware dependencies. `[AC-A2-012]`
- [ ] BOTH-mode text and image artifacts use the same Canonical Document revision. `[AC-F-009, AC-PF-010] [Layer A]`

## Unicode and text controls

- [ ] Unicode library/data version is pinned and recorded. `[Layer B]`
- [ ] Scalar, grapheme, normalization, default-ignorable, whitespace, script/confusable, punctuation, and structural inventories run where applicable. `[Layer B]`
- [ ] A maintained spoof/confusable library is used; there is no homemade frozen confusable database or global Greek/Cyrillic-to-Latin replacement. `[Layer B]`
- [ ] Genuine multilingual content and approved language exceptions are tested. `[Layer B]`
- [ ] Hidden/invisible controls are surfaced for review rather than silently discarded at import. `[Layer B]`
- [ ] Every normalization change is ledgered and protected spans remain unchanged unless explicitly edited. `[Layer B]`
- [ ] Final text contains no unapproved invisible controls. `[AC-S-005] [Layer B]`
- [ ] Final Unicode/script/confusable/punctuation scan matches approved policy and exceptions. `[Layer B]`
- [ ] Canonical normalization is idempotent; a second pass creates no changes. `[Layers A/B]`
- [ ] Rich text, spans, HTML alternatives, fonts, hidden annotations, and clipboard variants are absent from final plain text. `[Layers B/D]`

## URL controls

- [ ] URL extraction covers absolute, schemeless, encoded, Markdown, email-like, visible-domain, OCR, and QR-derived candidates. `[Layer B]`
- [ ] A maintained standards-based URL parser is used; regex is not the final parser. `[Layer B]`
- [ ] Maintained public-suffix/domain-boundary data is used; no last-two-label shortcut exists. `[Layer B]`
- [ ] Scheme, userinfo, host, registrable domain, subdomain, port, path, query, and fragment are inventoried. `[Layer B]`
- [ ] IDN/mixed-script/userinfo spoof checks run, with intentional international domains reviewable. `[Layer B]`
- [ ] Short/redirect links are not network-resolved in the default build and remain explicitly opaque. `[Layers B/E]`
- [ ] Query/path/subdomain/fragment/userinfo changes show component-level diffs and functionality warnings. `[AC-U-003] [Layer B]`
- [ ] Canonical URLs are serialized from approved parsed components, not in-place string edits. `[Layer B]`
- [ ] Final URL parse exactly matches approved decisions and removed components did not reappear. `[AC-S-006] [Layer B]`
- [ ] All required URL adversarial cases, including public-suffix, percent-encoding, Markdown, QR, and functional-breakage fixtures, pass. `[Layer B]`

## Image, OCR, renderer, and container controls

- [ ] Source header, detected type, dimensions, animation, bit depth, channels, metadata, thumbnail, profile, color, and alpha are inventoried under resource bounds. `[Layers C/E]`
- [ ] Orientation is materialized into pixels before later processing. `[Layer C]`
- [ ] Embedded source thumbnails and removed source metadata are absent from the final container. `[AC-S-004] [Layer C]`
- [ ] OCR models required by strict workflows are bundled/offline and OCR output remains untrusted. `[Layers B/E]`
- [ ] OCR consensus records agreement/disagreement without inventing averaged characters; high-impact ambiguity is reviewed. `[Layer B]`
- [ ] Reading order, layout, QR/barcode, and every non-text region have approved terminal decisions. `[Layer B]`
- [ ] Rebuilt output starts from a fresh canvas that cannot alias a source bitmap. `[Layer D]`
- [ ] Bundled fonts cover every rendered scalar; no silent system/OEM font fallback occurs. `[AC-S-011] [Layers B/D]`
- [ ] Canonical renderer uses bundled generic assets and controlled shaping/layout. `[Layer D]`
- [ ] Redactions are baked into final pixels and cannot reveal a hidden layer/source crop. `[Layers C/D]`
- [ ] Alpha is flattened and final color/channel representation matches the renderer profile. `[Layer C]`
- [ ] Fresh serialization copies no source chunks, tables, profiles, thumbnail, filename, timestamp, session ID, user ID, or device/build-host data. `[Layers C/D]`
- [ ] Final image metadata exactly matches the strict allowlist after reopening. `[AC-S-004] [Layer C]`
- [ ] Exact encoded output passes OCR round-trip or every meaningful residual is reviewed and reflected in assurance. `[Layer B]`
- [ ] Final QR/barcode rescan matches approved values and unexpected codes fail strict verification. `[Layer B]`
- [ ] Random perturbation is off by default, corpus-benchmarked where used, and never uses a stable device/install-derived seed. `[AC-S-012] [Layer D]`
- [ ] Derivative mode is clearly experimental and cannot exceed derivative assurance. `[AC-S-009, AC-PS-012] [Layer A]`

## Managed persistence and integrity controls

- [ ] Every successfully verified final output is automatically committed as one Saved Result. `[AC-PF-001]`
- [ ] Saved Result storage is physically/logically separate from transient sessions, sources, OCR intermediates, share cache, and crash diagnostics. `[AC-PS-002, AC-PS-003] [Layer E]`
- [ ] Persistent records contain only final artifacts and minimal display/verification/share/delete metadata. `[Layers D/E]`
- [ ] No source text/image history, OCR views/crops, source filename/URI, clipboard variants, temporary render files, hidden before-values, session paths, target history, destination identity, or removed metadata is persisted. `[AC-PS-001] [Layers D/E]`
- [ ] Artifact and metadata writes use a transactional commit protocol. `[Layer E]`
- [ ] Applicable flush/synchronization, database commit, final-location reopen, digest match, and canonical-revision match complete before visibility. `[AC-PS-004, AC-A2-013] [Layers A/C/E]`
- [ ] A failed transaction creates no normal visible Saved Result; transient verified output is clearly distinguished and may be retried safely. `[AC-PF-009]`
- [ ] The authoritative stored artifact is immutable; rename/favorite/sort/label changes do not alter bytes. `[AC-PF-006]`
- [ ] Editing managed content creates a new session/result rather than overwriting verified bytes. `[AC-PF-007]`
- [ ] Previews derive only from the Managed Artifact, are bounded, and cannot replace or establish authority for it. `[Layers C/D/E]`
- [ ] Stale, unknown, migrated, restored, or explicitly requested integrity state triggers Saved Result revalidation before verified share. `[AC-PS-005] [Layers A/C]`
- [ ] Revalidation can only maintain or downgrade the original assurance. `[Layer A]`
- [ ] Persistent integrity sweep detects orphan files/records, incomplete transactions, reopen failures, digest mismatch, and migration mismatch. `[AC-PS-008, AC-A2-014] [Layers A/C/E]`
- [ ] Affected records are quarantined from verified sharing until repaired, revalidated, or deleted. `[AC-A2-014]`
- [ ] Schema migrations do not rewrite verified bytes unless the result is revalidated. `[Layers A/C/E]`
- [ ] Automatic cloud backup is disabled by default and backup-exclusion behavior is tested. `[AC-PS-009] [Layer E]`
- [ ] Where encryption is used, keys use platform protection and are not hard-coded, user-derived, adjacent plaintext, uploaded, or treated as artifact content. `[Layer E]`

## Reference Timer controls

- [ ] Import Anchor is created exactly once only after safe source acceptance/snapshot/seal, never from source metadata. `[AC-PT-001]`
- [ ] Wall-clock time supports persistence/restoration and monotonic time supports same-boot live display. `[AC-A2-003]`
- [ ] Process restoration, reboot, time-zone/DST change, and ordinary clock change preserve a best-effort non-negative display. `[AC-PT-004, AC-PT-005, AC-PT-006, AC-A2-003]`
- [ ] Reduced/unknown clock confidence is neutral, visible in technical details where useful, and neither blocks sharing nor raises assurance. `[AC-A2-004]`
- [ ] The UI identifies the value as time since app import, not processing/OCR/verification duration or screenshot/source age. `[AC-PT-002, AC-PT-010]`
- [ ] The timer appears on workflow/review/preview/final/Saved Results/detail/pre-share surfaces as required. `[AC-PT-003, AC-PT-007, AC-PT-008]`
- [ ] No timer, import timestamp, or waiting target appears in output text, pixels, metadata, filenames, outgoing data, QR codes, or watermark. `[AC-PT-009] [Layers C/D]`
- [ ] There is no agent-invented default waiting threshold; any future threshold has documented threat-model, research, and validation evidence. `[AC-PT-011]`
- [ ] TalkBack announcements are rate-limited and duration UI remains accessible with large text/landscape. `[AC-PT-012]`

## Managed sharing and External Copy controls

- [ ] Sharing uses the Android system Sharesheet; there is no custom target list. `[AC-F-010]`
- [ ] Text uses the approved plain-text representation; image sharing uses a scoped read-only content URI and temporary read permission. `[AC-PS-006] [Layers C/E]`
- [ ] Where practical, the URI exposes the authoritative Managed Artifact rather than an unnecessary permanent duplicate. `[Layer E]`
- [ ] Temporary share derivatives are app-private, conservatively expired, best-effort/retryable, and stale-swept. `[AC-A2-007] [Layer E]`
- [ ] Outgoing MIME, URI authority/path scope, permission flags, digest, and selected BOTH artifact/bundle are inspected immediately before Sharesheet launch. `[Layers C/D/E]`
- [ ] Revalidation failure, partial deletion, corruption, or quarantine blocks verified sharing; no fallback uses source or obsolete cache. `[AC-PS-005, AC-A2-014]`
- [ ] Destination identity, recipient information, exact share-launch/completion state, and chooser result are not persistently recorded. `[AC-PS-010] [Layer E]`
- [ ] Chooser callbacks or activity results are used only for transient UI and never as read-completion proof. `[AC-A2-007]`
- [ ] The pre-share UI shows advisory Time since import but permits sharing before an optional waiting target. `[AC-A2-004]`
- [ ] Optional local share jitter is bounded, cancellable, offline, nonpersistent, and makes no anti-correlation claim. `[AC-A2-009]`
- [ ] Baseline Explicit Save is implemented as export of a user-selected External Copy, not a second required internal save. `[AC-F-011, AC-A2-015]`
- [ ] Export/share UI explains that the generated copy is verified at generation time and later external edits are not monitored. `[AC-A2-005, AC-A2-006]`
- [ ] An External Copy is not silently displayed later as a Managed Artifact; re-entry requires full re-import and verification. `[AC-A2-006]`

## Logical deletion and cleanup controls

- [ ] Single and bulk deletion require understandable confirmation and distinguish in-app data from External Copies. `[AC-PF-008, AC-PU-006]`
- [ ] Logical deletion inventories and removes metadata, artifacts, previews, indexes, verification summaries, labels, timing data, share-cache copies, optional per-result keys, and migration/recovery references. `[AC-PS-007, AC-A2-002] [Layer E]`
- [ ] Partial deletion immediately removes the item from normal shareable results, enters deletion-pending state, blocks sharing, and schedules idempotent retry. `[AC-A2-002]`
- [ ] Deletion diagnostics contain no content or sensitive paths. `[Layer E]`
- [ ] Re-enumeration verifies that every app-addressable reference for the Saved Result ID is gone. `[Layer E]`
- [ ] UI wording makes no physical flash-overwrite, wear-leveling, controller-remapping, or forensic-sanitization claim. `[AC-A2-002]`
- [ ] Transient-session purge removes source copies, OCR views, render intermediates, temporary reports, transient caches, and in-memory references. `[AC-F-012] [Layer E]`
- [ ] Clearing a session does not delete a committed Saved Result; deleting a result does not require its original session. `[Layer E]`
- [ ] Startup/crash stale-session cleanup and persistent-store integrity recovery are separately tested. `[AC-A2-014]`

## Logging, traces, privacy, and backup controls

- [ ] Normal logs contain no raw source/output text, URL values, OCR text/crops, image bytes, filenames, URIs, provider authority, internal paths, metadata canaries, display labels, or session secrets. `[AC-S-002] [Layer E]`
- [ ] No output, report, Saved Result, or outgoing Intent exposes source URI or filename. `[AC-S-003, AC-PS-001] [Layer D]`
- [ ] Import/share timing is not linked in logs or analytics to content identity, label, target, or waiting target. `[AC-PS-010] [Layer E]`
- [ ] Debug/approved diagnostic builds emit a content-free event for each block phase and app-applied transformation. `[AC-A2-010]`
- [ ] Trace fields are limited to session-local event ID, block/version, phase/category, execution/canonical revision, result class, and content-free reason code. `[AC-A2-010]`
- [ ] Traces exclude content, before/after values, filenames, URIs, paths, labels, URLs, content-linked timestamps, targets, recipients, keys, and stable cross-session digests/identifiers. `[AC-A2-010]`
- [ ] Persistent production tracing is off by default; temporary in-memory traces clear with the session. `[Layer E]`
- [ ] Trace coverage is not described as proof of all semantic consequences. `[AC-A2-011]`
- [ ] Automatic backup remains disabled and no default edition uploads artifacts, keys, OCR, or models. `[AC-PS-009]`

## Assurance and final verification controls

- [ ] `VER-001`: exact required block/version manifest and valid order pass. `[Layer A]`
- [ ] `VER-002`: all artifacts share the approved canonical revision and no post-verification edit exists. `[Layer A]`
- [ ] `VER-003`: exact final image bytes pass metadata/chunk/thumbnail/profile allowlist. `[Layer C]`
- [ ] `VER-004`: exact final text or final-image OCR passes Unicode policy. `[Layer B]`
- [ ] `VER-005`: final URLs match approved parsed decisions. `[Layer B]`
- [ ] `VER-006`: exact encoded rebuilt image passes OCR/reading-order comparison or reviewed residual handling. `[Layer B]`
- [ ] `VER-007`: bundle, report, runtime objects, metadata, and Intent contain no source URI/name/provider/path. `[Layer D]`
- [ ] `VER-008`: renderer log, dependency map, and final artifact agree on every declared source dependency. `[Layer D]`
- [ ] `VER-009`: final machine-readable codes match approved decisions. `[Layer B]`
- [ ] `VER-010`: every source region has a terminal remove/rebuild/replace/retain policy. `[Layers B/D]`
- [ ] `VER-011`: second canonicalization pass is idempotent. `[Layers A/B]`
- [ ] `VER-012`: strict workflow completes under blocked network with traffic capture and no model download. `[Layer E]`
- [ ] `VER-013`: static/dynamic canary logging audit passes. `[Layer E]`
- [ ] `VER-014`: every AS-0…AS-4 rule, contradictory state, ceiling, and unknown-state downgrade is unit tested. `[Layer A]`
- [ ] `VER-015`: human report agrees with machine results, lists residuals/source regions, and makes no anonymity claim. `[Layers A–E]`
- [ ] Every verifier has a deliberately failing fixture. `[Layers A–E]`
- [ ] A verification failure cannot display a high-assurance badge. `[AC-S-008]`
- [ ] User choices, labels, timer settings, preview settings, rename, favorite, or display metadata cannot promote assurance. `[AC-PS-011]`
- [ ] Saved Result persistence/revalidation never exceeds the original verified class. `[Layer A]`
- [ ] Assurance badges apply only to exact Managed Artifact bytes/text controlled by the app. `[AC-A2-005]`

## Baseline AC-S traceability

| Criterion | Release control | Verification layer(s) | Required evidence |
| --- | --- | --- | --- |
| `AC-S-001` | Strict workflows complete without network. | E | Blocked-network instrumentation plus traffic capture; bundled model availability. |
| `AC-S-002` | No raw source data in normal logs. | E | Static inspection and dynamic canary log/crash/analytics search. |
| `AC-S-003` | No output exposes source URI or filename. | D | Final artifact/report/Intent/runtime reference audit with canaries. |
| `AC-S-004` | Final image metadata matches allowlist. | C | Reopen exact saved bytes with maintained parser(s), including chunks/thumbnails/profiles. |
| `AC-S-005` | Final text has no unapproved invisible controls. | B | Final scalar/default-ignorable/bidi scan against approved exceptions. |
| `AC-S-006` | Final URLs match approved components. | B | Extract and parse final text/OCR; component-by-component decision comparison. |
| `AC-S-007` | Rebuilt-image dependency map is complete and accurate within defined pipeline scope. | D | Operation log, region manifest, canonical revision, user decisions, and exercised fixture comparison. |
| `AC-S-008` | Verification failure cannot show high assurance. | A | Classifier contradictory-state/unit tests and UI integration test. |
| `AC-S-009` | Derivative cannot exceed derivative assurance. | A | Preset ceiling/classifier tests, including persistence round-trip. |
| `AC-S-010` | External plug-ins cannot enter high-assurance presets. | A | Preset validator negative tests and imported-preset tests. |
| `AC-S-011` | No silent device-font fallback. | B, D | Glyph-coverage and missing-glyph instrumentation tests; renderer dependency audit. |
| `AC-S-012` | Random perturbation has no stable device-derived seed. | D | Code/config review, repeated-session tests, output metadata/log search. |

## Amendment 1 security acceptance traceability

| Criterion | Required release evidence | Layer(s) |
| --- | --- | --- |
| `AC-PS-001` | Persistent-store, report, preview, and share canary scan shows no source URI/name. | D, E |
| `AC-PS-002` | Session purge removes sources/OCR intermediates without deleting committed result. | E |
| `AC-PS-003` | Storage inventory proves persistent artifacts remain inside approved private roots. | E |
| `AC-PS-004` | Transaction/process-death tests prove invisibility until durable write, commit, and reopen. | A, C, E |
| `AC-PS-005` | Corrupt/migrated artifact revalidation removes verified share capability. | A, C |
| `AC-PS-006` | Cross-app share test proves read-only content URI and scoped temporary grant. | C, E |
| `AC-PS-007` | Single/bulk/interrupted deletion tests remove previews and share derivatives. | E |
| `AC-PS-008` | Orphan file/record fixtures are detected and quarantined/reconciled. | A, E |
| `AC-PS-009` | Manifest/data-extraction/backup rules and device backup tests prove backup is off. | E |
| `AC-PS-010` | Log/database/analytics inspection finds no content-linked import/share timing. | E |
| `AC-PS-011` | UI/model tests prove management metadata cannot call assurance promotion. | A |
| `AC-PS-012` | Stored derivative retains derivative ceiling across restart/migration/revalidation. | A |

## Amendment 2 acceptance traceability

| Criterion | Required release evidence | Layer(s) |
| --- | --- | --- |
| `AC-A2-001` | Kotlin Compose app and typed Java interop boundary review. | A |
| `AC-A2-002` | Complete app-addressable deletion inventory, partial-delete retry, and honest wording. | E |
| `AC-A2-003` | Process/reboot/clock-change tests produce non-negative advisory duration. | E |
| `AC-A2-004` | Clock-confidence tests neither block share nor raise assurance. | A, E |
| `AC-A2-005` | Badge/digest tests bind assurance to the Managed Artifact only. | A, C |
| `AC-A2-006` | Export/edit/re-entry tests do not treat External Copies as managed. | A, D |
| `AC-A2-007` | Documented retryable share-cache policy and UI text avoid consumption claims. | E |
| `AC-A2-008` | Provider import tests prove authoritative bounded internal snapshot. | C, D, E |
| `AC-A2-009` | Delay/jitter tests prove off-UI, cancellation, offline operation, and no claim/metadata leak. | D, E |
| `AC-A2-010` | Trace coverage test plus prohibited-data canaries. | E |
| `AC-A2-011` | Transformation mutation tests prove ledger coverage and reviewability. | A, B |
| `AC-A2-012` | Exercised dependency fixtures are fully represented and unknown platform causes remain disclosed. | D |
| `AC-A2-013` | Durable-write/process-death test proves visibility only after full commit protocol. | A, C, E |
| `AC-A2-014` | Crash/integrity fixtures quarantine incomplete, mismatched, migrated, and orphaned records. | A, C, E |
| `AC-A2-015` | UX/instrumentation test proves Explicit Save exports a copy and is not required for internal persistence. | A, E |

## Adversarial and lifecycle test gate

- [ ] All `TC-TXT-001` through `TC-TXT-020` relevant to enabled text profiles pass.
- [ ] All `TC-URL-001` through `TC-URL-015` pass.
- [ ] All `TC-IMG-001` through `TC-IMG-020` relevant to supported formats/profiles pass.
- [ ] Convergence families compare semantic equivalents and document intentional differences.
- [ ] Mutation tests cover block order/version, allowlist, URL parse result, canonical revision, dependency flags, outgoing MIME, and cleanup policy.
- [ ] Canary values in source filename, URI, metadata, URL query, Unicode, OCR crop, and session path do not escape approved visible content/session review surfaces.
- [ ] Persistent canaries do not appear in Saved Result metadata, previews, logs, Intents, or exports unless they are approved visible final content.
- [ ] Stored-byte modification disables verified share.
- [ ] Preview substitution cannot replace the authoritative artifact.
- [ ] Process death is tested during import, transform, verification, transaction commit, share-cache creation, deletion, and migration.
- [ ] Reboot, wall-clock rollback/advance, time-zone/DST transition, and reduced clock confidence are tested.
- [ ] Orphan files, orphan records, incomplete transactions, reopen failure, digest mismatch, and migration mismatch are tested.
- [ ] Low-storage persistence failure, key unavailability where encryption is enabled, interrupted deletion, and interrupted save are tested.
- [ ] Cross-app sharing, temporary grant scope, receiver delay, cancellation, and stale-cache sweep are tested without claiming receiver completion.
- [ ] TalkBack labels, rate-limited timer announcements, non-color status, large text, and landscape layouts are tested.

## Dependencies, governance, and release artifacts

- [ ] Every dependency record includes exact purpose, official source, license, maintenance, Android compatibility, offline and transitive-network behavior, data handling, measured binary-size impact, alternatives, and removal plan.
- [ ] Maintained components are used for Unicode/spoof checking, URL parsing/public suffixes, image codecs/metadata, OCR, barcode scanning, and Android storage/sharing; custom substitutes have explicit approval.
- [ ] Unicode data, parser/ruleset/suffix data, OCR models, fonts, renderer, block, preset, and schema versions are recorded.
- [ ] No arbitrary numeric OCR, detector, image-quality, resource, waiting-time, or share-jitter threshold was invented; every product threshold has documented corpus/research evidence.
- [ ] Static analysis, unit tests, instrumentation tests, migration tests, adversarial corpus, dependency report, SBOM, license review, and reproducible-build evidence are attached.
- [ ] Storage, backup, network, logging, key-management, screenshot/recent-app-preview, deletion, migration, and timing-leakage reviews are attached.
- [ ] Every new block has stable ID/version, typed contract, settings schema, action, failure/cleanup/invalidation behavior, threat and assurance mapping, unit/integration/negative tests, verifier, UX/help text, ledger output, and documentation.
- [ ] No hidden feature flag bypasses verification, no security-sensitive change lacks adversarial tests, and no assurance/schema change bypasses required security review/migration tests.

## Final release decision

- [ ] Every applicable checkbox has objective evidence.
- [ ] Every required verifier has both passing and deliberately failing fixtures.
- [ ] Unknown states downgrade or quarantine; none silently pass.
- [ ] All residuals are explicit in the UI/report and reflected in the assurance ceiling.
- [ ] Managed Artifact, External Copy, deletion, timer, provider, receiver, dependency, durability, and physical-storage boundaries use honest wording.
- [ ] No unresolved failure permits a high-assurance badge or verified Managed Share.
- [ ] Security reviewer approval: ____________________
- [ ] Architecture reviewer approval: ____________________
- [ ] Test reviewer approval: ____________________
- [ ] UX/accessibility reviewer approval: ____________________
- [ ] License/dependency reviewer approval: ____________________

## Source references

- Baseline: threat/assurance §§2–4; screens §§5–6; pipeline/data/blocks §§7–9; verification §12; corpus §13; acceptance §14; Android architecture §15; work packages §16; governance §17; prohibited patterns §19; release checklist §20.
- Amendment 1: persistence/timer/product A1–A4; blocks A5; storage/privacy A6; sharing/settings/errors A7–A9; acceptance/tests/work packages A10–A12.
- Amendment 2: precedence/language B1; logical deletion B2; timer B3; assurance scope B4; share/cache B5; snapshot/delay B6; trace events B7; dependency scope B8; durable write B9; completion order B10; acceptance B11.
