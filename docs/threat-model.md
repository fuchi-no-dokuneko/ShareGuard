# Canonical Share threat model

## Authority and scope

This document is a normative synthesis of the three product specifications. Conflicts are resolved in this order:

1. `canonical_share_android_implementation_spec_v1.amendment2.txt`
2. `canonical_share_android_implementation_spec_v1.ammend.txt` (Amendment 1)
3. `canonical_share_android_implementation_spec_v1.txt` (baseline)

Amendment 2 establishes that the primary application language is Kotlin, the UI is Jetpack Compose, and Java-compatible processing modules may be used behind explicit typed interfaces. This threat model does not weaken the baseline immutable-context, cancellation, review, verification, or assurance boundaries.

Canonical Share accepts exactly one primary source per session: text or one image. Its security objective is **convergence**: source representations with the same intended visible meaning should produce the same, or substantially more similar, canonical output. The application reduces inherited provenance signals; it does not provide guaranteed anonymity, complete anti-forensics, or an anonymous transport.

The authoritative processing flow is:

1. Accept and snapshot one source into app-private transient storage.
2. Inspect source representations and record findings.
3. Build and review a Canonical Document.
4. Serialize canonical text and/or freshly render an image.
5. Verify the exact final representation and compute assurance.
6. Reopen, digest-check, and transactionally commit a Managed Artifact as a Saved Result.
7. Share the Managed Artifact or export a user-selected External Copy.
8. Purge transient session material independently of Saved Result retention.

No content-transforming block may execute after final verification without invalidating that verification.

## Adversary model

The adversary controls the source platform and can give different users subtly different representations of apparently identical content. The adversary may later obtain one or more shared copies and correlate retained features with candidate users. It may combine many weak observations across samples instead of relying on one visible marker.

The adversary may control or influence:

- incoming Android Intent extras and MIME declarations;
- provider filenames, metadata, stream behavior, and source encoders;
- text spans, HTML or clipboard variants, Unicode code points, URLs, and QR/barcode values;
- source image bytes, pixels, containers, thumbnails, profiles, alpha, and layout;
- destination applications and copies made after export or sharing;
- malformed inputs intended to exhaust resources or confuse parsers.

The app assumes that ordinary Android APIs cannot prove provider immutability before delivery, exact receiver consumption, exact elapsed real time after arbitrary clock changes, physical flash sanitization, or integrity of copies outside app control.

## Trust boundaries

### Trusted within the product model

- Signed application code.
- Bundled fonts, renderer assets, normalization tables, and version-pinned maintained libraries.
- Typed pipeline engine, immutable `ExecutionContext`, review decisions, change ledger, and post-output verifiers.
- App-private transient session storage while it is managed by the app.
- A Saved Result only after the durable-write protocol completes: file writing and applicable flush/synchronization succeed, metadata commits transactionally, the artifact is reopened from its final app-private location, and digest plus canonical revision match.

Trust does not mean infallibility. Persistent integrity sweeps and revalidation must detect corruption, incomplete transactions, migration mismatch, and artifact substitution.

### Untrusted

- Incoming Intent extras, claimed MIME types, filenames, provider metadata, and external URIs.
- Source bytes and pixels, source encoders, rich text, styled spans, HTML alternatives, clipboard variants, OCR results, decoded QR/barcode values, and URLs.
- User-selected optional plug-ins and all third-party share targets.
- External Copies after export, sharing, recipient copying, or external editing.
- Source metadata timestamps as evidence of import or capture time.

### Partially trusted or constrained platform dependencies

- Android framework decoders and device-specific codecs.
- Library parsers and native libraries.
- Platform font and shaping behavior outside the bundled renderer boundary.
- System locale, wall clock, time zone, and daylight-saving behavior.
- Operating-system internals, hardware behavior, storage firmware, and flash controllers.

High-assurance output must minimize dependence on partially trusted components. Unknown platform-level causal dependencies remain explicit limitations; they are never silently treated as absent.

### Managed Artifact and External Copy boundary

