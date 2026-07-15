package app.shareguard.block.url

import app.shareguard.core.model.UrlComponents
import app.shareguard.core.model.UrlQueryParameter
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/** Serializes a URL exclusively from approved parsed components; source strings are never edited in place. */
class CanonicalUrlSerializer {
    fun serialize(components: UrlComponents): String {
        val effectiveScheme = components.scheme ?: "https"
        val builder = HttpUrl.Builder()
            .scheme(effectiveScheme.lowercase())
            .host(components.host.lowercase())
        components.port?.let(builder::port)
        components.userInfo?.let { userInfo ->
            val separator = userInfo.indexOf(':')
            if (separator < 0) {
                builder.username(userInfo)
            } else {
                builder.username(userInfo.substring(0, separator))
                builder.password(userInfo.substring(separator + 1))
            }
        }
        builder.encodedPath("/")
        val pathSegments = components.pathSegments.toList()
        if (pathSegments != listOf("") && pathSegments.isNotEmpty()) {
            pathSegments.forEachIndexed { index, segment ->
                if (index == 0) builder.setPathSegment(0, segment) else builder.addPathSegment(segment)
            }
        }
        components.queryParameters.forEach { parameter ->
            builder.addQueryParameter(parameter.name, parameter.value)
        }
        builder.encodedFragment(components.fragment?.let { URI(null, null, it).rawFragment })
        val serializedAbsolute = builder.build().toString()
        val serialized = if (components.scheme == null) {
            serializedAbsolute.removePrefix("$effectiveScheme://")
        } else {
            serializedAbsolute
        }
        verifyStable(serialized, components)
        return serialized
    }

    fun verifyStable(serialized: String, expected: UrlComponents) {
        val reparsed = if (expected.scheme == null) {
            "https://$serialized".toHttpUrlOrNull()
        } else {
            serialized.toHttpUrlOrNull()
        } ?: throw IllegalStateException(UrlFailureCode.SERIALIZER_PARSE_FAILED.name)
        val actual = comparable(reparsed, expected.scheme == null)
        val wanted = ComparableComponents(
            scheme = expected.scheme?.lowercase(),
            userInfo = expected.userInfo,
            host = expected.host.lowercase(),
            port = expected.port,
            pathSegments = expected.pathSegments.toList().ifEmpty { listOf("") },
            queryParameters = expected.queryParameters.toList(),
            fragment = expected.fragment,
        )
        check(actual == wanted) { UrlFailureCode.SERIALIZER_COMPONENT_MISMATCH.name }
    }

    private fun comparable(url: HttpUrl, implicitScheme: Boolean): ComparableComponents = ComparableComponents(
        scheme = url.scheme.takeUnless { implicitScheme },
        userInfo = when {
            url.username.isEmpty() && url.password.isEmpty() -> null
            url.password.isEmpty() -> url.username
            else -> "${url.username}:${url.password}"
        },
        host = url.host,
        port = url.port.takeUnless { it == if (url.scheme == "https") 443 else 80 },
        pathSegments = url.pathSegments,
        queryParameters = (0 until url.querySize).map { index ->
            UrlQueryParameter(url.queryParameterName(index), url.queryParameterValue(index))
        },
        fragment = url.fragment,
    )

    private data class ComparableComponents(
        val scheme: String?,
        val userInfo: String?,
        val host: String,
        val port: Int?,
        val pathSegments: List<String>,
        val queryParameters: List<UrlQueryParameter>,
        val fragment: String?,
    )
}
