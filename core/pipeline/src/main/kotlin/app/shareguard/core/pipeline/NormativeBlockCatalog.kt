package app.shareguard.core.pipeline

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.TransformationCategory
import app.shareguard.core.model.VerificationType

data class PipelineBlockRegistry(
    val descriptors: List<PipelineBlockMetadata>,
) {
    private val byId = descriptors.associateBy { it.blockId }

    init {
        require(descriptors.map { it.blockId }.distinct().size == descriptors.size) {
            "Registry block IDs must be unique"
        }
        require(descriptors.all { it.blockVersion.value > 0 }) { "Registry versions must be positive" }
    }

    fun find(blockId: BlockId): PipelineBlockMetadata? = byId[blockId]

    fun require(blockId: BlockId): PipelineBlockMetadata =
        find(blockId) ?: throw UnknownBlockException(blockId)

    fun require(reference: BlockReference): PipelineBlockMetadata {
        val descriptor = require(reference.blockId)
        if (descriptor.blockVersion != reference.blockVersion) {
            throw UnknownBlockVersionException(reference, descriptor.blockVersion)
        }
        return descriptor
    }

    fun withExternal(descriptor: PipelineBlockMetadata): PipelineBlockRegistry {
        require(!descriptor.builtIn) { "External registry additions must declare builtIn=false" }
        require(find(descriptor.blockId) == null) { "Cannot replace a normative built-in block" }
        return PipelineBlockRegistry(descriptors + descriptor)
    }
}

class UnknownBlockException(val blockId: BlockId) : IllegalArgumentException("Unknown block: ${blockId.value}")

class UnknownBlockVersionException(
    val reference: BlockReference,
    val knownVersion: BlockVersion,
) : IllegalArgumentException(
    "Unknown version ${reference.blockVersion.value} for ${reference.blockId.value}; known=${knownVersion.value}",
)

object NormativeBlockCatalog {
    val descriptors: List<PipelineBlockMetadata> by lazy { entries.map(::descriptor) }
    val registry: PipelineBlockRegistry by lazy { PipelineBlockRegistry(descriptors) }
    val exactIds: List<String> by lazy { entries.map { it.id } }

    private data class Entry(val id: String, val name: String)

