package app.shareguard.block.verify

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.ChangeEntry
import app.shareguard.core.model.ChangeId
import app.shareguard.core.model.ChangeLedger
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.SensitiveRepresentation
import app.shareguard.core.model.UrlComponents
import app.shareguard.core.model.UrlPolicy
import app.shareguard.core.model.UrlToken
import app.shareguard.core.model.UrlTokenId
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.VerificationType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FinalVerificationCoordinatorTextTest {
    private val coordinator = FinalVerificationCoordinator()

    @Before
    fun initializeAndroidOkHttpAssets() {
        OkHttp.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `valid exact text completes every applicable check and permits managed persistence`() = runBlocking {
        val fixture = VerificationFixtures.text()

        val outcome = coordinator.verify(fixture.request, fixture.providers)

        assertThat(outcome.report.assuranceClass)
            .isEqualTo(app.shareguard.core.model.AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT)
        assertThat(outcome.canPersistVerifiedResult).isTrue()
        assertThat(outcome.canManagedShare).isTrue()
        assertThat(outcome.blockingVerificationTypes).isEmpty()
        assertThat(outcome.report.requiredVerificationPassed).isTrue()
        assertThat(outcome.persistableSummary.resultStatuses).hasSize(16)
        assertThat(outcome.humanReadableReport.asPlainText()).doesNotContain("Hello world")
        assertThat(outcome.humanReadableReport.asPlainText()).contains("exact managed artifact")
    }

    @Test
    fun `missing providers are NOT_RUN and cannot produce verified assurance save or share`() = runBlocking {
        val fixture = VerificationFixtures.text()

        val outcome = coordinator.verify(fixture.request, VerificationProviders())

        assertThat(outcome.report.assuranceClass)
            .isEqualTo(app.shareguard.core.model.AssuranceClass.AS_0_UNVERIFIED)
        assertThat(outcome.canPersistVerifiedResult).isFalse()
        assertThat(outcome.canManagedShare).isFalse()
        assertThat(outcome.result(VerificationType.PERSISTENT_REOPEN_AND_DIGEST).status)
            .isEqualTo(VerificationStatus.NOT_RUN)
        assertThat(outcome.result(VerificationType.IDEMPOTENCE).status).isEqualTo(VerificationStatus.NOT_RUN)
        assertThat(outcome.result(VerificationType.NO_NETWORK_RUNTIME).status).isEqualTo(VerificationStatus.NOT_RUN)
        assertThat(outcome.result(VerificationType.SENSITIVE_LOGGING).status).isEqualTo(VerificationStatus.NOT_RUN)
    }

    @Test
    fun `reopened byte mutation is detected by digest and exact representation checks`() = runBlocking {
        val fixture = VerificationFixtures.text()
        val artifact = fixture.request.outputBundle.textArtifact!!
        val mutated = "Hello world!".toByteArray()
        val providers = fixture.providers.copy(
            artifactReopener = ArtifactReopener {
                ProviderResult.Completed(
                    ReopenedArtifact.create(
                        artifact.artifactRevision,
                        artifact.canonicalRevision,
                        artifact.mimeType,
                        VerificationFixtures.digest(mutated),
                        true,
                        mutated,
                    ),
                )
            },
        )

        val outcome = coordinator.verify(fixture.request, providers)

        val reopen = outcome.result(VerificationType.PERSISTENT_REOPEN_AND_DIGEST)
        assertThat(reopen.status).isEqualTo(VerificationStatus.FAIL)
        assertThat(reopen.failures.map { it.code })
            .containsAtLeast("REOPEN_DIGEST_MISMATCH", "TEXT_EXACT_REPRESENTATION_MISMATCH")
        assertThat(outcome.canPersistVerifiedResult).isFalse()
    }

    @Test
    fun `manifest missing block exact version and sequence mutations fail deterministically`() = runBlocking {
        val fixture = VerificationFixtures.text()
        val missing = fixture.request.copy(
            executedBlockManifest = fixture.request.executedBlockManifest.dropLast(1).let {
                app.shareguard.core.model.ImmutableList.copyOf(it)
            },
        )
        val missingOutcome = coordinator.verify(missing, fixture.providers)
        assertThat(missingOutcome.result(VerificationType.EXECUTED_BLOCK_MANIFEST).status)
            .isEqualTo(VerificationStatus.FAIL)
        assertThat(missingOutcome.result(VerificationType.EXECUTED_BLOCK_MANIFEST).failures.map { it.code })
            .contains("MANIFEST_BLOCK_COUNT_MISMATCH")

        val changed = fixture.request.executedBlockManifest.toMutableList()
        changed[5] = changed[5].copy(blockVersion = BlockVersion(2))
        val versionOutcome = coordinator.verify(
            fixture.request.copy(executedBlockManifest = app.shareguard.core.model.ImmutableList.copyOf(changed)),
            fixture.providers,
        )
        assertThat(versionOutcome.result(VerificationType.EXECUTED_BLOCK_MANIFEST).failures.map { it.code })
            .containsAtLeast("MANIFEST_BLOCK_VERSION_MISMATCH", "MANIFEST_UNKNOWN_BLOCK_VERSION")

        val swapped = fixture.request.executedBlockManifest.toMutableList()
        val left = swapped[2]
        val right = swapped[3]
        swapped[2] = left.copy(blockId = right.blockId, blockVersion = right.blockVersion)
        swapped[3] = right.copy(blockId = left.blockId, blockVersion = left.blockVersion)
        val orderOutcome = coordinator.verify(
            fixture.request.copy(executedBlockManifest = app.shareguard.core.model.ImmutableList.copyOf(swapped)),
            fixture.providers,
        )
        assertThat(orderOutcome.result(VerificationType.EXECUTED_BLOCK_MANIFEST).failures.map { it.code })
            .contains("MANIFEST_BLOCK_ORDER_MISMATCH")
    }

    @Test
    fun `unexpected invisible scalar in exact final text fails final Unicode reparse`() = runBlocking {
        val fixture = VerificationFixtures.text(
            artifactText = "Hello\u200Bworld",
            approvedText = "Hello world",
        )

        val outcome = coordinator.verify(fixture.request, fixture.providers)

        val unicode = outcome.result(VerificationType.FINAL_UNICODE)
        assertThat(unicode.status).isEqualTo(VerificationStatus.FAIL)
        assertThat(unicode.failures.map { it.code })
            .containsAtLeast("FINAL_TEXT_NOT_APPROVED_TEXT", "UNAPPROVED_FINAL_UNICODE_FINDING")
        assertThat(outcome.report.finalUnicodeFindings).isNotEmpty()
    }

    @Test
    fun `URL components are reparsed and removed query cannot reappear`() = runBlocking {
        val components = UrlComponents.create(
            scheme = "https",
            host = "example.com",
            registrableDomain = "example.com",
            pathSegments = listOf(""),
        )
        val token = UrlToken(
            tokenId = UrlTokenId("url-1"),
            originalReference = null,
            displayText = "https://example.com/",
            parsedComponents = components,
            normalizedComponents = components,
            chosenPolicy = UrlPolicy.KEEP_FULL,
            finalText = "https://example.com/",
            functionalityWarning = null,
            userApproved = false,
        )
        val fixture = VerificationFixtures.text(
            artifactText = "https://example.com/?utm_source=source-canary",
            approvedText = "https://example.com/",
            urlTokens = listOf(token),
        )

        val outcome = coordinator.verify(fixture.request, fixture.providers)

        val urls = outcome.result(VerificationType.FINAL_URL)
        assertThat(urls.status).isEqualTo(VerificationStatus.FAIL)
        assertThat(urls.failures.map { it.code })
            .containsAtLeast("FINAL_URL_COMPONENT_MISMATCH", "FINAL_URL_SERIALIZATION_MISMATCH")
        Unit
    }

    @Test
    fun `change-ledger removal and semantic decision linkage both block canonical revision`() = runBlocking {
        val changeId = ChangeId("change-1")
        val entry = ChangeEntry(
            changeId = changeId,
            blockId = BlockId("TXT-013"),
            blockVersion = BlockVersion(1),
            canonicalRevision = VerificationFixtures.revision,
            category = FindingCategory.UNICODE,
            sourceLocation = null,
            beforeRepresentation = SensitiveRepresentation("before"),
            afterRepresentation = SensitiveRepresentation("after"),
            reason = SafeSummary("WHITESPACE_CANONICALIZED"),
            reversibleBeforeExport = true,
            semanticImpact = SemanticImpact.NONE,
            reviewLink = null,
            verificationId = null,
        )
        val transformation = AppliedTransformation(
            blockId = entry.blockId,
            blockVersion = entry.blockVersion,
            canonicalRevision = entry.canonicalRevision,
            changeIds = setOf(changeId),
        )
        val fixture = VerificationFixtures.text(
            changes = listOf(entry),
            appliedTransformations = listOf(transformation),
        )
        val complete = coordinator.verify(fixture.request, fixture.providers)
        assertThat(complete.result(VerificationType.CANONICAL_REVISION_LINK).status)
            .isEqualTo(VerificationStatus.PASS)

        val missingLedger = fixture.request.copy(
            changeLedger = ChangeLedger.create(VerificationFixtures.ledgerId, VerificationFixtures.revision),
        )
        val outcome = coordinator.verify(missingLedger, fixture.providers)

        assertThat(outcome.result(VerificationType.CANONICAL_REVISION_LINK).status)
            .isEqualTo(VerificationStatus.FAIL)
        assertThat(outcome.result(VerificationType.CANONICAL_REVISION_LINK).failures.map { it.code })
            .containsAtLeast("CONTEXT_CHANGE_LEDGER_MISMATCH", "CHANGE_LEDGER_ENTRY_MISSING")
        Unit
    }

    @Test
    fun `source URI filename path and provider canaries block while report remains redacted`() = runBlocking {
        val secret = "content://evil.provider/private/source-name.png"
        val canaries = listOf(
            SourceCanary.utf8(SourceCanaryKind.SOURCE_URI, secret),
            SourceCanary.utf8(SourceCanaryKind.SOURCE_FILENAME, "source-name.png"),
            SourceCanary.utf8(SourceCanaryKind.PROVIDER_AUTHORITY, "evil.provider"),
        )
        val fixture = VerificationFixtures.text(
            sourceCanaries = canaries,
            referenceSurfaces = listOf(ReferenceSurface.utf8(ReferenceSurfaceKind.OUTGOING_INTENT, secret)),
        )

        val outcome = coordinator.verify(fixture.request, fixture.providers)

        assertThat(outcome.result(VerificationType.SOURCE_REFERENCE).status).isEqualTo(VerificationStatus.FAIL)
        assertThat(outcome.result(VerificationType.SOURCE_REFERENCE).failures.map { it.code })
            .contains("REFERENCE_CANARY_DETECTED")
        assertThat(outcome.humanReadableReport.asPlainText()).doesNotContain(secret)
        assertThat(outcome.humanReadableReport.asPlainText()).doesNotContain("source-name.png")
        assertThat(outcome.persistableSummary.toString()).doesNotContain("evil.provider")
    }

    @Test
    fun `idempotence logging network and assurance assertion failures cannot be upgraded`() = runBlocking {
        val fixture = VerificationFixtures.text()
        val providers = fixture.providers.copy(
            idempotenceInspector = IdempotenceInspector { text, revision ->
                ProviderResult.Completed(IdempotenceInspection(revision, "$text!", 1))
            },
            runtimePrivacyInspector = RuntimePrivacyInspector {
                ProviderResult.Completed(
                    RuntimePrivacyInspection(
                        networkEvidenceCaptured = true,
                        networkAttemptCount = 1,
                        onDemandModelDownloadCount = 1,
                        declaredPermissionNames = setOf("android.permission.INTERNET"),
                        broadStoragePermissionPresent = true,
                        appPrivateArtifactRoot = true,
                        cleanupCompleted = true,
                        outgoingMimeMatchesArtifact = true,
                        outgoingDigestMatchesArtifact = true,
                        outgoingContentUriAppScoped = true,
                        temporaryReadGrantLeastPrivilege = true,
                    ),
                )
            },
            sensitiveLoggingInspector = SensitiveLoggingInspector {
                ProviderResult.Completed(SensitiveLoggingInspection(true, true, 4, 2, true))
            },
        )

        val outcome = coordinator.verify(fixture.request, providers)

        assertThat(outcome.result(VerificationType.IDEMPOTENCE).status).isEqualTo(VerificationStatus.FAIL)
        assertThat(outcome.result(VerificationType.NO_NETWORK_RUNTIME).failures.map { it.code })
            .containsAtLeast("NETWORK_ATTEMPT_DETECTED", "INTERNET_PERMISSION_PRESENT", "BROAD_STORAGE_PERMISSION_PRESENT")
        assertThat(outcome.result(VerificationType.SENSITIVE_LOGGING).failures.map { it.code })
            .containsAtLeast("SENSITIVE_LOG_PAYLOAD_DETECTED", "PERSISTENT_PRODUCTION_TRACE_ENABLED")
        assertThat(outcome.report.assuranceClass)
            .isEqualTo(app.shareguard.core.model.AssuranceClass.AS_0_UNVERIFIED)
        assertThat(outcome.canManagedShare).isFalse()
    }

    private fun FinalVerificationOutcome.result(type: VerificationType) =
        if (type == VerificationType.SOURCE_REFERENCE) report.sourceReferenceAudit
        else report.results.single { it.type == type }
}
