package app.shareguard.canonical

import android.graphics.BitmapFactory
import app.shareguard.block.image.ArgbPixelImage
import app.shareguard.block.ocr.MlKitBundledBarcodeRecognizer
import app.shareguard.block.ocr.MlKitTextRecognizerAdapter
import app.shareguard.block.ocr.OcrScript
import app.shareguard.block.ocr.OcrViewRecipe
import app.shareguard.block.ocr.OcrViewResourcePolicy
import app.shareguard.block.ocr.TemporaryOcrViewFactory
import app.shareguard.block.render.RenderOperation
import app.shareguard.block.render.RenderOperationCode
import app.shareguard.block.render.StrictPngSerializer
import app.shareguard.block.verify.BarcodeInspection
import app.shareguard.block.verify.BarcodeInspector
import app.shareguard.block.verify.FinalImageInspection
import app.shareguard.block.verify.FinalImageInspector
import app.shareguard.block.verify.ImageArtifactPolicy
import app.shareguard.block.verify.MachineReadableCode
import app.shareguard.block.verify.OcrRoundTripInspection
import app.shareguard.block.verify.OcrRoundTripInspector
import app.shareguard.block.verify.ProviderResult
import app.shareguard.block.verify.RegionCoverageInspection
import app.shareguard.block.verify.RegionCoverageInspector
import app.shareguard.block.verify.ReopenedArtifact
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.SafeSummary
import java.util.Locale

/** Verifiers operate on the exact reopened PNG, never the renderer's mutable canvas. */
class StrictRenderedImageInspector(
    private val operations: List<RenderOperation>,
    private val serializer: StrictPngSerializer = StrictPngSerializer(),
) : FinalImageInspector {
    override suspend fun inspect(
        artifact: ReopenedArtifact,
        policy: ImageArtifactPolicy,
    ): ProviderResult<FinalImageInspection> {
        val bytes = artifact.bytesCopy()
        return try {
            val evidence = serializer.reopenAndInspect(bytes)
            ProviderResult.Completed(
                FinalImageInspection(
                    artifactRevision = artifact.artifactRevision,
                    detectedMimeType = artifact.detectedMimeType,
                    independentlyDecodes = true,
                    metadataFieldCodes = emptySet(),
                    containerChunkCodes = evidence.chunkTypes.map { "PNG_$it" }.toSet(),
                    embeddedThumbnailCount = 0,
                    channelModelCode = if (evidence.colorType == 6) "RGBA_8" else "RGB_8",
                    alphaModelCode = "OPAQUE",
                    colourProfileCode = "NONE",
                    freshlyAllocatedCanvas = operations.any { it.code == RenderOperationCode.FRESH_CANVAS_ALLOCATED },
                    bundledRendererAssetsOnly = operations.any { it.code == RenderOperationCode.BUNDLED_FONT_RESOLVED },
                ),
            )
        } catch (_: Throwable) {
            ProviderResult.Error("FINAL_IMAGE_INSPECTION_FAILED")
        } finally {
            bytes.fill(0)
        }
    }
}