    private val entries = listOf(
        Entry("SYS-001", "Create Ephemeral Session"),
        Entry("SYS-002", "Stale Session Purge"),
        Entry("IN-001", "Receive Android Text Share"),
        Entry("IN-002", "Receive Android Image Share"),
        Entry("IN-003", "Photo Picker Import"),
        Entry("IN-004", "Plain Text Entry"),
        Entry("IN-005", "Detect and Validate Source Type"),
        Entry("IN-006", "Resource Guard"),
        Entry("IN-007", "Source Snapshot Seal"),
        Entry("TXT-001", "Unicode Scalar Inventory"),
        Entry("TXT-002", "Grapheme and Normalization Analysis"),
        Entry("TXT-003", "Default-Ignorable Scan"),
        Entry("TXT-004", "Whitespace Taxonomy Scan"),
        Entry("TXT-005", "Confusable and Script Scan"),
        Entry("TXT-006", "Punctuation Variant Scan"),
        Entry("TXT-007", "Line and Paragraph Structure Scan"),
        Entry("TXT-008", "Language Context Resolver"),
        Entry("TXT-009", "Semantic Token Protection"),
        Entry("TXT-010", "Unicode Compatibility Normalization"),
        Entry("TXT-011", "Remove or Replace Approved Ignorables"),
        Entry("TXT-012", "Confusable Canonicalization"),
        Entry("TXT-013", "Whitespace Canonicalization"),
        Entry("TXT-014", "Punctuation Canonicalization"),
        Entry("TXT-015", "Viewport Wrap Removal"),
        Entry("TXT-016", "Rich Representation Strip"),
        Entry("TXT-017", "Canonical Text Lock"),
        Entry("URL-001", "URL Candidate Extraction"),
        Entry("URL-002", "Standards-Based URL Parse"),
        Entry("URL-003", "Hostname Canonicalization and Spoof Check"),
        Entry("URL-004", "Registrable Domain Resolution"),
        Entry("URL-005", "Query Parameter Inventory"),
        Entry("URL-006", "Path Token Inventory"),
        Entry("URL-007", "Subdomain Inventory"),
        Entry("URL-008", "Fragment and Userinfo Scan"),
        Entry("URL-009", "Redirect and Short-Link Warning"),
        Entry("URL-010", "Known Tracking Parameter Removal"),
        Entry("URL-011", "Strict Query and Fragment Removal"),
        Entry("URL-012", "Path Reduction"),
        Entry("URL-013", "Subdomain Reduction"),
        Entry("URL-014", "Canonical URL Serializer"),
        Entry("URL-015", "URL Functionality Review Gate"),
        Entry("IMG-001", "Image Header Probe"),
        Entry("IMG-002", "Controlled Decode"),
        Entry("IMG-003", "Orientation Materialization"),
        Entry("IMG-004", "Container Metadata Inventory"),
        Entry("IMG-005", "Embedded Thumbnail Scan"),
        Entry("IMG-006", "Colour and Alpha Model Inventory"),
        Entry("IMG-007", "Low-Bit-Plane Diagnostic"),
        Entry("IMG-008", "Frequency and Repetition Diagnostic"),
        Entry("IMG-009", "OCR View Generator"),
        Entry("IMG-010", "Primary OCR Engine"),
        Entry("IMG-011", "Secondary OCR Adapter"),
        Entry("IMG-012", "OCR Consensus Builder"),
        Entry("IMG-013", "Text Region Geometry"),
        Entry("IMG-014", "Reading Order Resolver"),
        Entry("IMG-015", "QR and Barcode Decoder"),
        Entry("IMG-016", "Non-Text Region Segmenter"),
        Entry("IMG-017", "Source Pixel Dependency Map"),
        Entry("IMG-018", "Platform UI Pattern Classifier"),
        Entry("CAN-001", "Canonical Document Builder"),
        Entry("CAN-002", "Canonical Schema Validator"),
        Entry("CAN-003", "Change Ledger Builder"),
        Entry("REV-001", "Invisible Character Review"),
        Entry("REV-002", "Confusable Character Review"),
        Entry("REV-003", "OCR Ambiguity Review"),
        Entry("REV-004", "URL Policy Review"),
        Entry("REV-005", "Reading Order Review"),
        Entry("REV-006", "Image Region Policy Review"),
        Entry("REV-007", "Semantic Diff Review"),
        Entry("REV-008", "Assurance Consequence Gate"),
        Entry("REN-001", "Fresh Canvas Allocator"),
        Entry("REN-002", "Bundled Font Resolver"),
        Entry("REN-003", "Deterministic Text Shaper"),
        Entry("REN-004", "Canonical Layout Renderer"),
        Entry("REN-005", "Structured Region Renderer"),
        Entry("REN-006", "Placeholder and Redaction Renderer"),
        Entry("REN-007", "Approved Source Region Import"),
        Entry("REN-008", "Alpha Flatten"),
        Entry("REN-009", "Canonical Colour Conversion"),
        Entry("REN-010", "Fresh Image Serializer"),
        Entry("REN-011", "Metadata Allowlist Writer"),
        Entry("OUT-TXT-001", "Canonical Plain-Text Serializer"),
        Entry("OUT-TXT-002", "Final Text MIME Packager"),
        Entry("OUT-IMG-001", "Secure Image Share Package"),
        Entry("OUT-BND-001", "Output Bundle Builder"),
        Entry("DER-001", "Derivative Resample"),
        Entry("DER-002", "Derivative Channel Canonicalization"),
        Entry("DER-003", "Derivative Quantization"),
        Entry("DER-004", "Ephemeral Stochastic Perturbation"),
        Entry("DER-005", "Derivative Fresh Serialize"),
        Entry("DER-006", "Mandatory Derivative Warning"),
        Entry("VER-001", "Executed Block Manifest Audit"),
        Entry("VER-002", "Canonical Revision Link Audit"),
        Entry("VER-003", "Final Metadata Re-scan"),
        Entry("VER-004", "Final Unicode Re-scan"),
        Entry("VER-005", "Final URL Re-scan"),
        Entry("VER-006", "OCR Round-Trip Verification"),
        Entry("VER-007", "Source URI and Filename Reference Audit"),
        Entry("VER-008", "Source Pixel Dependency Audit"),
        Entry("VER-009", "Machine-Readable Code Re-scan"),
        Entry("VER-010", "Visual Diff and Region Coverage"),
        Entry("VER-011", "Idempotence Check"),
        Entry("VER-012", "No-Network Runtime Check"),
        Entry("VER-013", "Sensitive Logging Audit"),
        Entry("VER-014", "Assurance Classifier"),
        Entry("VER-015", "Human-Readable Verification Report"),
        Entry("EXP-001", "Output Preview Gate"),
        Entry("EXP-002", "Android Sharesheet Launch"),
        Entry("EXP-003", "Export User-Selected Copy"),
        Entry("SYS-003", "Normal Session Purge"),
        Entry("SYS-004", "Share Grant Expiry Cleanup"),
        Entry("PST-001", "Create Import Time Anchor"),
        Entry("PST-002", "Persist Verified Result"),
        Entry("PST-003", "Build Safe Saved-Result Preview"),
        Entry("PST-004", "Revalidate Saved Result"),
        Entry("PST-005", "Managed Share from Saved Result"),
        Entry("PST-006", "Delete Saved Result"),
        Entry("PST-007", "Persistent Store Integrity Sweep"),
    )

