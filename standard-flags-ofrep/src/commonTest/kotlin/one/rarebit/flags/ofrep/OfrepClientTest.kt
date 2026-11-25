package one.rarebit.flags.ofrep

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.toHttpDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.rarebit.flags.core.PayloadLimits
import one.rarebit.flags.core.PayloadResult
import one.rarebit.flags.core.RejectReason
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class OfrepClientTest {
    private val body = """{"flags":[{"key":"chat","value":true,"reason":"STATIC","variant":"on"}],"metadata":{"version":"abc"}}"""
    private val requests = mutableListOf<HttpRequestData>()
    private val client = ClientInfo("android", "0.5.0", 381)

    private fun engine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine { request -> requests += request; handler(request) })

    @Test
    fun postsJsonWithClientHeadersAndContext() = runTest {
        val http = engine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"abc\"")) }
        val ofrep = OfrepClient(http, "https://api.example.test/ofrep/v1/evaluate/flags", client, targetingKey = { "install-1" })

        val result = assertIs<FetchResult.Fresh>(ofrep.fetch(ifNoneMatch = "\"old\""))
        assertEquals("\"abc\"", result.etag)
        assertIs<PayloadResult.Accepted>(result.result)

        val request = requests.single()
        assertEquals("POST", request.method.value)
        assertEquals("android", request.headers[ClientInfo.HEADER_PLATFORM])
        assertEquals("0.5.0", request.headers[ClientInfo.HEADER_VERSION])
        assertEquals("381", request.headers[ClientInfo.HEADER_BUILD])
        assertEquals("\"old\"", request.headers[HttpHeaders.IfNoneMatch])
        assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
        val context = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject.getValue("context").jsonObject
        assertEquals("install-1", context.getValue("targetingKey").jsonPrimitive.content)
        assertEquals("android", context.getValue("platform").jsonPrimitive.content)
        assertEquals("0.5.0", context.getValue("appVersion").jsonPrimitive.content)
        assertEquals("381", context.getValue("build").jsonPrimitive.content)
        assertTrue(context.getValue("build").jsonPrimitive.isString.not())
    }

    @Test
    fun mapsStatuses() = runTest {
        var status = HttpStatusCode.NotModified
        var headers = headersOf()
        val ofrep = OfrepClient(engine { respond("", status, headers) }, "https://x.test/f", client)

        assertEquals(FetchResult.NotModified, ofrep.fetch("\"abc\""))
        status = HttpStatusCode.Unauthorized
        assertEquals(FetchResult.Unauthorized(401), ofrep.fetch(null))
        status = HttpStatusCode.Forbidden
        assertEquals(FetchResult.Unauthorized(403), ofrep.fetch(null))
        status = HttpStatusCode.TooManyRequests
        headers = headersOf(HttpHeaders.RetryAfter, "120")
        assertEquals(FetchResult.Failed(429, 120.seconds, null), ofrep.fetch(null))
        status = HttpStatusCode.UnsupportedMediaType
        headers = headersOf()
        assertEquals(FetchResult.Failed(415, null, null), ofrep.fetch(null))
    }

    @Test
    fun aClientThatExpectsSuccessStillSeesResults() = runTest {
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.NotModified) }) { expectSuccess = true }
        assertEquals(FetchResult.NotModified, OfrepClient(http, "https://x.test/f", client).fetch("\"abc\""))
    }

    @Test
    fun refusesAnOversizedBodyAndNetworkErrorsAreFailures() = runTest {
        val big = OfrepClient(engine { respond("x".repeat(100), HttpStatusCode.OK) }, "https://x.test/f", client, PayloadLimits(maxBytes = 50))
        assertEquals(FetchResult.Fresh(PayloadResult.Rejected(RejectReason.TooLarge), null), big.fetch(null))

        val broken = OfrepClient(engine { error("connection reset") }, "https://x.test/f", client)
        val failed = assertIs<FetchResult.Failed>(broken.fetch(null))
        assertNull(failed.status)
    }

    @Test
    fun headerPluginTagsEveryRequestWithoutOverriding() = runTest {
        val http = HttpClient(MockEngine { request -> requests += request; respond("{}") }) {
            install(ClientHeaders) { client(ClientInfo("ios", "1.4.0")) }
        }
        http.get("https://x.test/me")
        http.get("https://x.test/me") { headers.append(ClientInfo.HEADER_PLATFORM, "desktop") }
        assertEquals("ios", requests[0].headers[ClientInfo.HEADER_PLATFORM])
        assertEquals("1.4.0", requests[0].headers[ClientInfo.HEADER_VERSION])
        assertNull(requests[0].headers[ClientInfo.HEADER_BUILD])
        assertEquals(listOf("desktop"), requests[1].headers.getAll(ClientInfo.HEADER_PLATFORM))
    }

    @Test
    fun backoffGrowsWithJitterAndHonoursRetryAfter() {
        val backoff = Backoff(initial = 1.seconds, max = 8.seconds, random = Random(7))
        for (failures in 1..6) {
            val ceiling = minOf(8_000L, 1_000L shl (failures - 1))
            val delay = backoff.delay(failures).inWholeMilliseconds
            assertTrue(delay in ceiling / 2..ceiling, "failure $failures waited $delay")
        }
        assertEquals(30.seconds, backoff.delay(1, retryAfter = 30.seconds))
        assertEquals(backoff.maxRetryAfter, backoff.delay(1, retryAfter = 1000.seconds * 1000))
    }

    @Test
    fun parsesRetryAfter() {
        assertEquals(120.seconds, Backoff.parseRetryAfter("120"))
        assertNull(Backoff.parseRetryAfter("soon"))
        assertNull(Backoff.parseRetryAfter(null))
        val now = io.ktor.util.date.GMTDate(1_700_000_000_000L)
        val inAMinute = io.ktor.util.date.GMTDate(1_700_000_060_000L)
        val header = inAMinute.toHttpDate()
        assertEquals(60_000.milliseconds, Backoff.parseRetryAfter(header, now))
    }
}
