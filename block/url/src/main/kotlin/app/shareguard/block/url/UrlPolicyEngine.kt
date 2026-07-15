package app.shareguard.block.url

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SensitiveRepresentation
import app.shareguard.core.model.UrlComponents
import app.shareguard.core.model.UrlPolicy
import app.shareguard.core.model.UrlQueryParameter
import app.shareguard.core.model.toImmutableList
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class UrlPolicyEngine(
    private val serializer: CanonicalUrlSerializer = CanonicalUrlSerializer(),
) {
    fun propose(
        analysis: UrlAnalysis,
        approvals: UrlReviewApprovals = UrlReviewApprovals.none(),
    ): UrlTransformationProposal? {
        val parsed = analysis.parsed ?: return null
        val original = parsed.parsedComponents
        val inventory = parsed.inventory
        val candidate = parsed.candidate
        val reviewCodes = analysis.reviewGates.map { it.code }.distinct().sortedBy { it.name }
        val changes = mutableListOf<UrlComponentChange>()

        if (candidate.reconstructedVisualWrap) {
            changes += UrlComponentChange(
                component = UrlComponentKind.LINE_WRAP,
                blockId = URL_001,
                before = candidate.originalReference,
                after = candidate.parseTarget,
                reviewCode = UrlReviewCode.URL_LINE_WRAP_RECONSTRUCTION_REVIEW,
            )
        }
        if (candidate.kind == UrlCandidateKind.MARKDOWN_TARGET && candidate.displayText.value != candidate.parseTarget.value) {
            changes += UrlComponentChange(
                component = UrlComponentKind.DISPLAY_MODE,
                blockId = URL_015,
                before = candidate.displayText,
                after = candidate.parseTarget,
                reviewCode = UrlReviewCode.VISIBLE_TEXT_LINK_TARGET_REVIEW,
            )
        }

        val hasOriginOnlyReview = UrlReviewCode.ORIGIN_ONLY_FUNCTIONALITY_REVIEW in reviewCodes
        val hasPathIdentifier = inventory.pathSegments.any { it.risk == PathRisk.LIKELY_IDENTIFIER }
        val hasEncodedPathIdentifier = inventory.pathSegments.any {
            it.risk == PathRisk.PERCENT_ENCODED_IDENTIFIER
        }
        val hasUnknownQuery = inventory.queryParameters.any {
            it.risk in setOf(QueryRisk.UNKNOWN, QueryRisk.LIKELY_IDENTIFIER)
        }
        val hasKnownTracking = inventory.queryParameters.any { it.risk == QueryRisk.KNOWN_TRACKING }
        val reduceSubdomain = inventory.subdomainRisk == SubdomainRisk.LIKELY_PERSONALIZED ||
            hasOriginOnlyReview && original.subdomain != null && original.registrableDomain != null
        val originOnly = hasPathIdentifier || reduceSubdomain || hasOriginOnlyReview
        val removeAllQuery = hasUnknownQuery || candidate.kind in setOf(
            UrlCandidateKind.QR_PAYLOAD,
            UrlCandidateKind.MARKDOWN_TARGET,
        ) && original.queryParameters.isNotEmpty()

        var host = original.host
        var registrableDomain = original.registrableDomain
        var subdomain = original.subdomain
        if (UrlReviewCode.IDN_CONFUSABLE_HOST_REVIEW in reviewCodes) {
            approvals.approvedHostByTokenId[candidate.tokenId]?.let { approvedHost ->
                val parsedHost = "https://$approvedHost/".toHttpUrlOrNull()
                if (parsedHost != null) {
                    host = parsedHost.host
                    registrableDomain = parsedHost.topPrivateDomain()
                    subdomain = registrableDomain?.let { domain ->
                        host.takeIf { it != domain }?.removeSuffix(".$domain")
                    }
                }
            }
        }
        if (reduceSubdomain) {
            val domain = requireNotNull(original.registrableDomain)
            host = domain
            registrableDomain = domain
            subdomain = null
        }
        if (host != original.host) {
            changes += UrlComponentChange(
                component = if (reduceSubdomain) UrlComponentKind.SUBDOMAIN else UrlComponentKind.HOST,
                blockId = if (reduceSubdomain) URL_013 else URL_003,
                before = SensitiveRepresentation(original.host),
                after = SensitiveRepresentation(host),
                reviewCode = if (reduceSubdomain) {
                    if (UrlReviewCode.HOST_COMPONENT_REVIEW in reviewCodes) {
                        UrlReviewCode.HOST_COMPONENT_REVIEW
                    } else {
                        UrlReviewCode.ORIGIN_ONLY_FUNCTIONALITY_REVIEW
                    }
                } else {
                    UrlReviewCode.IDN_CONFUSABLE_HOST_REVIEW
                },
            )
        }

        val originalUserInfo = original.userInfo
        val userInfo = if (originalUserInfo != null) {
            changes += UrlComponentChange(
                component = UrlComponentKind.USERINFO,
                blockId = URL_008,
                before = SensitiveRepresentation(originalUserInfo),
                after = null,
                reviewCode = UrlReviewCode.DECEPTIVE_HOST_REVIEW,
            )
            null
        } else {
            null
        }

        val pathSegments = when {
            originOnly -> listOf("")
            hasEncodedPathIdentifier -> {
                val firstRisk = inventory.pathSegments.indexOfFirst {
                    it.risk == PathRisk.PERCENT_ENCODED_IDENTIFIER
                }
                original.pathSegments.take(firstRisk).ifEmpty { listOf("") }
            }
            else -> original.pathSegments.toList()
        }
        if (pathSegments != original.pathSegments.toList()) {
            changes += UrlComponentChange(
                component = UrlComponentKind.PATH,
                blockId = URL_012,
                before = SensitiveRepresentation(original.pathSegments.joinToString("/")),
                after = SensitiveRepresentation(pathSegments.joinToString("/")),
                reviewCode = when {
                    hasEncodedPathIdentifier -> UrlReviewCode.ENCODED_PATH_COMPONENT_REVIEW
                    UrlReviewCode.HOST_COMPONENT_REVIEW in reviewCodes -> UrlReviewCode.HOST_COMPONENT_REVIEW
                    hasPathIdentifier -> UrlReviewCode.PATH_SEMANTICS_REVIEW
                    else -> UrlReviewCode.ORIGIN_ONLY_FUNCTIONALITY_REVIEW
                },
            )
        }

        val queryParameters = mutableListOf<UrlQueryParameter>()
        original.queryParameters.forEachIndexed { index, parameter ->
            val risk = inventory.queryParameters[index].risk
            val remove = risk == QueryRisk.KNOWN_TRACKING || removeAllQuery
            if (remove) {
                changes += UrlComponentChange(
                    component = UrlComponentKind.QUERY_PARAMETER,
                    blockId = if (risk == QueryRisk.KNOWN_TRACKING) URL_010 else URL_011,
                    before = SensitiveRepresentation(
                        if (parameter.value == null) parameter.name else "${parameter.name}=${parameter.value}",
                    ),
                    after = null,
                    reviewCode = when {
                        risk == QueryRisk.KNOWN_TRACKING -> null
                        candidate.kind == UrlCandidateKind.QR_PAYLOAD -> UrlReviewCode.QR_PAYLOAD_REVIEW
                        candidate.kind == UrlCandidateKind.MARKDOWN_TARGET -> {
                            UrlReviewCode.ORIGIN_ONLY_FUNCTIONALITY_REVIEW
                        }
                        else -> UrlReviewCode.UNKNOWN_URL_COMPONENT_REVIEW
                    },
                )
            } else {
                queryParameters += parameter
            }
        }

        val originalFragment = original.fragment
        val fragment = if (originalFragment != null) {
            changes += UrlComponentChange(
                component = UrlComponentKind.FRAGMENT,
                blockId = URL_011,
                before = SensitiveRepresentation(originalFragment),
                after = null,
                reviewCode = UrlReviewCode.FRAGMENT_SEMANTICS_REVIEW,
            )
            null
        } else {
            null
        }

        val proposed = UrlComponents.create(
            scheme = original.scheme,
            userInfo = userInfo,
            host = host,
            port = original.port,
            registrableDomain = registrableDomain,
            subdomain = subdomain,
            pathSegments = pathSegments,
            queryParameters = queryParameters,
            fragment = fragment,
        )
        val chosenPolicy = when {
            originOnly -> UrlPolicy.KEEP_ORIGIN_ONLY
            hasEncodedPathIdentifier -> UrlPolicy.REDUCE_PATH
            original.userInfo != null || host != original.host -> UrlPolicy.MANUAL
            removeAllQuery || original.fragment != null -> UrlPolicy.REMOVE_QUERY_AND_FRAGMENT
            hasKnownTracking -> UrlPolicy.REMOVE_KNOWN_TRACKING
            else -> UrlPolicy.KEEP_FULL
        }
        val warning = when {
            inventory.unresolvedRedirect -> SafeSummary("UNRESOLVED_REDIRECT_DESTINATION")
            reviewCodes.isNotEmpty() -> SafeSummary("URL_CHANGE_OR_RETENTION_REQUIRES_REVIEW")
            else -> null
        }
        val proposedText = serializer.serialize(proposed)
        if (candidate.parseTarget.value != proposedText) {
            changes += UrlComponentChange(
                component = UrlComponentKind.SERIALIZATION,
                blockId = URL_014,
                before = candidate.parseTarget,
                after = SensitiveRepresentation(proposedText),
                reviewCode = if (UrlReviewCode.MALFORMED_PERCENT_ENCODING_REVIEW in reviewCodes) {
                    UrlReviewCode.MALFORMED_PERCENT_ENCODING_REVIEW
                } else {
                    null
                },
            )
        }
        return UrlTransformationProposal(
            tokenId = candidate.tokenId,
            chosenPolicy = chosenPolicy,
            proposedComponents = proposed,
            proposedText = proposedText,
            clickable = chosenPolicy != UrlPolicy.NON_CLICKABLE_DOMAIN,
            requiredReviews = reviewCodes.toImmutableList(),
            changes = changes.toImmutableList(),
            functionalityWarning = warning,
        )
    }

    companion object {
        private val URL_001 = BlockId("URL-001")
        private val URL_003 = BlockId("URL-003")
        private val URL_008 = BlockId("URL-008")
        private val URL_010 = BlockId("URL-010")
        private val URL_011 = BlockId("URL-011")
        private val URL_012 = BlockId("URL-012")
        private val URL_013 = BlockId("URL-013")
        private val URL_014 = BlockId("URL-014")
        private val URL_015 = BlockId("URL-015")
    }
}