    private fun descriptor(entry: Entry): PipelineBlockMetadata {
        val id = entry.id
        return PipelineBlockMetadata(
            blockId = BlockId(id),
            blockVersion = BlockVersion(1),
            displayName = entry.name,
            description = "Normative $id ${entry.name} block.",
            stage = stage(id),
            acceptedInputKinds = acceptedInputKinds(id),
            supportedOutputModes = supportedOutputModes(id),
            inputPredicate = inputPredicate(id),
            outputGuarantees = outputGuarantees(id),
            mandatory = id !in conditionalIds && id !in optionalIds,
            conditional = id in conditionalIds,
            deterministic = id !in nonDeterministicIds,
            requiresReview = id in reviewIds,
            settingsSchemaVersion = SchemaVersion(1),
            threatCoverage = threatCoverage(id),
            invalidationKeys = (invalidationKeys(id) + InvalidationKey.BLOCK_VERSION).distinct(),
            verificationRequirements = verificationRequirements(id),
            resourceClass = resourceClass(id),
            offlineCapability = if (id in platformMediatedIds) {
                OfflineCapability.PLATFORM_MEDIATED
            } else {
                OfflineCapability.OFFLINE
            },
            transformationCategory = transformationCategory(id),
            contentTransforming = isContentTransforming(id),
            finalSerialization = id in finalSerializationIds,
            verificationBlock = id.startsWith("VER-"),
            sourceBlock = id in sourceIds,
            finalExportPreparation = id in finalExportPreparationIds,
            builtIn = true,
            persistentLoggingAllowed = false,
        )
    }

    private fun stage(id: String): PipelineStage = when {
        id == "SYS-001" || id == "SYS-002" -> PipelineStage.SESSION
        id == "SYS-003" || id == "SYS-004" -> PipelineStage.LIFECYCLE
        id.startsWith("IN-") -> PipelineStage.INPUT
        id.startsWith("TXT-") && id.number() <= 9 -> PipelineStage.INSPECTION
        id.startsWith("TXT-") -> PipelineStage.NORMALIZATION
        id.startsWith("URL-") && id.number() <= 9 -> PipelineStage.INSPECTION
        id == "URL-015" -> PipelineStage.REVIEW
        id.startsWith("URL-") -> PipelineStage.NORMALIZATION
        id.startsWith("IMG-") -> PipelineStage.INSPECTION
        id.startsWith("CAN-") -> PipelineStage.CANONICALIZATION
        id.startsWith("REV-") -> PipelineStage.REVIEW
        id.startsWith("REN-") && id.number() >= 10 -> PipelineStage.SERIALIZATION
        id.startsWith("REN-") -> PipelineStage.RENDERING
        id == "OUT-TXT-001" || id == "OUT-BND-001" -> PipelineStage.SERIALIZATION
        id.startsWith("OUT-") -> PipelineStage.EXPORT
        id.startsWith("DER-") -> PipelineStage.DERIVATIVE
        id.startsWith("VER-") -> PipelineStage.VERIFICATION
        id.startsWith("EXP-") -> PipelineStage.EXPORT
        id == "PST-001" -> PipelineStage.INPUT
        id in setOf("PST-002", "PST-003", "PST-004", "PST-007") -> PipelineStage.PERSISTENCE
        id == "PST-005" -> PipelineStage.EXPORT
        id == "PST-006" -> PipelineStage.LIFECYCLE
        else -> error("No stage for $id")
    }

