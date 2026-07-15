package app.shareguard.block.url

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.FindingStatus
import app.shareguard.core.model.ReviewStatus
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SemanticRisk
import app.shareguard.core.model.Severity
import app.shareguard.core.model.SensitiveRepresentation
import app.shareguard.core.model.UrlComponents
import app.shareguard.core.model.UrlQueryParameter
import app.shareguard.core.model.toImmutableList
import com.ibm.icu.text.SpoofChecker
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN

class StandardsUrlAnalyzer(
    private val spoofChecker: SpoofChecker = defaultSpoofChecker(),
) {
    fun analyze(candidate: UrlCandidate): UrlAnalysis {
        val ids = UrlIds(candidate.tokenId.value)
        val taggedFindings = mutableListOf<TaggedFinding>()
        val failures = mutableListOf<UrlFailure>()

        fun addFinding(
            blockId: BlockId,
            severity: Severity,
            confidence: ConfidenceClass,
            title: String,
            explanation: String,
            semanticRisk: SemanticRisk,
            reviewCode: UrlReviewCode? = null,
        ) {
            taggedFindings += TaggedFinding(
                finding = Finding(
                    findingId = ids.finding(),
                    blockId = blockId,
                    category = FindingCategory.URL,
                    severity = severity,
                    confidenceClass = confidence,
                    sourceLocation = candidate.sourceLocation,
                    canonicalLocation = null,
                    title = SafeSummary(title),
                    explanation = SafeSummary(explanation),
                    suggestedAction = if (reviewCode == null) null else DecisionAction.ACCEPT_PROPOSED_CHANGE,
                    semanticRisk = semanticRisk,
                    requiresUserDecision = reviewCode != null,
                    status = if (reviewCode == null) FindingStatus.DETECTED else FindingStatus.REVIEW_REQUIRED,
                    evidenceSummary = SafeSummary("${blockId.value}_EVIDENCE"),
                ),
                reviewCode = reviewCode,
            )
        }

        when {
            candidate.schemeWasImplicit -> addFinding(
                URL_001,
                Severity.MEDIUM,
                ConfidenceClass.STRONG_HEURISTIC,
                "SCHEMELESS_URL_CANDIDATE",
                "SCHEMELESS_TEXT_IS_NOT_AUTOMATICALLY_REWRITTEN",
                SemanticRisk.POSSIBLE_MEANING_CHANGE,
                UrlReviewCode.SCHEMELESS_URL_REVIEW,
            )
            candidate.kind == UrlCandidateKind.MARKDOWN_TARGET &&
                candidate.displayText.value != candidate.parseTarget.value -> addFinding(
                URL_001,
                Severity.HIGH,
                ConfidenceClass.CERTAIN_BY_PARSER,
                "VISIBLE_TEXT_TARGET_DIVERGENCE",
                "VISIBLE_TEXT_AND_LINK_TARGET_REQUIRE_SEPARATE_REVIEW",
                SemanticRisk.HIGH_IMPACT,
                UrlReviewCode.VISIBLE_TEXT_LINK_TARGET_REVIEW,
            )
        }
        if (candidate.kind == UrlCandidateKind.QR_PAYLOAD) {
            addFinding(
                URL_001,
                Severity.HIGH,
                ConfidenceClass.CERTAIN_BY_PARSER,
                "MACHINE_READABLE_URL_PAYLOAD",
                "QR_URL_REQUIRES_INDEPENDENT_REVIEW",
                SemanticRisk.HIGH_IMPACT,
                UrlReviewCode.QR_PAYLOAD_REVIEW,
            )
        }
        if (candidate.reconstructedVisualWrap) {
            addFinding(
                URL_001,
                Severity.MEDIUM,
                ConfidenceClass.WEAK_HEURISTIC,
                "URL_VISUAL_WRAP_RECONSTRUCTED",
                "LINE_JOIN_REQUIRES_EXPLICIT_REVIEW",
                SemanticRisk.POSSIBLE_MEANING_CHANGE,
                UrlReviewCode.URL_LINE_WRAP_RECONSTRUCTION_REVIEW,
            )
        }

        if (candidate.kind == UrlCandidateKind.EMAIL_LIKE) {
            addFinding(
                URL_002,
                Severity.MEDIUM,
                ConfidenceClass.CERTAIN_BY_PARSER,
                "UNSUPPORTED_URL_SCHEME",
                "ONLY_HTTP_URLS_HAVE_STRUCTURED_CANONICALIZATION",
                SemanticRisk.POSSIBLE_MEANING_CHANGE,
                UrlReviewCode.PARSE_FAILURE_REVIEW,
            )
            failures += UrlFailure(UrlFailureCode.UNSUPPORTED_SCHEME, URL_002, candidate.sourceLocation)
            return buildAnalysis(null, taggedFindings, failures, ids)
        }

        val parseInput = if (candidate.schemeWasImplicit) {
            "https://${candidate.parseTarget.value}"
        } else {
            candidate.parseTarget.value
        }
        val httpUrl = parseInput.toHttpUrlOrNull()
        if (httpUrl == null) {
            addFinding(
                URL_002,
                Severity.HIGH,
                ConfidenceClass.CERTAIN_BY_PARSER,
                "URL_PARSE_FAILED",
                "STANDARDS_PARSER_REJECTED_CANDIDATE",
                SemanticRisk.HIGH_IMPACT,
                UrlReviewCode.PARSE_FAILURE_REVIEW,
            )
            failures += UrlFailure(UrlFailureCode.PARSE_FAILED, URL_002, candidate.sourceLocation)
            return buildAnalysis(null, taggedFindings, failures, ids)
        }

        val malformedPercent = hasMalformedPercentEncoding(candidate.parseTarget.value)
        if (malformedPercent) {
            addFinding(
                URL_002,
                Severity.HIGH,
                ConfidenceClass.CERTAIN_BY_PARSER,
                "MALFORMED_PERCENT_ENCODING",
                "MALFORMED_ENCODING_REQUIRES_REVIEW",
                SemanticRisk.HIGH_IMPACT,
                UrlReviewCode.MALFORMED_PERCENT_ENCODING_REVIEW,
            )
        }

        val unicodeHost = IDN.toUnicode(httpUrl.host)
        val spoofResult = SpoofChecker.CheckResult()
        spoofChecker.failsChecks(unicodeHost, spoofResult)
        val skeleton = spoofChecker.getSkeleton(unicodeHost)
        val hostHasNonAscii = unicodeHost.any { it.code > 0x7F }
        val suspiciousInternationalHost = hostHasNonAscii &&
            (spoofResult.checks != 0 || skeleton.lowercase() != unicodeHost.lowercase())
        if (suspiciousInternationalHost) {
            addFinding(
                URL_003,
                Severity.HIGH,
                ConfidenceClass.STRONG_HEURISTIC,
                "INTERNATIONAL_HOST_CONFUSABLE",
                "ICU_HOST_SPOOF_CHECK_REQUIRES_REVIEW",
                SemanticRisk.HIGH_IMPACT,
                UrlReviewCode.IDN_CONFUSABLE_HOST_REVIEW,
            )
        }

        val registrableDomain = httpUrl.topPrivateDomain()
        val labels = httpUrl.host.split('.')
        val boundaryRequiredButUnavailable = registrableDomain == null && labels.size > 2
        if (boundaryRequiredButUnavailable) {
            addFinding(
                URL_004,
                Severity.HIGH,
                ConfidenceClass.CERTAIN_BY_PARSER,
                "PUBLIC_SUFFIX_BOUNDARY_UNAVAILABLE",
                "ORIGIN_REDUCTION_CANNOT_BE_CLAIMED_SAFE",
                SemanticRisk.HIGH_IMPACT,
                UrlReviewCode.PUBLIC_SUFFIX_UNAVAILABLE_REVIEW,
            )
        }
        val subdomain = registrableDomain?.let { domain ->
            httpUrl.host.takeIf { it != domain }?.removeSuffix(".$domain")
        }
        val userInfo = userInfo(httpUrl)
        val queryInventory = queryInventory(httpUrl, malformedPercent)
        queryInventory.forEach { entry ->
            when (entry.risk) {
                QueryRisk.KNOWN_TRACKING -> addFinding(
                    URL_005,
                    Severity.LOW,
                    ConfidenceClass.CERTAIN_BY_PARSER,
                    "KNOWN_TRACKING_QUERY_PARAMETER",
                    "VERSIONED_TRACKING_RULE_MATCH",
                    SemanticRisk.NONE,
                )
                QueryRisk.LIKELY_IDENTIFIER,
                QueryRisk.UNKNOWN,
                -> if (candidate.kind !in setOf(UrlCandidateKind.QR_PAYLOAD, UrlCandidateKind.MARKDOWN_TARGET)) {
                    addFinding(
                        URL_005,
                        Severity.HIGH,
                        if (entry.risk == QueryRisk.LIKELY_IDENTIFIER) {
                            ConfidenceClass.STRONG_HEURISTIC
                        } else {
                            ConfidenceClass.UNKNOWN
                        },
                        "UNKNOWN_OR_IDENTIFIER_QUERY_PARAMETER",
                        "QUERY_COMPONENT_REQUIRES_FUNCTIONAL_REVIEW",
                        SemanticRisk.POSSIBLE_MEANING_CHANGE,
                        UrlReviewCode.UNKNOWN_URL_COMPONENT_REVIEW,
                    )
                }
                QueryRisk.FUNCTIONAL -> Unit
            }
        }

        val pathInventory = pathInventory(httpUrl, candidate.parseTarget.value)
        val pathRisks = pathInventory.map { it.risk }.toSet()
        when {
            PathRisk.PERCENT_ENCODED_IDENTIFIER in pathRisks -> addFinding(
                URL_006,
                Severity.HIGH,
                ConfidenceClass.STRONG_HEURISTIC,
                "PERCENT_ENCODED_PATH_IDENTIFIER",
                "DECODED_PATH_COMPONENT_REQUIRES_REVIEW",
                SemanticRisk.POSSIBLE_MEANING_CHANGE,
                UrlReviewCode.ENCODED_PATH_COMPONENT_REVIEW,
            )
            PathRisk.LIKELY_IDENTIFIER in pathRisks -> addFinding(
                URL_006,
                Severity.HIGH,
                ConfidenceClass.STRONG_HEURISTIC,
                "PATH_IDENTIFIER",
                "PATH_REDUCTION_CAN_CHANGE_NAVIGATION",
                SemanticRisk.POSSIBLE_MEANING_CHANGE,
                UrlReviewCode.PATH_SEMANTICS_REVIEW,
            )
            PathRisk.FUNCTIONAL_INVITE in pathRisks -> addFinding(
                URL_006,
                Severity.MEDIUM,
                ConfidenceClass.STRONG_HEURISTIC,
                "FUNCTIONAL_INVITE_PATH",
                "FUNCTIONAL_PATH_RETENTION_REQUIRES_REVIEW",
                SemanticRisk.POSSIBLE_MEANING_CHANGE,
                UrlReviewCode.FUNCTIONAL_URL_COMPONENT_REVIEW,
            )
        }

        val subdomainRisk = when {
            boundaryRequiredButUnavailable -> SubdomainRisk.DOMAIN_BOUNDARY_UNAVAILABLE
            subdomain == null -> SubdomainRisk.NONE
            looksPersonalized(subdomain) -> SubdomainRisk.LIKELY_PERSONALIZED
            else -> SubdomainRisk.ORDINARY
        }
        if (subdomainRisk == SubdomainRisk.LIKELY_PERSONALIZED) {
            addFinding(
                URL_007,
                Severity.HIGH,
                ConfidenceClass.STRONG_HEURISTIC,
                "PERSONALIZED_SUBDOMAIN",
                "SUBDOMAIN_REDUCTION_CAN_CHANGE_ROUTING",
                SemanticRisk.POSSIBLE_MEANING_CHANGE,
                UrlReviewCode.HOST_COMPONENT_REVIEW,
            )
        }
        if (candidate.kind == UrlCandidateKind.MARKDOWN_TARGET ||
            (registrableDomain != null && registrableDomain.count { it == '.' } >= 2 && subdomain != null)
        ) {
            addFinding(
                URL_006,
                Severity.MEDIUM,
                ConfidenceClass.CERTAIN_BY_PARSER,
                "ORIGIN_ONLY_CHANGES_FUNCTIONALITY",
                "PATH_OR_HOST_REDUCTION_REQUIRES_APPROVAL",
                SemanticRisk.POSSIBLE_MEANING_CHANGE,
                UrlReviewCode.ORIGIN_ONLY_FUNCTIONALITY_REVIEW,
            )
        }
        if (httpUrl.fragment != null) {
            addFinding(
                URL_008,
                Severity.MEDIUM,
                ConfidenceClass.CERTAIN_BY_PARSER,
                "URL_FRAGMENT_PRESENT",
                "FRAGMENT_CAN_IDENTIFY_OR_CONTROL_NAVIGATION",
                SemanticRisk.POSSIBLE_MEANING_CHANGE,
                UrlReviewCode.FRAGMENT_SEMANTICS_REVIEW,
            )
        }
        if (userInfo != null) {
            addFinding(
                URL_008,
                Severity.CRITICAL,
                ConfidenceClass.CERTAIN_BY_PARSER,
                "DECEPTIVE_URL_USERINFO",
                "USERINFO_CAN_DISGUISE_ACTUAL_HOST",
                SemanticRisk.HIGH_IMPACT,
                UrlReviewCode.DECEPTIVE_HOST_REVIEW,
            )
        }

        val unresolvedRedirect = httpUrl.host in SHORTENER_HOSTS
        if (unresolvedRedirect) {
            addFinding(
                URL_009,
                Severity.HIGH,
                ConfidenceClass.STRONG_HEURISTIC,
                "UNRESOLVED_REDIRECT_URL",
                "OFFLINE_ANALYSIS_DOES_NOT_CLAIM_DESTINATION_SAFETY",
                SemanticRisk.HIGH_IMPACT,
                UrlReviewCode.UNRESOLVED_REDIRECT_REVIEW,
            )
        }

        val components = UrlComponents.create(
            scheme = httpUrl.scheme.takeUnless { candidate.schemeWasImplicit },
            userInfo = userInfo,
            host = httpUrl.host,
            port = httpUrl.port.takeUnless { it == defaultPort(httpUrl.scheme) },
            registrableDomain = registrableDomain,
            subdomain = subdomain,
            pathSegments = httpUrl.pathSegments,
            queryParameters = (0 until httpUrl.querySize).map { index ->
                UrlQueryParameter(httpUrl.queryParameterName(index), httpUrl.queryParameterValue(index))
            },
            fragment = httpUrl.fragment,
        )
        val parsed = ParsedUrlCandidate(
            candidate = candidate,
            parsedComponents = components,
            inventory = UrlSecurityInventory(
                queryParameters = queryInventory.toImmutableList(),
                pathSegments = pathInventory.toImmutableList(),
                subdomainRisk = subdomainRisk,
                hasFragment = httpUrl.fragment != null,
                hasUserInfo = userInfo != null,
                unresolvedRedirect = unresolvedRedirect,
                hostSpoofChecks = spoofResult.checks,
                hostRestrictionLevel = spoofResult.restrictionLevel?.toString() ?: "UNKNOWN",
                unicodeHost = SensitiveRepresentation(unicodeHost),
                hostSkeleton = SensitiveRepresentation(skeleton),
                publicSuffixDataAvailable = registrableDomain != null,
            ),
        )
        return buildAnalysis(parsed, taggedFindings, failures, ids)
    }

    fun analyzeAll(candidates: Iterable<UrlCandidate>): UrlAnalysisBatch {
        val stableCandidates = candidates.toList()
        val analyses = stableCandidates.map(::analyze)
        return UrlAnalysisBatch(
            candidates = stableCandidates.toImmutableList(),
            analyses = analyses.toImmutableList(),
            findings = analyses.flatMap { it.findings }.toImmutableList(),
            reviewGates = analyses.flatMap { it.reviewGates }.toImmutableList(),
            failures = analyses.flatMap { it.failures }.toImmutableList(),
        )
    }

    private fun buildAnalysis(
        parsed: ParsedUrlCandidate?,
        findings: List<TaggedFinding>,
        failures: List<UrlFailure>,
        ids: UrlIds,
    ): UrlAnalysis {
        val gates = findings.filter { it.reviewCode != null }
            .groupBy { requireNotNull(it.reviewCode) }
            .map { (code, grouped) ->
                UrlReviewGate(
                    code = code,
                    decisionId = ids.decision(code),
                    findingIds = grouped.map { it.finding.findingId }.toImmutableList(),
                    blocking = true,
                    status = ReviewStatus.PENDING,
                    summary = SafeSummary(code.name),
                )
            }
            .sortedBy { it.code.name }
        return UrlAnalysis(
            parsed = parsed,
            findings = findings.map { it.finding }.toImmutableList(),
            reviewGates = gates.toImmutableList(),
            failures = failures.toImmutableList(),
        )
    }

    private fun queryInventory(url: HttpUrl, malformedPercent: Boolean): List<QueryInventoryEntry> =
        (0 until url.querySize).map { index ->
            val name = url.queryParameterName(index)
            val value = url.queryParameterValue(index)
            QueryInventoryEntry(
                index = index,
                name = SensitiveRepresentation(name),
                value = value?.let(::SensitiveRepresentation),
                risk = classifyQuery(name, value),
                malformedPercentEncoding = malformedPercent,
            )
        }

    private fun pathInventory(url: HttpUrl, original: String): List<PathInventoryEntry> =
        url.pathSegments.mapIndexed { index, segment ->
            val priorIsInvite = index > 0 && url.pathSegments[index - 1].equals("invite", ignoreCase = true)
            PathInventoryEntry(
                index = index,
                decodedSegment = SensitiveRepresentation(segment),
                risk = when {
                    segment.isEmpty() -> PathRisk.EMPTY
                    priorIsInvite || segment.equals("invite", ignoreCase = true) -> PathRisk.FUNCTIONAL_INVITE
                    original.contains('%') && looksIdentifier(segment) -> PathRisk.PERCENT_ENCODED_IDENTIFIER
                    looksIdentifier(segment) -> PathRisk.LIKELY_IDENTIFIER
                    else -> PathRisk.ORDINARY
                },
            )
        }

    private data class TaggedFinding(val finding: Finding, val reviewCode: UrlReviewCode?)

    private class UrlIds(private val prefix: String) {
        private var findingIndex = 0

        fun finding(): FindingId = FindingId("$prefix-f-${findingIndex++}")

        fun decision(code: UrlReviewCode): DecisionId = DecisionId("$prefix-d-${code.name.lowercase()}")
    }

    companion object {
        private val URL_001 = BlockId("URL-001")
        private val URL_002 = BlockId("URL-002")
        private val URL_003 = BlockId("URL-003")
        private val URL_004 = BlockId("URL-004")
        private val URL_005 = BlockId("URL-005")
        private val URL_006 = BlockId("URL-006")
        private val URL_007 = BlockId("URL-007")
        private val URL_008 = BlockId("URL-008")
        private val URL_009 = BlockId("URL-009")

        private val KNOWN_TRACKING_NAMES = setOf(
            "fbclid",
            "gclid",
            "dclid",
            "msclkid",
            "mc_cid",
            "mc_eid",
            "ref",
            "referrer",
            "source",
        )
        private val FUNCTIONAL_QUERY_NAMES = setOf("page", "q", "query", "lang", "locale")
        private val IDENTIFIER_QUERY_NAMES = setOf("session", "sessionid", "token", "invite", "user", "account")
        private val SHORTENER_HOSTS = setOf(
            "bit.ly",
            "t.co",
            "tinyurl.com",
            "goo.gl",
            "ow.ly",
            "t.example",
        )

        private fun defaultSpoofChecker(): SpoofChecker = SpoofChecker.Builder()
            .setChecks(SpoofChecker.ALL_CHECKS)
            .setRestrictionLevel(SpoofChecker.RestrictionLevel.MODERATELY_RESTRICTIVE)
            .build()

        private fun userInfo(url: HttpUrl): String? = when {
            url.username.isEmpty() && url.password.isEmpty() -> null
            url.password.isEmpty() -> url.username
            else -> "${url.username}:${url.password}"
        }

        private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

        private fun classifyQuery(name: String, value: String?): QueryRisk {
            val lower = name.lowercase()
            if (lower.startsWith("utm_") || lower in KNOWN_TRACKING_NAMES) return QueryRisk.KNOWN_TRACKING
            if (lower == "id" && value?.all(Char::isDigit) == true && value.length <= 6) return QueryRisk.FUNCTIONAL
            if (lower in FUNCTIONAL_QUERY_NAMES && value?.let { !looksIdentifier(it) } != false) return QueryRisk.FUNCTIONAL
            if (lower in IDENTIFIER_QUERY_NAMES || value?.let(::looksIdentifier) == true) return QueryRisk.LIKELY_IDENTIFIER
            return QueryRisk.UNKNOWN
        }

        private fun looksIdentifier(value: String): Boolean {
            if (value.length < 7) return false
            val letters = value.count(Char::isLetter)
            val digits = value.count(Char::isDigit)
            return (letters > 0 && digits > 0) || value.length >= 14
        }

        private fun looksPersonalized(subdomain: String): Boolean = subdomain.split('.').any { label ->
            label.length >= 8 && label.any(Char::isDigit) ||
                label.contains('-') && label.any(Char::isDigit)
        }

        private fun hasMalformedPercentEncoding(value: String): Boolean {
            var index = value.indexOf('%')
            while (index >= 0) {
                if (index + 2 >= value.length || !value[index + 1].isHexDigit() || !value[index + 2].isHexDigit()) {
                    return true
                }
                index = value.indexOf('%', index + 3)
            }
            return false
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
