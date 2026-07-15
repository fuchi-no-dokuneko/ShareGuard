package app.shareguard.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class InputKind { TEXT, IMAGE }

@Serializable
enum class OutputMode { TEXT, REBUILT_IMAGE, BOTH, DERIVATIVE_IMAGE }

@Serializable
enum class AssuranceClass(val level: Int) {
    AS_0_UNVERIFIED(0),
    AS_1_REENCODED_DERIVATIVE(1),
    AS_2_REVIEWED_CANONICAL_TEXT(2),
    AS_3_REBUILT_WITH_SOURCE_REGIONS(3),
    AS_4_FULLY_REBUILT_TEXTUAL_IMAGE(4);

    fun isAtMost(ceiling: AssuranceClass): Boolean = level <= ceiling.level
    fun lowerOf(other: AssuranceClass): AssuranceClass = if (level <= other.level) this else other
}

@Serializable
enum class ImportMethod { ANDROID_SHARE, PHOTO_PICKER, CLIPBOARD_PASTE, DIRECT_ENTRY }

@Serializable
enum class Severity { INFORMATIONAL, LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
enum class ConfidenceClass {
    CERTAIN_BY_PARSER,
    STRONG_HEURISTIC,
    WEAK_HEURISTIC,
    OCR_DISAGREEMENT,
    UNKNOWN,
}

@Serializable
enum class FindingStatus { DETECTED, REVIEW_REQUIRED, ACCEPTED, CHANGED, RETAINED, RESOLVED, DISMISSED }

@Serializable
enum class SemanticRisk { NONE, LOW, POSSIBLE_MEANING_CHANGE, HIGH_IMPACT }

@Serializable
enum class FindingCategory {
    FONT,
    SPATIAL,
    FREQUENCY,
    LOW_BIT_PLANE,
    ALPHA,
    UNICODE,
    CONFUSABLE,
    PUNCTUATION,
    METADATA,
    URL,
    LAYOUT,
    SEMANTIC,
    BARCODE,
    IMAGE_REGION,
    FILE_CONTAINER,
    CROSS_SAMPLE,
    RESOURCE,
    STORAGE,
    TIMING,
    INTEGRITY,
    UNKNOWN,
}

@Serializable
enum class DecisionAction {
    ACCEPT_PROPOSED_CHANGE,
    RETAIN_SOURCE_MEANING,
    MANUAL_EDIT,
    MARK_EXPECTED_LANGUAGE,
    EXCLUDE_REGION,
    KEEP_FULL_URL,
    REMOVE_KNOWN_TRACKING,
    REMOVE_QUERY_AND_FRAGMENT,
    KEEP_ORIGIN_ONLY,
    MAKE_NON_CLICKABLE,
    REMOVE_URL,
    REORDER,
    MERGE,
    SPLIT,
    MARK_DECORATIVE,
    MARK_HIDDEN,
    LOCK_EXACT_WORDING,
    ACCEPT_LOWER_ASSURANCE,
}

@Serializable
enum class DecisionStatus { PENDING, APPROVED, REJECTED, SUPERSEDED }

@Serializable
enum class SemanticImpact { NONE, POSSIBLE, CONFIRMED }

@Serializable
enum class ReviewStatus { NOT_REQUIRED, PENDING, APPROVED, REJECTED }

@Serializable
enum class ScriptCode {
    LATIN,
    GREEK,
    CYRILLIC,
    ARABIC,
    HEBREW,
    DEVANAGARI,
    HAN,
    HANGUL,
    KANA,
    COMMON,
    INHERITED,
    OTHER,
}

@Serializable
enum class SemanticRole {
    BODY,
    HEADING,
    QUOTE,
    LIST_ITEM,
    MESSAGE,
    CODE,
    TABLE_CELL,
    LINK,
    LABEL,
    REDACTION_LABEL,
    UNKNOWN,
}

@Serializable
enum class EmphasisRole { NONE, EMPHASIS, STRONG, CODE, UNDERLINE }

@Serializable
enum class UrlPolicy {
    KEEP_FULL,
    REMOVE_KNOWN_TRACKING,
    REMOVE_QUERY_AND_FRAGMENT,
    REDUCE_PATH,
    KEEP_ORIGIN_ONLY,
    REDUCE_SUBDOMAIN,
    NON_CLICKABLE_DOMAIN,
    REMOVE,
    MANUAL,
}

@Serializable
enum class ImageRegionType {
    PHOTOGRAPH,
    AVATAR,
    ICON,
    LOGO,
    MAP,
    CHART,
    BACKGROUND,
    DECORATION,
    QR_CODE,
    BARCODE,
    UNKNOWN,
}

@Serializable
enum class ImageRegionPolicy {
    REPLACE_WITH_PLACEHOLDER,
    SOLID_REDACT,
    CROP,
    REBUILD_FROM_STRUCTURED_DATA,
    RETAIN_SOURCE_PIXELS,
    REMOVE,
}

@Serializable
enum class LayoutKind { PLAIN_DOCUMENT, ARTICLE, MESSAGE_THREAD, GENERIC_CARD, TABLE_LIKE, MANUAL }

@Serializable
enum class MessageRole { AUTHOR, RECIPIENT, SYSTEM, UNKNOWN }

@Serializable
enum class DependencyType {
    RETAINED_SOURCE_PIXELS,
    OCR_DERIVED_TEXT,
    SOURCE_DERIVED_LAYOUT,
    RETAINED_SOURCE_METADATA,
    BUNDLED_ASSET,
    RENDERER_GENERATED_PRIMITIVE,
    USER_DECISION,
    CANONICAL_DOCUMENT_REVISION,
}

@Serializable
enum class DependencyOrigin { SOURCE, BUNDLED, GENERATED, USER_DECISION, CANONICAL_DOCUMENT }

@Serializable
enum class ArtifactKind { CANONICAL_TEXT, REBUILT_IMAGE, DERIVATIVE_IMAGE }

@Serializable
enum class VerificationStatus {
    PASS,
    PASS_WITH_DECLARED_RESIDUAL,
    REVIEW_REQUIRED,
    NOT_APPLICABLE,
    NOT_RUN,
    FAIL,
    ERROR,
}

@Serializable
enum class VerificationType {
    EXECUTED_BLOCK_MANIFEST,
    CANONICAL_REVISION_LINK,
    FINAL_METADATA,
    FINAL_UNICODE,
    FINAL_URL,
    OCR_ROUND_TRIP,
    SOURCE_REFERENCE,
    SOURCE_PIXEL_DEPENDENCY,
    MACHINE_READABLE_CODE,
    VISUAL_REGION_COVERAGE,
    IDEMPOTENCE,
    NO_NETWORK_RUNTIME,
    SENSITIVE_LOGGING,
    ASSURANCE_CLASSIFIER,
    HUMAN_READABLE_REPORT,
    PERSISTENT_REOPEN_AND_DIGEST,
}

@Serializable
enum class ExecutionLifecycleState {
    CREATED,
    IMPORTED,
    RUNNING,
    REVIEW_REQUIRED,
    VERIFIED,
    PERSISTING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

@Serializable
enum class BlockExecutionStatus {
    WAITING,
    READY,
    RUNNING,
    REVIEW_REQUIRED,
    BLOCKED,
    DONE_NO_CHANGE,
    DONE_CHANGED,
    SKIPPED_BY_POLICY,
    FAILED_RECOVERABLE,
    FAILED_FATAL,
    INVALIDATED,
}

@Serializable
enum class BlockPhase { VALIDATE, INSPECT, PLAN, REVIEW, APPLY, SELF_CHECK, COMMIT, CLEANUP }

@Serializable
enum class TraceOutcome { SUCCESS, REVIEW_REQUIRED, RECOVERABLE_FAILURE, FATAL_FAILURE }

@Serializable
enum class TransformationCategory {
    IMPORT,
    TEXT_NORMALIZATION,
    URL_TRANSFORMATION,
    CANONICAL_DOCUMENT,
    IMAGE_RENDER,
    DERIVATIVE,
    SERIALIZATION,
    VERIFICATION,
    PERSISTENCE,
    SHARE_PREPARATION,
    DELETION,
}

@Serializable
enum class BoundedDelayPurpose { PROVIDER_SNAPSHOT_RECHECK, OPTIONAL_PRE_SHARE_JITTER }

@Serializable
enum class VerificationLayer { STRUCTURAL, CONTENT, CONTAINER, PROVENANCE, PRIVACY_ENGINEERING }

@Serializable
enum class ImportClockConfidence {
    MONOTONIC_ACTIVE,
    WALL_CLOCK_RESTORED,
    CLOCK_CHANGE_DETECTED,
    UNKNOWN_LEGACY_IMPORT_TIME,
}

@Serializable
enum class IntegrityState { PENDING, VALID, STALE, UNKNOWN, INVALID }

@Serializable
enum class VerificationState { PENDING, VERIFIED, REVALIDATION_REQUIRED, FAILED, INVALIDATED }

@Serializable
enum class MigrationState { CURRENT, PENDING, IN_PROGRESS, REVALIDATION_REQUIRED, FAILED }

@Serializable
enum class SavedResultLifecycleState {
    COMMITTING,
    AVAILABLE,
    QUARANTINED,
    DELETION_PENDING,
    DELETED,
    INVALIDATED,
}