Verification and an assurance badge apply only to the exact Managed Artifact bytes or text representation currently stored and controlled by Canonical Share. An External Copy is outside that boundary. The app does not monitor destinations, recipient copies, later edits, or continued byte identity. An External Copy receives a new assurance assessment only after re-import and a complete applicable pipeline run.

## Normative threat catalog

| Threat ID | Threat | Required treatment and residual limit |
| --- | --- | --- |
| `TH-FONT-001` | Per-glyph outlines, kerning, hinting, antialiasing, and one-pixel character variants. | Canonical text removes source glyph pixels. Rebuilt images use versioned bundled fonts and controlled shaping with no silent device-font fallback. Retained source regions and derivative images remain exposed. |
| `TH-SPATIAL-001` | Repeated blocks, tiling, alignment markers, rotations, padding, and local texture patterns. | Full rebuild uses a fresh canvas and canonical layout. Spatial diagnostics are comparative only. Derivative resampling may weaken simple patterns but cannot certify removal. |
| `TH-FREQ-001` | Frequency-domain or combined robust watermark signals. | Fully rebuilt textual content severs source pixels. Frequency analysis is diagnostic, corpus-calibrated, cannot promote assurance, and a detector miss is not evidence of absence. Source regions and derivatives retain residual risk. |
| `TH-LSB-001` | Least-significant-bit and low-amplitude color-channel differences. | Fresh reconstruction, canonical color conversion, and controlled serialization sever source pixels where no region is retained. Low-bit diagnostics are non-certifying; derivative channel conversion/quantization only lowers risk for simple signals. |
| `TH-ALPHA-001` | Transparent or near-transparent content and auxiliary channels. | Inventory source channels, flatten rebuilt output onto a canonical background, and verify the final channel/alpha model. Derivatives must canonicalize channels and remain lower assurance. |
| `TH-UNICODE-001` | Zero-width/default-ignorable characters, bidi controls, variation selectors, combining marks, and unusual whitespace. | Inventory exact scalars and graphemes, review language-sensitive controls, apply approved normalization, record every change, and rescan final output. Unapproved controls prevent verified canonical assurance. |
| `TH-CONFUSABLE-001` | Mixed Latin/Greek/Cyrillic scripts, homoglyphs, compatibility forms, and width variants. | Use a maintained, version-pinned spoof/confusable implementation. Operate on reviewed tokens under a language policy; never globally transliterate genuine language. Final scans compare against approved exceptions. |
| `TH-PUNCT-001` | Quote, apostrophe, dash, ellipsis, separator, and line-ending variants. | Inventory variants, protect code/math/literals, apply a language-appropriate reviewed profile, ledger changes, require idempotence, and preserve approved exceptions. |
| `TH-META-001` | EXIF, XMP, ICC, text/application chunks, timestamps, thumbnails, encoder fields, and filenames. | Inventory source metadata without copying it. Fresh serialization writes a strict allowlist. Reopen and rescan exact output bytes, including thumbnails and profiles. Saved Result metadata excludes removed source metadata. |
| `TH-URL-001` | Query, fragment, path, userinfo, personalized subdomain, redirect, and high-entropy identifiers. | Extract candidates, parse with a maintained standards-based parser and maintained public-suffix data, inventory all components, warn about opaque redirects without network resolution, apply reviewed policies, serialize from approved components, and parse final output again. |
| `TH-LAYOUT-001` | Line wrapping, paragraph/list order, indentation, dimensions, bubble/card geometry, and spacing. | Review reading order and semantic structure. High-assurance rebuilt output uses generic canonical layouts instead of copied source geometry. Source-derived layout decisions remain represented in the dependency map. |
| `TH-SEMANTIC-001` | Personalized wording, synonyms, sentence order, spelling, and content choices. | The app does not translate or paraphrase by default. Possible meaning changes require before/after review and a change-ledger entry. Canonicalization cannot remove all semantic personalization. |
| `TH-BARCODE-001` | QR/barcode content containing identifiers, personalized URLs, or tokens. | Decode locally, route decoded values through Unicode and URL policy, treat undecodable codes as unknown regions, and rescan the exact final image. |
| `TH-REGION-001` | Avatars, maps, notifications, account names, timestamps, icons, photographs, and unique background/UI content. | Every non-text region receives a terminal policy. Retention requires explicit approval, persistent overlay/notice, dependency lineage, final-report disclosure, and assurance downgrade. Unknown regions never default to retention. |
| `TH-FILE-001` | Container ordering, tables, chunk order, codec fingerprint, color profiles, and quantization structure. | Encode a new approved container without source chunks/tables/thumbnails/filenames. Reopen with independent parsers where practical and enforce the final allowlist. |
| `TH-CROSS-SAMPLE-001` | Correlation from accumulated weak signals across repeated captures or shares. | Favor deterministic semantic canonicalization and fresh reconstruction; avoid stable per-install/device seeds. Convergence and multi-sample adversarial tests are required. No single detector or timer defeats all correlation. |