    private fun acceptedInputKinds(id: String): List<InputKind> = when {
        id in setOf("IN-001", "IN-004") -> listOf(InputKind.TEXT)
        id in setOf("IN-002", "IN-003", "IN-007") -> listOf(InputKind.IMAGE)
        id.startsWith("IMG-") || id.startsWith("DER-") -> listOf(InputKind.IMAGE)
        else -> InputKind.entries
    }

    private fun supportedOutputModes(id: String): List<OutputMode> = when {
        id.startsWith("DER-") -> listOf(OutputMode.DERIVATIVE_IMAGE)
        id.startsWith("REN-") -> listOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH)
        id.startsWith("OUT-TXT-") -> listOf(OutputMode.TEXT, OutputMode.BOTH)
        id.startsWith("OUT-IMG-") -> listOf(
            OutputMode.REBUILT_IMAGE,
            OutputMode.BOTH,
            OutputMode.DERIVATIVE_IMAGE,
        )
        else -> OutputMode.entries
    }

    private fun inputPredicate(id: String): List<PipelineCapability> = when {
        id == "SYS-001" -> emptyList()
        id in sourceIds -> listOf(PipelineCapability.SESSION)
        id == "IN-005" -> listOf(PipelineCapability.SOURCE_IMPORTED)
        id == "IN-006" -> listOf(PipelineCapability.SOURCE_TYPED)
        id == "IN-007" -> listOf(PipelineCapability.RESOURCE_APPROVED)
        id == "PST-001" -> listOf(PipelineCapability.RESOURCE_APPROVED)
        id == "CAN-001" -> listOf(PipelineCapability.TEXT_INSPECTED)
        id == "CAN-002" -> listOf(PipelineCapability.CANONICAL_DOCUMENT)
        id == "CAN-003" -> listOf(PipelineCapability.CANONICAL_DOCUMENT)
        id == "TXT-017" -> listOf(
            PipelineCapability.CANONICAL_DOCUMENT_APPROVED,
            PipelineCapability.CHANGE_LEDGER,
        )
        id.startsWith("REN-") -> listOf(PipelineCapability.CANONICAL_TEXT_LOCKED)
        id == "OUT-TXT-001" -> listOf(PipelineCapability.CANONICAL_TEXT_LOCKED)
        id == "OUT-TXT-002" -> listOf(PipelineCapability.TEXT_ARTIFACT)
        id == "OUT-IMG-001" -> listOf(PipelineCapability.IMAGE_ARTIFACT)
        id == "OUT-BND-001" -> emptyList()
        id.startsWith("VER-") -> listOf(PipelineCapability.OUTPUT_BUNDLE)
        id == "PST-002" -> listOf(
            PipelineCapability.FINAL_VERIFICATION,
            PipelineCapability.ASSURANCE_COMPUTED,
            PipelineCapability.VERIFICATION_REPORT,
            PipelineCapability.IMPORT_ANCHOR,
        )
        id == "PST-003" -> listOf(PipelineCapability.SAVED_RESULT)
        id == "PST-005" -> listOf(PipelineCapability.SAVED_RESULT)
        id == "EXP-002" -> listOf(PipelineCapability.SHARE_READY)
        else -> emptyList()
    }

    private fun outputGuarantees(id: String): List<PipelineCapability> = when {
        id == "SYS-001" -> listOf(PipelineCapability.SESSION)
        id in sourceIds -> listOf(PipelineCapability.SOURCE_IMPORTED)
        id == "IN-005" -> listOf(PipelineCapability.SOURCE_TYPED)
        id == "IN-006" -> listOf(PipelineCapability.RESOURCE_APPROVED)
        id == "IN-007" -> listOf(PipelineCapability.SOURCE_SEALED)
        id == "PST-001" -> listOf(PipelineCapability.IMPORT_ANCHOR)
        id.startsWith("TXT-") && id.number() <= 9 -> listOf(PipelineCapability.TEXT_INSPECTED)
        id == "IMG-012" -> listOf(PipelineCapability.IMAGE_INSPECTED, PipelineCapability.TEXT_INSPECTED)
        id.startsWith("IMG-") -> listOf(PipelineCapability.IMAGE_INSPECTED)
        id == "URL-002" -> listOf(PipelineCapability.URLS_PARSED)
        id == "CAN-001" -> listOf(PipelineCapability.CANONICAL_DOCUMENT)
        id == "CAN-003" -> listOf(PipelineCapability.CHANGE_LEDGER)
        id == "REV-008" -> listOf(PipelineCapability.CANONICAL_DOCUMENT_APPROVED)
        id == "TXT-017" -> listOf(PipelineCapability.CANONICAL_TEXT_LOCKED)
        id == "REN-010" -> listOf(PipelineCapability.IMAGE_ARTIFACT)
        id == "DER-005" -> listOf(PipelineCapability.DERIVATIVE_ARTIFACT)
        id == "OUT-TXT-001" -> listOf(PipelineCapability.TEXT_ARTIFACT)
        id == "OUT-IMG-001" -> listOf(PipelineCapability.IMAGE_ARTIFACT)
        id == "OUT-BND-001" -> listOf(PipelineCapability.OUTPUT_BUNDLE)
        id == "VER-014" -> listOf(PipelineCapability.ASSURANCE_COMPUTED)
        id == "VER-015" -> listOf(
            PipelineCapability.FINAL_VERIFICATION,
            PipelineCapability.VERIFICATION_REPORT,
        )
        id == "PST-002" -> listOf(PipelineCapability.SAVED_RESULT)
        id == "PST-003" -> listOf(PipelineCapability.SAVED_PREVIEW)
        id == "PST-005" -> listOf(PipelineCapability.SHARE_READY)
        id == "EXP-003" -> listOf(PipelineCapability.EXTERNAL_EXPORT_READY)
        else -> emptyList()
    }

    private fun threatCoverage(id: String): List<String> = when {
        id.startsWith("SYS-") -> listOf("RESIDUAL_LOCAL_DATA", "SESSION_ISOLATION")
        id.startsWith("IN-") -> listOf("UNTRUSTED_INPUT", "PROVIDER_MUTATION", "RESOURCE_EXHAUSTION")
        id.startsWith("TXT-") -> listOf("UNICODE_PROVENANCE", "SEMANTIC_CHANGE", "CROSS_SAMPLE_MARKERS")
        id.startsWith("URL-") -> listOf("URL_TRACKING", "DECEPTIVE_DESTINATION", "FUNCTIONAL_BREAKAGE")
        id.startsWith("IMG-") -> listOf("IMAGE_PROVENANCE", "HIDDEN_IMAGE_CONTENT", "RESOURCE_EXHAUSTION")
        id.startsWith("CAN-") -> listOf("UNACCOUNTED_CHANGE", "REVISION_DIVERGENCE")
        id.startsWith("REV-") -> listOf("UNREVIEWED_SEMANTIC_CHANGE", "MISLEADING_ASSURANCE")
        id.startsWith("REN-") -> listOf("SOURCE_PIXEL_RETENTION", "RENDERER_FINGERPRINT", "METADATA_LEAKAGE")
        id.startsWith("OUT-") -> listOf("WRONG_ARTIFACT_EXPORT", "MIME_CONFUSION", "SOURCE_REFERENCE_LEAKAGE")
        id.startsWith("DER-") -> listOf("DERIVATIVE_LIMITATION", "SOURCE_PIXEL_RETENTION")
        id.startsWith("VER-") -> listOf("VERIFICATION_BYPASS", "STALE_ASSURANCE")
        id.startsWith("EXP-") -> listOf("SHARE_PERMISSION_OVERREACH", "WRONG_ARTIFACT_EXPORT")
        id.startsWith("PST-") -> listOf("PERSISTENT_SOURCE_LEAKAGE", "STORAGE_INCONSISTENCY", "STALE_ASSURANCE")
        else -> error("No threat coverage for $id")
    }

    private fun invalidationKeys(id: String): List<InvalidationKey> = when {
        id.startsWith("IN-") || id.startsWith("IMG-") -> listOf(InvalidationKey.SOURCE)
        id.startsWith("TXT-") -> listOf(
            InvalidationKey.SOURCE,
            InvalidationKey.LANGUAGE_POLICY,
            InvalidationKey.CANONICAL_DOCUMENT,
        )
        id.startsWith("URL-") -> listOf(InvalidationKey.SOURCE, InvalidationKey.URL_POLICY)
        id.startsWith("CAN-") || id.startsWith("REV-") -> listOf(
            InvalidationKey.CANONICAL_DOCUMENT,
            InvalidationKey.CANONICAL_REVISION,
        )
        id.startsWith("REN-") -> listOf(
            InvalidationKey.CANONICAL_DOCUMENT,
            InvalidationKey.RENDERER_SETTINGS,
            InvalidationKey.SOURCE_DEPENDENCY_MAP,
        )
        id.startsWith("OUT-") -> listOf(
            InvalidationKey.CANONICAL_REVISION,
            InvalidationKey.ARTIFACT_BYTES,
            InvalidationKey.OUTPUT_MODE,
        )
        id.startsWith("DER-") -> listOf(InvalidationKey.SOURCE, InvalidationKey.RENDERER_SETTINGS)
        id.startsWith("VER-") -> listOf(
            InvalidationKey.ARTIFACT_BYTES,
            InvalidationKey.CANONICAL_REVISION,
            InvalidationKey.ASSURANCE_POLICY,
        )
        id.startsWith("EXP-") -> listOf(
            InvalidationKey.ARTIFACT_BYTES,
            InvalidationKey.SAVE_FILENAME,
            InvalidationKey.SHARE_POLICY,
        )
        id.startsWith("PST-") -> listOf(
            InvalidationKey.ARTIFACT_BYTES,
            InvalidationKey.STORAGE_MIGRATION,
        )
        else -> listOf(InvalidationKey.SOURCE)
    }

    private fun verificationRequirements(id: String): List<VerificationType> = when {
        id.startsWith("VER-") -> listOf(verifierType(id))
        id.startsWith("TXT-") -> listOf(VerificationType.FINAL_UNICODE)
        id.startsWith("URL-") -> listOf(VerificationType.FINAL_URL)
        id.startsWith("IMG-") -> listOf(VerificationType.SOURCE_PIXEL_DEPENDENCY)
        id.startsWith("REN-") -> listOf(
            VerificationType.FINAL_METADATA,
            VerificationType.SOURCE_PIXEL_DEPENDENCY,
        )
        id.startsWith("DER-") -> listOf(
            VerificationType.FINAL_METADATA,
            VerificationType.SOURCE_PIXEL_DEPENDENCY,
        )
        id.startsWith("CAN-") || id.startsWith("REV-") -> listOf(VerificationType.CANONICAL_REVISION_LINK)
        id.startsWith("OUT-") || id.startsWith("EXP-") -> listOf(
            VerificationType.CANONICAL_REVISION_LINK,
            VerificationType.SOURCE_REFERENCE,
        )
        id.startsWith("PST-") -> listOf(VerificationType.PERSISTENT_REOPEN_AND_DIGEST)
        else -> listOf(VerificationType.EXECUTED_BLOCK_MANIFEST)
    }

    private fun verifierType(id: String): VerificationType = when (id) {
        "VER-001" -> VerificationType.EXECUTED_BLOCK_MANIFEST
        "VER-002" -> VerificationType.CANONICAL_REVISION_LINK
        "VER-003" -> VerificationType.FINAL_METADATA
        "VER-004" -> VerificationType.FINAL_UNICODE
        "VER-005" -> VerificationType.FINAL_URL
        "VER-006" -> VerificationType.OCR_ROUND_TRIP
        "VER-007" -> VerificationType.SOURCE_REFERENCE
        "VER-008" -> VerificationType.SOURCE_PIXEL_DEPENDENCY
        "VER-009" -> VerificationType.MACHINE_READABLE_CODE
        "VER-010" -> VerificationType.VISUAL_REGION_COVERAGE
        "VER-011" -> VerificationType.IDEMPOTENCE
        "VER-012" -> VerificationType.NO_NETWORK_RUNTIME
        "VER-013" -> VerificationType.SENSITIVE_LOGGING
        "VER-014" -> VerificationType.ASSURANCE_CLASSIFIER
        "VER-015" -> VerificationType.HUMAN_READABLE_REPORT
        else -> error("Unknown verifier $id")
    }

    private fun resourceClass(id: String): ResourceClass = when {
        id.startsWith("REV-") || id == "URL-015" || id == "DER-006" -> ResourceClass.USER_INTERACTION
        id.startsWith("IMG-") || id.startsWith("REN-") || id.startsWith("DER-") -> ResourceClass.CPU
        id.startsWith("IN-") || id.startsWith("SYS-") || id.startsWith("PST-") -> ResourceClass.IO
        id.startsWith("EXP-") || id.startsWith("OUT-IMG-") -> ResourceClass.PLATFORM
        else -> ResourceClass.LIGHT
    }

    private fun transformationCategory(id: String): TransformationCategory? = when {
        id.startsWith("IN-") -> TransformationCategory.IMPORT
        id.startsWith("TXT-") && id.number() >= 10 -> TransformationCategory.TEXT_NORMALIZATION
        id.startsWith("URL-") && id.number() in 10..14 -> TransformationCategory.URL_TRANSFORMATION
        id == "CAN-001" -> TransformationCategory.CANONICAL_DOCUMENT
        id.startsWith("REN-") -> TransformationCategory.IMAGE_RENDER
        id.startsWith("DER-") && id != "DER-006" -> TransformationCategory.DERIVATIVE
        id.startsWith("OUT-") -> TransformationCategory.SERIALIZATION
        id.startsWith("VER-") -> TransformationCategory.VERIFICATION
        id.startsWith("PST-") -> when (id) {
            "PST-005" -> TransformationCategory.SHARE_PREPARATION
            "PST-006" -> TransformationCategory.DELETION
            else -> TransformationCategory.PERSISTENCE
        }
        id.startsWith("EXP-") -> TransformationCategory.SHARE_PREPARATION
        else -> null
    }

    private fun isContentTransforming(id: String): Boolean =
        (id.startsWith("TXT-") && id.number() in 10..16) ||
            (id.startsWith("URL-") && id.number() in 10..14) ||
            id == "CAN-001" ||
            id.startsWith("REN-") ||
            (id.startsWith("DER-") && id != "DER-006") ||
            id in setOf("OUT-TXT-001", "OUT-BND-001")

    private fun String.number(): Int = substringAfterLast('-').toInt()

    private val sourceIds = setOf("IN-001", "IN-002", "IN-003", "IN-004")
    private val conditionalIds = setOf(
        "URL-004", "URL-006", "URL-007", "URL-015",
        "IMG-007", "IMG-008", "IMG-011", "IMG-018",
        "REV-001", "REV-002", "REV-003", "REV-004", "REV-005",
        "REN-005", "REN-006", "REN-007",
        "DER-003", "DER-004",
        "VER-005", "VER-009", "VER-012", "VER-013",
        "PST-004", "PST-005", "PST-006",
    )
    private val optionalIds = setOf("EXP-003", "PST-003")
    private val reviewIds = setOf(
        "TXT-012", "TXT-015",
        "URL-011", "URL-012", "URL-013", "URL-015",
        "REV-001", "REV-002", "REV-003", "REV-004", "REV-005", "REV-006", "REV-007", "REV-008",
        "REN-007", "DER-006", "EXP-001",
    )
    private val nonDeterministicIds = setOf(
        "SYS-002", "SYS-003", "SYS-004",
        "IN-001", "IN-002", "IN-003", "IN-004",
        "IMG-007", "IMG-008", "IMG-010", "IMG-011", "IMG-018",
        "DER-004", "EXP-002",
        "PST-001", "PST-002", "PST-005", "PST-006", "PST-007",
    )
    private val platformMediatedIds = setOf(
        "IN-001", "IN-002", "IN-003", "EXP-002", "EXP-003", "PST-005",
    )
    private val finalSerializationIds = setOf("TXT-017", "REN-010", "DER-005", "OUT-TXT-001", "OUT-BND-001")
    private val finalExportPreparationIds = setOf("PST-005", "EXP-003")
}
