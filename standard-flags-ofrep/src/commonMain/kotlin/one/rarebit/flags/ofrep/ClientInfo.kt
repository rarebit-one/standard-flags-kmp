package one.rarebit.flags.ofrep

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HeadersBuilder

/**
 * Which build is asking: sent as `X-Client-Platform`, `X-Client-Version` and
 * `X-Client-Build` on every request, and in the OFREP context. The server uses
 * them to withhold flags a build can't handle (`min_client`) and to answer
 * `/client/status`. A missing or unparseable version counts as an unknown
 * build, which never gets a `min_client` flag and is never forced to upgrade.
 *
 * @property platform `android`, `ios`, `web` or `desktop` (the server's allowlist).
 * @property version the app's SemVer version, such as `0.5.0`.
 * @property build the build number, a non-negative integer.
 */
public data class ClientInfo(
    val platform: String,
    val version: String? = null,
    val build: Long? = null,
) {
    init {
        require(platform.isNotBlank()) { "platform must not be blank" }
        require(build == null || build >= 0) { "build must not be negative" }
    }

    /** Sets the three headers on [headers], leaving any the request already has. */
    public fun applyTo(headers: HeadersBuilder) {
        if (!headers.contains(HEADER_PLATFORM)) headers.append(HEADER_PLATFORM, platform)
        if (version != null && !headers.contains(HEADER_VERSION)) headers.append(HEADER_VERSION, version)
        if (build != null && !headers.contains(HEADER_BUILD)) headers.append(HEADER_BUILD, build.toString())
    }

    public companion object {
        public const val HEADER_PLATFORM: String = "X-Client-Platform"
        public const val HEADER_VERSION: String = "X-Client-Version"
        public const val HEADER_BUILD: String = "X-Client-Build"
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