## Additional persistent, timing, and platform threats

| Threat | Required control and limit |
| --- | --- |
| Persistent local retention | Automatically saved results contain only verified final artifacts and minimal management/assurance/timing metadata. Sources, OCR views/crops, filenames, URIs, hidden ledger values, paths, target history, and removed metadata remain excluded. Storage is app-private; backup is off by default. |
| Partial or corrupt persistence | Files and metadata commit transactionally. Results are invisible until durable write, digest, revision, and reopen checks pass. Integrity sweeps quarantine orphaned, incomplete, mismatched, or migration-failed records from verified sharing. |
| Artifact or preview substitution | The Managed Artifact is immutable. Previews derive only from it and are never authoritative. Revalidation checks digest, MIME/container properties, canonical revision, and assurance linkage. |
| Key exposure or loss | When encryption is used, keys use Android platform protection and are never hard-coded, user-ID-derived, stored beside ciphertext, or uploaded. Key loss cannot turn ciphertext into a valid artifact. |
| Backup/account correlation | Cloud backup of Saved Results is disabled unless a separate threat-model revision covers consent, encryption, account correlation, restore integrity, deletion, and cross-device timing. |
| Incomplete deletion | “Delete permanently” means logical deletion of all app-addressable metadata, artifacts, previews, indexes, summaries, labels, timing data, caches, optional keys, and recovery references. Partial deletion immediately hides/quarantines the item, blocks sharing, and enters idempotent retry. No physical-flash claim is allowed. |
| Clock manipulation and timing correlation | Time since import is an advisory Reference Timer. Use monotonic time during the same boot and wall clock for persistence/restoration; prevent negative values and expose neutral confidence. Clock confidence never promotes assurance or blocks sharing by itself. |
| Timer leakage | Import time, elapsed duration, and waiting target stay in UI/management metadata only. They are never placed in text, pixels, metadata, filenames, share extras, QR codes, or visible watermarks. |
| Provider mutation | A bounded private copy becomes the authoritative immutable source. Optional bounded random delay is cancellable/off the UI thread; the internal copy is then reopened and checked. A stable copy does not prove provider stability. |
| Receiver and share-cache uncertainty | Prefer a read-only URI backed by the Managed Artifact. Temporary derivatives are private, conservatively expired, best-effort/retryable, and stale-swept. Chooser callbacks and expiry do not prove receiver consumption. Destination/recipient/completion identity is not persisted. |
| External editing | Assurance ends at the exported/shared copy boundary. Later external integrity is not monitored or implied. |
| Diagnostic leakage | Debug/approved diagnostic builds emit content-free, session-local trace events for block phases and app transformations. Traces exclude content, values, paths, labels, timestamps linked to content, targets, keys, and stable cross-session identifiers. Persistent tracing is off by default in production. |
| Incomplete dependency knowledge | The dependency map completely covers dependencies introduced or declared by pipeline blocks: pixels, OCR text, layout, intentionally retained metadata, bundled assets, renderer primitives, decisions, and revisions. It does not claim visibility into hidden OS/codec/native/hardware causes. |
| Resource exhaustion | Bounded probes and resource plans precede full decoding. Unsupported, ambiguous, oversized, malformed, or unsafe inputs stop without public-storage or source-pixel fallback. |
| App-generated fingerprint | Session IDs, random perturbation, and bounded delays are locally generated and not derived from stable account/device/install identifiers or persisted into artifacts. |

