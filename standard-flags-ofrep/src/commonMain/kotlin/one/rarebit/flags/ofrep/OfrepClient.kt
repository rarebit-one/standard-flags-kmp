package one.rarebit.flags.ofrep

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration

/** What one bulk fetch returned. */
public sealed interface FetchResult {
    /**
     * A 200: the raw body (at most the requested `maxBytes`) and the response's
     * entity tag. The [StandardFlagsClient] validates it, so one set of limits
     * applies to fetched, seeded and stored payloads alike.
     */
    public class Fresh(public val body: ByteArray, public val etag: String?) : FetchResult {
        override fun toString(): String = "Fresh(${body.size} bytes, etag=$etag)"
    }

    /** A 200 whose body is larger than the requested `maxBytes`; it wasn't read. */
    public data object TooLarge : FetchResult

    /** A 304: the cached payload is current. */
    public data object NotModified : FetchResult

    /** A 401 or 403: stop fetching until the context (sign-in) changes. */
    public data class Unauthorized(val status: Int) : FetchResult

    /**
     * Anything else: a 5xx, a 429, an unexpected status ([status] set), or a
     * network or client-side error ([status] null, [cause] set).
     */
    public data class Failed(val status: Int?, val retryAfter: Duration?, val cause: Throwable?) : FetchResult
}

/** Fetches the bulk evaluation. [OfrepClient] is the HTTP one; tests can substitute their own. */
public interface FlagsFetcher {
    /** The build the fetches describe, which also keys the stored payload. Null: none. */
    public val client: ClientInfo? get() = null

    /** Fetches, reading at most [maxBytes] of a 200's body. */
    public suspend fun fetch(ifNoneMatch: String?, maxBytes: Int): FetchResult
}

/**
 * The OFREP bulk call: `POST <url>` with `Content-Type: application/json`,
 * the `X-Client-*` headers and `{"context": {...}}`, honouring ETags.
 *
 * Authentication is the app's: install it on [http] (a bearer-token plugin),
 * or pass [headers]. The call opts out of `expectSuccess`, so a client that
 * throws on non-2xx still sees 304s and errors as results. The body is
 * streamed and capped: a `Content-Length` over the limit is refused unread,
 * and a body without one is read only up to the limit.
 *
 * Install a request timeout on [http] (Ktor's `HttpTimeout`): the
 * [StandardFlagsClient] never holds its lock while a fetch waits, but a
 * request with no timeout can wait as long as the engine allows.
 *
 * @param url the bulk endpoint, such as `https://api.example.com/ofrep/v1/evaluate/flags`.
 * @param client the build, sent as headers and in the context. Pass it even when
 *   the [ClientHeaders] plugin already sets the headers: it also keys the stored
 *   payload by build, and without it a payload saved by another build could apply.
 * @param targetingKey the install key, for a pre-auth (public audience)
 *   endpoint; authenticated endpoints ignore it.
 * @param headers extra request headers, read on every call.
 */
public class OfrepClient(
    private val http: HttpClient,
    private val url: String,
    override val client: ClientInfo?,
    private val targetingKey: () -> String? = { null },
    private val headers: () -> Map<String, String> = { emptyMap() },
) : FlagsFetcher {

    override suspend fun fetch(ifNoneMatch: String?, maxBytes: Int): FetchResult = try {
        http.preparePost(url) {
            expectSuccess = false
            client?.applyTo(this.headers)
            for ((name, value) in headers()) header(name, value)
            if (ifNoneMatch != null) header(HttpHeaders.IfNoneMatch, ifNoneMatch)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody(TextContent(requestBody().toString(), ContentType.Application.Json))
        }.execute { response ->
            val status = response.status
            when {
                status == HttpStatusCode.NotModified -> FetchResult.NotModified
                status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                    FetchResult.Unauthorized(status.value)
                status == HttpStatusCode.OK -> {
                    val body = response.readBounded(maxBytes)
                    if (body == null) FetchResult.TooLarge else FetchResult.Fresh(body, response.headers[HttpHeaders.ETag])
                }
                else -> FetchResult.Failed(
                    status.value,
                    Backoff.parseRetryAfter(response.headers[HttpHeaders.RetryAfter]),
                    null,
                )
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        FetchResult.Failed(null, null, error)
    }

    private fun requestBody(): JsonObject = buildJsonObject {
        put(
            "context",
            buildJsonObject {
                targetingKey()?.let { put("targetingKey", it) }
                if (client != null) {
                    put("platform", client.platform)
                    client.version?.let { put("appVersion", it) }
                    client.build?.let { put("build", JsonPrimitive(it)) }
                }
            },
        )
    }
}