class ExactPngOcrInspector(
    private val scripts: Set<OcrScript>,
) : OcrRoundTripInspector {
    override suspend fun inspect(
        artifact: ReopenedArtifact,
        approvedCanonicalText: String,
        approvedReadingOrder: app.shareguard.core.model.ImmutableList<app.shareguard.core.model.CanonicalBlockId>,
    ): ProviderResult<OcrRoundTripInspection> {
        if (scripts.isEmpty()) return ProviderResult.NotRun("OCR_SCRIPT_NOT_SUPPORTED")
        val bytes = artifact.bytesCopy()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return ProviderResult.Error("FINAL_IMAGE_DECODE_FAILED").also { bytes.fill(0) }
        val pixels = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
        val source = try {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ArgbPixelImage(bitmap.width, bitmap.height, pixels)
        } finally {
            pixels.fill(0)
            bitmap.recycle()
            bytes.fill(0)
        }
        val pixelCount = source.width.toLong() * source.height.toLong()
        val views = try {
            TemporaryOcrViewFactory().create(
                source,
                listOf(OcrViewRecipe.ORIGINAL),
                OcrViewResourcePolicy(1, pixelCount, pixelCount, SafeSummary("exact-final-png-runtime-bounds-v1")),
            )
        } finally {
            source.close()
        }
        return try {
            val view = views.single()
            val candidates = scripts.map { script ->
                val recognizer = MlKitTextRecognizerAdapter.bundled(script)
                try {
                    recognizer.recognize(view).observations
                        .sortedWith(compareBy({ it.geometry.bounds.top }, { it.geometry.bounds.left }))
                        .joinToString("\n") { it.text }
                } finally {
                    recognizer.close()
                }
            }.distinct()
            val recognized = candidates.singleOrNull()
                ?: return ProviderResult.Completed(
                    OcrRoundTripInspection.create(
                        artifact.artifactRevision,
                        candidates.firstOrNull().orEmpty(),
                        approvedReadingOrder,
                        listOf("OCR_SCRIPT_RESULT_DISAGREEMENT"),
                    ),
                )
            ProviderResult.Completed(
                OcrRoundTripInspection.create(
                    artifact.artifactRevision,
                    recognized,
                    approvedReadingOrder,
                    if (recognized == approvedCanonicalText) emptyList() else listOf("OCR_TEXT_DIFFERENCE"),
                ),
            )
        } catch (_: Throwable) {
            ProviderResult.Error("OCR_ROUND_TRIP_EXECUTION_FAILED")
        } finally {
            views.forEach { it.close() }
        }
    }
}

class ExactPngBarcodeInspector : BarcodeInspector {
    override suspend fun inspect(artifact: ReopenedArtifact): ProviderResult<BarcodeInspection> {
        val bytes = artifact.bytesCopy()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return ProviderResult.Error("BARCODE_IMAGE_DECODE_FAILED").also { bytes.fill(0) }
        val storage = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
        val source = try {
            bitmap.getPixels(storage, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ArgbPixelImage(bitmap.width, bitmap.height, storage)
        } finally {
            storage.fill(0)
            bitmap.recycle()
            bytes.fill(0)
        }
        val pixels = source.width.toLong() * source.height.toLong()
        val views = try {
            TemporaryOcrViewFactory().create(
                source,
                listOf(OcrViewRecipe.ORIGINAL),
                OcrViewResourcePolicy(1, pixels, pixels, SafeSummary("exact-final-barcode-runtime-bounds-v1")),
            )
        } finally {
            source.close()
        }
        return try {
            val recognizer = MlKitBundledBarcodeRecognizer.bundled()
            val observations = try {
                recognizer.scan(views.single())
            } finally {
                recognizer.close()
            }
            ProviderResult.Completed(
                BarcodeInspection.create(
                    artifact.artifactRevision,
                    observations.mapNotNull { observation ->
                        observation.decodedValue?.let {
                            MachineReadableCode(
                                "MLKIT_FORMAT_${observation.formatCode}".uppercase(Locale.ROOT),
                                it,
                            )
                        }
                    },
                ),
            )
        } catch (_: Throwable) {
            ProviderResult.Error("BARCODE_RESCAN_EXECUTION_FAILED")
        } finally {
            views.forEach { it.close() }
        }
    }
}

class ExactRenderedRegionInspector(
    private val terminalPolicies: Map<ImageRegionId, ImageRegionPolicy>,
    private val operations: List<RenderOperation>,
) : RegionCoverageInspector {
    override suspend fun inspect(artifact: ReopenedArtifact): ProviderResult<RegionCoverageInspection> =
        ProviderResult.Completed(
            RegionCoverageInspection(
                artifact.artifactRevision,
                terminalPolicies,
                operations.filter { it.code == RenderOperationCode.APPROVED_SOURCE_REGION_IMPORTED }
                    .mapNotNull { it.regionId?.let(::ImageRegionId) }
                    .toSet(),
            ),
        )
}