## Output-specific limits

### Canonical Text

- Strongly severs source font, pixel, alpha, frequency, and image-container channels.
- Can normalize reviewed Unicode, punctuation, whitespace, structure, and URL components.
- May retain wording, ordering, visible account information, identifiers, and other semantic personalization.
- Depends on correct extraction, protected-span handling, user review, final rescans, and idempotence.

### Rebuilt Image

- Strongly severs source text glyph pixels only when all text is redrawn from approved canonical text.
- Severs source container metadata through fresh serialization and final-byte verification.
- Severs source layout only when canonical layouts replace exact source geometry.
- May retain approved source pixels, OCR mistakes, semantic personalization, or unknown platform-level dependencies. Every retained region must be reported.

### Appearance-Preserving Derivative

- May weaken basic metadata, alpha, LSB, color, and pixel-grid signals.
- Remains statistically and semantically dependent on source pixels.
- Cannot reliably remove robust watermarking, cannot be the default, and can never receive AS-3 or AS-4.

### Saved Result and preview

- Persistence preserves the verified assurance class; it never raises it.
- A card or preview is not proof that stored bytes remain intact. Stale, unknown, migrated, or suspect results require revalidation before Managed Share.
- Rename, favorite, sort, display-label, preview, and Reference Timer changes are management-only and do not alter verification.
- Editing content creates a new session and Saved Result; it never silently overwrites the prior Managed Artifact.

### Verification report and diagnostics

- The human-readable report states executed facts, unresolved findings, source-pixel regions, and limitations; it does not reproduce source secrets beyond what is necessary to explain decisions.
- A persistable verification summary is compact and excludes removed metadata, source values, hidden ledger before-values, filenames, URIs, and session paths.
- Content-free Diagnostic Trace Events prove only which application phases and transformations executed. They are not output artifacts and do not prove that every semantic consequence or hidden watermark was detected.

### External Copy

- Verified status applies to the copy generated at export/share time only.
- The app does not claim later external byte identity, lack of edits, revocation, receiver deletion, or continued assurance.

## Security claim language

### Forbidden claims

The application, UI, report, documentation, and diagnostics must not state or imply:

- “safe” as an absolute content state;
- “anonymous” or “guaranteed anonymity”;
- “watermark removed” or reliable removal of every proprietary/unknown watermark;
- absence of a threat merely because a detector found nothing;
- complete anti-forensics or proof that the source cannot be identified;
- protection from destination-account, network, device, behavioral, social-graph, or all timing correlation;
- that waiting or bounded random delay defeats timing correlation;
- that the Reference Timer is exact after arbitrary clock changes, is screenshot/capture age, or is security evidence;
- that a stable internal snapshot proves the external provider was immutable;
- that a chooser result, activity result, grant expiry, or cache deletion proves the receiver finished reading;
- that an External Copy remains unchanged or managed after export/share;
- verified physical overwriting, flash-cell sanitization, control of wear leveling/controller remapping, or forensic erasure of inaccessible remnants;
- guaranteed durability across physical storage failure, defective firmware, OS corruption, or catastrophic hardware/power loss;
- complete knowledge of hidden dependencies inside OS, codecs, native libraries, shaping engines, hardware, or undocumented third-party internals;
- that diagnostic trace coverage proves every natural-language semantic consequence.

### Allowed evidence-backed language

Claims may describe only completed, scoped facts, for example:

- “Source metadata was not copied.”
- “The final output was reopened and re-scanned.”
- “Text was serialized from the approved Canonical Document revision.”
- “Text regions were rendered with the bundled renderer and fonts.”
- “The specified URL components were removed.”
- “The listed unresolved findings remain.”
- “The listed output regions still depend on source pixels.”
- “Canonical text was generated and verified.”
- “Image rebuilt; listed source regions retained.”
- “Fully rebuilt from reviewed content,” only when AS-4 conditions hold.
- “Saved in Canonical Share,” only after the durable-write protocol succeeds.
- “Removed from Canonical Share,” for completed logical deletion, while distinguishing it from physical-media sanitization.
- “Time since import is an approximate reference based on this device’s system clock. It is not a security guarantee and may change when the device clock changes.”
- “Verified status applies to the copy generated at export time; later external edits are not monitored.”

