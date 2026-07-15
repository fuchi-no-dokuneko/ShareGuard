# Dependency review

This record is required by baseline section 15.5. Versions are pinned in
`gradle/libs.versions.toml`; dynamic versions are prohibited. Runtime dependencies are provisional until
the release-size column is replaced with measurements from a clean release APK/AAB. No dependency may
add an Android network permission or perform runtime model/data downloads in the default build.

## Runtime dependency decisions

| Component | Purpose and official project | Licence / maintenance | Android and offline behavior | Data and transitive-network review | Size evidence / alternatives / removal plan |
|---|---|---|---|---|---|
| AndroidX Core, Activity, Lifecycle, Navigation | Platform-supported lifecycle, activity result, Compose host and navigation APIs; Android Developers / AndroidX | Apache-2.0; actively maintained stable AndroidX releases | API 23+, fully local | No content transmission; no Internet permission | Measure in release bundle. Replacing these would mean lower-level Android framework code and is not preferred. Remove individual integrations if no longer used. |
| Jetpack Compose BOM, UI, Foundation, Material 3 | Kotlin UI and accessible platform semantics; Android Developers / AndroidX | Apache-2.0; actively maintained | API 23+, fully local | No content transmission; tooling artifacts are debug-only | Measure optimized DEX/resources. Required by the product UI decision; remove unused icon/tooling artifacts before release. |
| Room | Transactional Saved Result metadata, schema export and migration validation; AndroidX Room | Apache-2.0; stable 2.8.4 | API 23+, local SQLite | No content transmission; KSP compiler is build-time only | Measure runtime/Dex. Preferred over custom SQL schema/mapper code. Repository interface permits replacement without changing domain models. |
| WorkManager | Retryable integrity and stale-cache sweeps that survive ordinary process loss; AndroidX Work | Apache-2.0; actively maintained | API 23+, local work with no network constraint | No content leaves app; no network constraint or permission | Measure runtime/Dex. Use direct startup cleanup for nonpersistent work; remove WorkManager only if lifecycle evidence proves it unnecessary. |
| DataStore Preferences | Nonsensitive UI preferences such as preview visibility/list mode; AndroidX DataStore | Apache-2.0; actively maintained | API 23+, app-private local files | Never stores artifacts, source content, labels, timing linked to content, or destinations | Measure runtime/Dex. Could be replaced with a small Room preference table; remove if settings are moved there. |
| ExifInterface | Maintained orientation and known metadata access; AndroidX | Apache-2.0; actively maintained | API 23+, local streams | Does not transmit data | Measure runtime/Dex. Android framework metadata support is less consistent; keep behind `ImageMetadataReader`. |
| DocumentFile / Storage Access Framework | User-selected external copy export | Apache-2.0; actively maintained | API 23+, platform URI operations | Writes only after explicit user action; no destination history | Measure runtime/Dex. Can be removed in favor of raw `ContentResolver` when equivalent behavior is proven. |
| Kotlin coroutines | Structured cancellation and bounded background work | Apache-2.0; actively maintained by JetBrains | JVM/Android, local | No networking is initiated by the library | Measure runtime/Dex. Required for specified cancellation; interfaces use suspend/Flow so implementation can be replaced if needed. |
| Kotlin serialization JSON | Versioned canonical/preset/corpus configuration serialization | Apache-2.0; actively maintained | JVM/Android, local | Persistent serializers exclude sensitive transient values by model boundary | Measure runtime/Dex. Avoid reflection serializers; remove JSON fields through schema migration, never destructive fallback. |
| Kotlin immutable collections | Immutable execution-context collections | Apache-2.0; maintained JetBrains library | JVM/Android, local | No I/O | Measure runtime/Dex. Kotlin read-only lists are an alternative but do not enforce persistent immutability. |
| ICU4J | Unicode normalization, scripts, graphemes and SpoofChecker/confusables | ICU licence; maintained Unicode/ICU project | JVM/Android, bundled data, local | No runtime download or transmission | Expected to be a material size contributor; record AAB/APK split size. Android ICU is an alternative but varies by OS. Adapter permits migration when a minimum platform provides equivalent pinned behavior. |
| OkHttp `HttpUrl` and public suffix database | Maintained standards-based URL parser/serializer and registrable-domain boundary | Apache-2.0; actively maintained by Square | JVM/Android, used without constructing a client | Library contains networking capability but app has no Internet permission; only `HttpUrl`/PSL APIs are reachable from the URL module | Measure runtime/Dex and R8 removal. Android `Uri` lacks the required parsing/PSL guarantees. Keep dependency isolated so a maintained parser can replace it. |
| metadata-extractor | Independent container/metadata inventory across supported image formats | Apache-2.0; maintained Drew Noakes project | JVM/Android streams, local | No network behavior | Measure runtime/Dex. ExifInterface alone is not an independent broad inventory; remove format readers through R8/explicit exclusions if unsupported. |
| ML Kit bundled Text Recognition: Latin, Chinese, Devanagari, Japanese, Korean | Local OCR engines/models and script-specific adapters | Google ML Kit terms; maintained official ML Kit Android artifacts | API 23+; `com.google.mlkit:*` bundled models are statically delivered and available offline | Never use `com.google.android.gms:play-services-mlkit-*`; no model-download metadata/API and no Internet permission | Official estimate is roughly 4 MB per bundled script/architecture; replace with measured APK/AAB contribution. Each adapter/model can be removed if its script is no longer supported, but missing scripts must become explicit review limits rather than cloud fallback. |
| ML Kit bundled Barcode Scanning | Local QR/barcode detection and final re-scan | Google ML Kit terms; maintained official bundled artifact | API 23+, bundled/local | No dynamic model request; no Internet permission | Measure release contribution. ZXing is a maintained alternative; adapter boundary permits replacement after accuracy/licence review. |

## Build and test-only dependencies

| Component | Use | Distribution effect |
|---|---|---|
| Android Gradle Plugin 9.3.0 / Gradle 9.5.1 | Reproducible Android build, lint, R8 and bundle tasks | Build only; wrapper URL and SHA-256 are pinned. |
| Kotlin 2.3.21 / Compose compiler | Kotlin/JVM compilation and Compose transformation | Build only. Android modules use AGP built-in Kotlin; pure JVM modules use the pinned plugin. |
| KSP 2.3.10 / Room compiler | Generate Room implementation and schema JSON | Build only; generated schema is checked in and migration-tested. |
| JUnit 4, Truth, Turbine, Robolectric | Deterministic unit, Flow and host Android tests | Test configurations only. |
| AndroidX Test, Espresso, Compose UI test, Work/Room testing | Managed-emulator instrumentation, migrations, URI/share and accessibility assertions | Test APK only. |

## Release gates

Before the first public release:

1. Run a clean release build and record compressed APK/AAB and installed/split contributions for ICU,
   each OCR model, barcode, metadata parsing, Room/WorkManager, and Compose.
2. Inspect the merged release manifest and fail if `INTERNET`, network-state, broad media/storage, backup,
   analytics, advertising, account, or dynamic-feature model-download declarations are present.
3. Generate the Gradle dependency report and CycloneDX-compatible SBOM; archive them with CI evidence.
4. Check resolved artefacts for known vulnerabilities and licence changes. A version bump needs a fresh
   offline/data/size review and the full relevant verifier suite.
5. Confirm R8 does not remove serializers, Room schema behavior, OCR adapters, or verification code and
   does remove unused networking/client paths.
