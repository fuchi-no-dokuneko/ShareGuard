# Canonical Share (ShareGuard)

Canonical Share is an offline-first Android application for turning one text or image source into a
reviewed canonical representation before sharing. It uses an ordered, typed pipeline: inspect, propose,
review semantic impact, transform, serialize, verify the exact result, reopen it, and only then commit an
encrypted app-private Saved Result.

The app does **not** promise anonymity, removal of every unknown watermark, physical flash erasure, or
continued integrity after another app receives or edits an exported copy. Its assurance applies only to
the exact Managed Artifact controlled by Canonical Share.

## Privacy boundary

- All OCR, barcode, Unicode, URL, image and verification processing is local.
- The default manifest has no Internet, network-state, broad storage or media-library permission.
- Incoming provider data is copied to a bounded app-private snapshot before acceptance.
- Sources, OCR views, detailed before-values and render intermediates are transient.
- Verified results are encrypted in app-private storage; Android backup and device-transfer extraction
  are disabled by default.
- Sharing uses Android's system Sharesheet and a scoped read-only content URI when an image is involved.
- Deletion removes app-addressable records, keys, artifacts, previews and caches, without claiming
  physical sanitization of flash storage or deletion of external copies.

See [the threat model](docs/threat-model.md), [privacy policy](docs/privacy-policy.md), and
[architecture decisions](docs/architecture-decisions.md) for the precise claim boundary.

## Build

Requirements:

- JDK 17 or newer (the project emits Java/JVM 17 bytecode)
- Android SDK platform 36 and build-tools 36.0.0
- no service account, network API key or downloaded runtime model

```bash
./gradlew test lintDebug :app:assembleDebug
```

Useful focused checks:

```bash
./gradlew :core:model:test :core:pipeline:test :test-corpus:test
./gradlew :app:processDebugMainManifest
bash scripts/ci/verify-backup-rules.sh \
  app/src/main/res/xml/backup_rules.xml \
  app/src/main/res/xml/data_extraction_rules.xml
```

Device-backed tests run on API 23 and API 36 managed emulators in GitHub Actions. The local project does
not require `adb` for unit, lint, compile, APK or bundle verification.

## Repository layout

- `core/model` — immutable domain, lineage, verification and Saved Result models
- `core/pipeline` — normative block catalog, presets, validation, execution and assurance
- `core/session`, `core/security`, `core/storage` — transient lifecycle, cryptography and durable storage
- `block/*` — text, URL, image, bundled OCR, rendering and exact-output verification
- `feature/*`, `core/ui`, `app` — accessible Compose UI and Android integration
- `test-corpus` — generated adversarial fixtures, convergence families, canaries and mutations
- `benchmark` — measurements that may inform thresholds but cannot silently create product claims

## Specification authority

The checked-in specifications are the sole product authority. Conflicts resolve in this order:

1. `canonical_share_android_implementation_spec_v1.amendment2.txt`
2. `canonical_share_android_implementation_spec_v1.ammend.txt`
3. `canonical_share_android_implementation_spec_v1.txt`

The [implementation plan](IMPLEMENTATION_PLAN.md) records the effective contract and verification matrix.
Third-party documentation is used only to select and validate maintained platform APIs and dependencies.

## Security reports

Use GitHub's private security-advisory flow. Do not attach real sensitive source text, images, URIs,
filenames, encryption material or destination details; use generated fixtures and content-free traces.
See [SECURITY.md](SECURITY.md).

No source-code license has been granted by this repository unless a later explicit `LICENSE` file says
otherwise. Third-party dependency and bundled-asset licenses remain their respective owners' licenses.