## Assurance classes

Assurance is computed from executed blocks, verification evidence, source dependencies, and the applicable ceiling. A user or display setting can never promote it.

| Class | Required conditions | Required presentation |
| --- | --- | --- |
| `AS-0 Unverified` | Required verification is incomplete, not run, errored, or failed; output was changed after verification; lineage is missing; or mandatory processing was bypassed. | Red/error state, no privacy claim. Sharing requires explicit acknowledgement where allowed and must never appear as verified. |
| `AS-1 Re-encoded Derivative` | A new file was encoded and metadata rescanned, but substantial source-pixel dependency remains. | “Source pixels retained.” Present only as convenience/experimental transformation with mandatory warning. |
| `AS-2 Reviewed Canonical Text` | Final text came from the approved Canonical Document; final Unicode and URL checks passed; ambiguities were reviewed or explicitly retained. | “Canonical text generated and verified.” |
| `AS-3 Rebuilt Image with Source Regions` | Text/UI primitives were freshly rendered; retained source regions are all approved and listed; metadata and exact-output OCR round-trip completed. | “Image rebuilt; listed source regions retained.” |
| `AS-4 Fully Rebuilt Textual Image` | All visible text came from approved canonical text; all primitives came from bundled renderer assets; no source image region remains; exact output was reopened, metadata scanned, and OCR round-trip checked; findings are resolved or explicitly accepted. | “Fully rebuilt from reviewed content.” |

### Assurance ceilings and downgrades

- Any derivative block imposes the derivative ceiling; a saved derivative remains under it.
- Any retained source-pixel region prevents AS-4 and requires complete declared lineage.
- Persistence, preview generation, timer display, waiting-target changes, sharing, and export preparation do not raise assurance.
- Saved Result revalidation may maintain or downgrade the original class but never exceed it.
- A source region added later, unknown/optional transform after verification, edit to a Managed Artifact, final rescan failure, OCR divergence, skipped mandatory block, missing ledger entry, revision mismatch, unexpected metadata, source reference, undeclared dependency, corrupt/migrated artifact, or unknown state downgrades or blocks assurance. An edited External Copy is simply outside managed assurance; it does not downgrade the unchanged authoritative Managed Artifact.
- Partial deletion, integrity mismatch, incomplete transaction, orphan/migration mismatch, or stale/unknown integrity quarantines the result and blocks verified sharing.
- `NOT_RUN`, `FAIL`, and `ERROR` on a required verifier prevent a verified class. `PASS_WITH_DECLARED_RESIDUAL` is valid only when the residual is explicit, approved, and reflected in the ceiling/report.
- Assurance badges apply only to exact Managed Artifact bytes/text controlled by the app. External Copies do not retain managed status.

## Residual risks and non-goals

Canonical Share does not prevent account, network, device, timing, behavioral, social-graph, or recipient-side correlation. It cannot reconstruct arbitrary photographs perfectly, detect every proprietary watermark, prove hidden information absent, preserve all source styling at high assurance, or replace legal and operational protections. It is not a secure whistleblowing transport or anonymous network. Default processing remains local, but local persistence creates retention risk that users must be able to manage through immediate logical deletion.

## Source references

- Baseline: trust boundary §§2.3; threat model §3; assurance §4; pipeline §§7–9; verification §12; acceptance §14; prohibited patterns §19; release checklist §20.
- Amendment 1: product decision A1; Saved Results UX A2; Reference Timer A3; data/storage A4/A6; persistent blocks A5; sharing A7; errors A9; acceptance/tests A10–A11.
- Amendment 2: precedence and terminology; language B1; deletion B2; timer B3; managed assurance B4; sharing/cache B5; provider snapshot/delay B6; traces B7; dependency scope B8; durable write B9; completion order B10; acceptance B11.
