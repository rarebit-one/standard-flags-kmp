package one.rarebit.flags.ofrep

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HeadersBuilder
import one.rarebit.flags.core.Version

/**
 * Which build is asking: sent as `X-Client-Platform`, `X-Client-Version` and
 * `X-Client-Build` on every request, and in the OFREP context. The server uses
 * them to withhold flags a build can't handle (`min_client`) and to answer
 * `/client/status`. A missing or unparseable version counts as an unknown
 * build, which never gets a `min_client` flag and is never forced to upgrade.
 *
 * The server never errors on a bad value; it silently treats the build as
 * unknown. So a version like `1.4.0 (381)` ships without complaint and quietly
 * loses every `min_client` flag. Check [isRecognised] in a debug build or a test.
 *
 * A regular class, not a data class, so a field can be added later without
 * changing `copy` or `componentN`. When adding one, keep the previous
 * constructor as a secondary constructor (for example
 * `@Deprecated(level = DeprecationLevel.HIDDEN)`), so binaries built against
 * an earlier version still link.
 *
 * @property platform `android`, `ios`, `web` or `desktop` (the server's default allowlist).
 * @property version the app's SemVer version, such as `0.5.0`.
 * @property build the build number, a non-negative integer.
 */
public class ClientInfo(
    public val platform: String,
    public val version: String? = null,
    public val build: Long? = null,
) {
    init {
        require(platform.isNotBlank()) { "platform must not be blank" }
        require(build == null || build >= 0) { "build must not be negative" }
    }

    /**
     * Whether the server will read this as a known build: a platform on its
     * default allowlist and a version that parses as SemVer (or its shorthands).
     */
    public val isRecognised: Boolean
        get() = platform.lowercase() in KNOWN_PLATFORMS && Version.parse(version) != null

    /** Identifies the build for the last-good store: a payload saved by another build isn't applied. */
    public val fingerprint: String get() = "${platform.lowercase()}/${version.orEmpty()}/${build ?: ""}"

    /** Sets the three headers on [headers], leaving any the request already has. */
    public fun applyTo(headers: HeadersBuilder) {
        if (!headers.contains(HEADER_PLATFORM)) headers.append(HEADER_PLATFORM, platform)
        if (version != null && !headers.contains(HEADER_VERSION)) headers.append(HEADER_VERSION, version)
        if (build != null && !headers.contains(HEADER_BUILD)) headers.append(HEADER_BUILD, build.toString())
    }

    override fun equals(other: Any?): Boolean =
        other is ClientInfo && platform == other.platform && version == other.version && build == other.build

    override fun hashCode(): Int {
        var result = platform.hashCode()
        result = 31 * result + (version?.hashCode() ?: 0)
        result = 31 * result + (build?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "ClientInfo(platform=$platform, version=$version, build=$build)"

    public companion object {
        public const val HEADER_PLATFORM: String = "X-Client-Platform"
        public const val HEADER_VERSION: String = "X-Client-Version"
        public const val HEADER_BUILD: String = "X-Client-Build"

        /** The server's default platform allowlist. */
        public val KNOWN_PLATFORMS: Set<String> = setOf("android", "ios", "web", "desktop")
    }
}

/** Configuration for [ClientHeaders]. */
public class ClientHeadersConfig {
    /** The client to describe, read on every request; null sends nothing. */
    public var client: () -> ClientInfo? = { null }

    /** Sends a fixed [ClientInfo]. */
    public fun client(info: ClientInfo) {
        client = { info }
    }
}

/**
 * A Ktor client plugin that adds the `X-Client-*` headers to every request,
 * so the app's own API calls carry them too (the server's 426 guard and
 * compat-window counts read them):
 *
 * ```
 * HttpClient { install(ClientHeaders) { client(ClientInfo("android", "0.5.0", 381)) } }
 * ```
 */
public val ClientHeaders: ClientPlugin<ClientHeadersConfig> =
    createClientPlugin("StandardFlagsClientHeaders", ::ClientHeadersConfig) {
        val provide = pluginConfig.client
        onRequest { request, _ ->
            provide()?.applyTo(request.headers)
        }
    }
