package app.shareguard.block.url

import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.UrlComponents
import app.shareguard.core.model.UrlQueryParameter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlSystemTest {
    private val extractor = UrlCandidateExtractor()
    private val analyzer = StandardsUrlAnalyzer()

    @Test
    fun parserInventory_preservesDuplicateQueryOrderAndDecodedValues() {
        val candidate = extractor.extract(
            UrlProcessingInput.create(
                "https://Example.COM/a%20b?x=one&x=two%20words&flag",
                UrlSourceKind.PLAIN_TEXT_URL,
            ),
        ).single()
        val analysis = analyzer.analyze(candidate)

        assertThat(analysis.parsed?.parsedComponents?.host).isEqualTo("example.com")
        assertThat(analysis.parsed?.parsedComponents?.pathSegments).containsExactly("a b")
        assertThat(analysis.parsed?.parsedComponents?.queryParameters).containsExactly(
            UrlQueryParameter("x", "one"),
            UrlQueryParameter("x", "two words"),
            UrlQueryParameter("flag", null),
        ).inOrder()
    }

    @Test
    fun serializer_isStableAcrossParseSerializeParse() {
        val components = UrlComponents.create(
            scheme = "HTTPS",
            host = "EXAMPLE.COM",
            pathSegments = listOf("space value", "percent%value"),
            queryParameters = listOf(
                UrlQueryParameter("b", "two words"),
                UrlQueryParameter("a", "1"),
            ),
            fragment = "part one",
        )
        val serializer = CanonicalUrlSerializer()

        val once = serializer.serialize(components)
        val twice = serializer.serialize(components)

        assertThat(once).isEqualTo(twice)
        assertThat(once).isEqualTo("https://example.com/space%20value/percent%25value?b=two%20words&a=1#part%20one")
    }

    @Test
    fun standardsParserRejectsRegexLikeButInvalidPort_withContentFreeFailure() {
        val canary = "private-canary"
        val candidate = extractor.extract(
            UrlProcessingInput.create("https://example.com:99999/$canary", UrlSourceKind.PLAIN_TEXT_URL),
        ).single()
        val analysis = analyzer.analyze(candidate)

        assertThat(analysis.parsed).isNull()
        assertThat(analysis.failures.map { it.code }).contains(UrlFailureCode.PARSE_FAILED)
        assertThat(analysis.failures.toString()).doesNotContain(canary)
    }

    @Test
    fun deceptiveUserInfo_keepsActualHostAndRequiresApproval() {
        val input = UrlProcessingInput.create(
            "https://trusted.example@evil.example/path",
            UrlSourceKind.PLAIN_TEXT_URL,
        )
        val candidate = extractor.extract(input, "userinfo").single()
        val analysis = analyzer.analyze(candidate)

        assertThat(analysis.parsed?.parsedComponents?.host).isEqualTo("evil.example")
        assertThat(analysis.parsed?.parsedComponents?.userInfo).isEqualTo("trusted.example")
        assertThat(analysis.reviewGates.map { it.code }).containsExactly(UrlReviewCode.DECEPTIVE_HOST_REVIEW)
    }

    @Test
    fun schemelessCandidate_isNeverGivenAnInventedScheme() {
        val input = UrlProcessingInput.create("See example.com/help.", UrlSourceKind.PLAIN_TEXT)
        val candidate = extractor.extract(input, "schemeless").single()
        val result = UrlCanonicalizer().canonicalize(
            analyzer.analyze(candidate),
            CanonicalRevision(1),
            UrlReviewApprovals.create(approvedCodes = setOf(UrlReviewCode.SCHEMELESS_URL_REVIEW)),
        )

        assertThat(result.urlToken?.parsedComponents?.scheme).isNull()
        assertThat(result.urlToken?.finalText).isEqualTo("example.com/help")
    }

    @Test
    fun confusableIdn_remainsBlockedUntilExplicitReplacementHostIsApproved() {
        val input = UrlProcessingInput.create("https://раypal.com/", UrlSourceKind.PLAIN_TEXT_URL)
        val candidate = extractor.extract(input, "idn").single()
        val analysis = analyzer.analyze(candidate)
        val withoutHost = UrlCanonicalizer().canonicalize(
            analysis,
            CanonicalRevision(1),
            UrlReviewApprovals.create(approvedCodes = setOf(UrlReviewCode.IDN_CONFUSABLE_HOST_REVIEW)),
        )
        val withHost = UrlCanonicalizer().canonicalize(
            analysis,
            CanonicalRevision(1),
            UrlReviewApprovals.create(
                approvedCodes = setOf(UrlReviewCode.IDN_CONFUSABLE_HOST_REVIEW),
                approvedHostByTokenId = mapOf(candidate.tokenId to "paypal.com"),
            ),
        )

        assertThat(withoutHost.urlToken).isNull()
        assertThat(withoutHost.failures.map { it.code }).contains(UrlFailureCode.INVALID_APPROVED_HOST)
        assertThat(withHost.urlToken?.finalText).isEqualTo("https://paypal.com/")
    }

    @Test
    fun rawEmailIsInventoriedButNotMisparsedAsAnHttpUrl() {
        val candidate = extractor.extract(
            UrlProcessingInput.create("Contact person@example.com today.", UrlSourceKind.PLAIN_TEXT),
            "email",
        ).single()
        val analysis = analyzer.analyze(candidate)

        assertThat(candidate.kind).isEqualTo(UrlCandidateKind.EMAIL_LIKE)
        assertThat(analysis.parsed).isNull()
        assertThat(analysis.failures.map { it.code }).contains(UrlFailureCode.UNSUPPORTED_SCHEME)
    }
}
