package one.rarebit.flags.ofrep

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import one.rarebit.flags.core.FlagPayload
import one.rarebit.flags.core.PayloadLimits
import one.rarebit.flags.core.PayloadResult
import one.rarebit.flags.core.RejectReason
import kotlin.time.Duration

/** What one bulk fetch returned. */
public sealed interface FetchResult {
    /** A 200: the body, validated, and the response's `ETag`. */
    public data class Fresh(val result: PayloadResult, val etag: String?) : FetchResult

    /** A 304: the cached payload is current. */
    public data object NotModified : FetchResult

    /** A 401 or 403: stop fetching until the context (sign-in) changes. */
    public data class Unauthorized(val status: Int) : FetchResult

    /** Anything else: a network error, a 5xx, a 429, an unexpected status. */
    public data class Failed(val status: Int?, val retryAfter: Duration?, val cause: Throwable?) : FetchResult
}

/** Fetches the bulk evaluation. [OfrepClient] is the HTTP one; tests can substitute their own. */
public fun interface FlagsFetcher {
    public suspend fun fetch(ifNoneMatch: String?): FetchResult
}

/**
 * The OFREP bulk call: `POST <url>` with `Content-Type: application/json`,
 * the `X-Client-*` headers and `{"context": {...}}`, honouring ETags.
 *
 * Authentication is the app's: install it on [http] (a bearer-token plugin),
 * or pass [headers]. The call opts out of `expectSuccess`, so a client that
 * throws on non-2xx still sees 304s and errors as results.
 *
 * @param url the bulk endpoint, such as `https://api.example.com/ofrep/v1/evaluate/flags`.
 * @param client the build, sent as headers and in the context.
 * @param targetingKey the install key, for a pre-auth (public audience)
 *   endpoint; authenticated endpoints ignore it.
 * @param headers extra request headers, read on every call.
 */
public class OfrepClient(
    private val http: HttpClient,
    private val url: String,
    private val client: ClientInfo?,
    private val limits: PayloadLimits = PayloadLimits(),
    private val targetingKey: () -> String? = { null },
    private val headers: () -> Map<String, String> = { emptyMap() },
) : FlagsFetcher {

    override suspend fun fetch(ifNoneMatch: String?): FetchResult = try {
        val response = http.post(url) {
            expectSuccess = false
            client?.applyTo(this.headers)
            for ((name, value) in headers()) header(name, value)
            if (ifNoneMatch != null) header(HttpHeaders.IfNoneMatch, ifNoneMatch)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody(TextContent(requestBody().toString(), ContentType.Application.Json))
        }
        val status = response.status
        when {
            status == HttpStatusCode.NotModified -> FetchResult.NotModified
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                FetchResult.Unauthorized(status.value)
            status == HttpStatusCode.OK -> {
                // Read at most one byte past the limit, so a huge body is refused unread.
                val bytes = response.bodyAsChannel().readBuffer(limits.maxBytes.toLong() + 1).readByteArray()
                val result = if (bytes.size > limits.maxBytes) {
                    PayloadResult.Rejected(RejectReason.TooLarge)
                } else {
                    FlagPayload.parse(bytes, limits)
                }
                FetchResult.Fresh(result, response.headers[HttpHeaders.ETag])
            }
            else -> FetchResult.Failed(
                status.value,
                Backoff.parseRetryAfter(response.headers[HttpHeaders.RetryAfter]),
                null,
            )
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
