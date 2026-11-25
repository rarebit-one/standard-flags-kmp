package one.rarebit.flags.ofrep

import kotlinx.coroutines.DelicateCoroutinesApi
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
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class)
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

        val result = assertIs<FetchResult.Fresh>(ofrep.fetch(ifNoneMatch = "\"old\"", maxBytes = 1024))
        assertEquals("\"abc\"", result.etag)
        assertEquals(body, result.body.decodeToString())
        assertEquals(client, ofrep.client)

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
        assertFalse(context.getValue("build").jsonPrimitive.isString)
    }

    @Test
    fun mapsStatuses() = runTest {
        var status = HttpStatusCode.NotModified
        var headers = headersOf()
        val ofrep = OfrepClient(engine { respond("", status, headers) }, "https://x.test/f", client)

        assertEquals(FetchResult.NotModified, ofrep.fetch("\"abc\"", 1024))
        status = HttpStatusCode.Unauthorized
        assertEquals(FetchResult.Unauthorized(401), ofrep.fetch(null, 1024))
        status = HttpStatusCode.Forbidden
        assertEquals(FetchResult.Unauthorized(403), ofrep.fetch(null, 1024))
        status = HttpStatusCode.TooManyRequests
        headers = headersOf(HttpHeaders.RetryAfter, "120")
        assertEquals(FetchResult.Failed(429, 120.seconds, null), ofrep.fetch(null, 1024))
        status = HttpStatusCode.UnsupportedMediaType
        headers = headersOf()
        assertEquals(FetchResult.Failed(415, null, null), ofrep.fetch(null, 1024))
    }

    @Test
    fun aClientThatExpectsSuccessStillSeesResults() = runTest {
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.NotModified) }) { expectSuccess = true }
        assertEquals(FetchResult.NotModified, OfrepClient(http, "https://x.test/f", client).fetch("\"abc\"", 1024))
    }

    @Test
    fun refusesADeclaredOversizedBodyUnread() = runTest {
        // Reading this body throws, so TooLarge (not Failed) proves it was never read.
        val http = engine {
            val channel = GlobalScope.writer { error("the body was read") }.channel
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "100"))
        }
        assertEquals(FetchResult.TooLarge, OfrepClient(http, "https://x.test/f", client).fetch(null, maxBytes = 50))
    }

    @Test
    fun stopsReadingAnUndeclaredStreamPastTheCap() = runTest {
        var produced = 0
        val http = engine {
            val channel = GlobalScope.writer {
                // Far more than the cap, with no Content-Length: reading must stop at cap + 1.
                repeat(1_000_000) {
                    channel.writeByte('x'.code.toByte())
                    channel.flush()
                    produced++
                }
            }.channel
            respond(channel, HttpStatusCode.OK)
        }
        assertEquals(FetchResult.TooLarge, OfrepClient(http, "https://x.test/f", client).fetch(null, maxBytes = 64))
        assertTrue(produced < 1_000_000, "the stream was read to the end ($produced bytes)")
    }

    @Test
    fun readsABodyOfExactlyTheCap() = runTest {
        val exact = "y".repeat(64)
        val http = engine { respond(ByteReadChannel(exact), HttpStatusCode.OK) }
        assertEquals(exact, assertIs<FetchResult.Fresh>(OfrepClient(http, "https://x.test/f", client).fetch(null, 64)).body.decodeToString())
    }

    @Test
    fun theBufferGrowsToTheBodyAndAnUnboundedLimitDoesNotOverflow() = runTest {
        val large = "z".repeat(100_000)
        val growing = engine { respond(ByteReadChannel(large), HttpStatusCode.OK) }
        val read = assertIs<FetchResult.Fresh>(OfrepClient(growing, "https://x.test/f", client).fetch(null, 200_000))
        assertEquals(large, read.body.decodeToString())

        val small = engine { respond(ByteReadChannel(body), HttpStatusCode.OK) }
        val unbounded = assertIs<FetchResult.Fresh>(OfrepClient(small, "https://x.test/f", client).fetch(null, Int.MAX_VALUE))
        assertEquals(body, unbounded.body.decodeToString())

        val declared = engine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.length.toString())) }
        assertEquals(body, assertIs<FetchResult.Fresh>(OfrepClient(declared, "https://x.test/f", client).fetch(null, Int.MAX_VALUE)).body.decodeToString())
    }

    @Test
    fun networkErrorsAreFailures() = runTest {
        val broken = OfrepClient(engine { error("connection reset") }, "https://x.test/f", client)
        val failed = assertIs<FetchResult.Failed>(broken.fetch(null, 1024))
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
    fun clientInfoSaysWhetherTheServerWillRecogniseIt() {
        assertTrue(ClientInfo("Android", "0.5.0", 3).isRecognised)
        assertFalse(ClientInfo("android", "1.4.0 (381)").isRecognised)
        assertFalse(ClientInfo("watch", "1.0.0").isRecognised)
        assertFalse(ClientInfo("ios").isRecognised)
        assertEquals("android/0.5.0/3", ClientInfo("Android", "0.5.0", 3).fingerprint)
        assertEquals("ios//", ClientInfo("ios").fingerprint)
    }

    @Test
    fun clientInfoComparesByValue() {
        val info = ClientInfo("android", "0.5.0", 381)
        assertEquals(ClientInfo("android", "0.5.0", 381), info)
        assertEquals(ClientInfo("android", "0.5.0", 381).hashCode(), info.hashCode())
        assertTrue(info != ClientInfo("android", "0.5.0", 382))
        assertEquals(ClientInfo("ios"), ClientInfo("ios", version = null, build = null))
        assertEquals("ClientInfo(platform=android, version=0.5.0, build=381)", info.toString())
        assertEquals("ClientInfo(platform=ios, version=null, build=null)", ClientInfo("ios").toString())
        assertFailsWith<IllegalArgumentException> { ClientInfo(" ") }
        assertFailsWith<IllegalArgumentException> { ClientInfo("android", "1.0.0", -1) }
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
    fun parsesRetryAfterInEveryHttpDateForm() {
        assertEquals(120.seconds, Backoff.parseRetryAfter("120"))
        assertNull(Backoff.parseRetryAfter("soon"))
        assertNull(Backoff.parseRetryAfter(null))
        // 1994-11-06T08:49:37Z, the RFC 9110 example, in its three forms.
        val then = 784_111_777_000L
        val now = GMTDate(then - 60_000L)
        assertEquals(60_000.milliseconds, Backoff.parseRetryAfter("Sun, 06 Nov 1994 08:49:37 GMT", now))
        assertEquals(60_000.milliseconds, Backoff.parseRetryAfter("Sunday, 06-Nov-94 08:49:37 GMT", now))
        assertEquals(60_000.milliseconds, Backoff.parseRetryAfter("Sun Nov  6 08:49:37 1994", now))
        assertEquals(60_000.milliseconds, Backoff.parseRetryAfter(GMTDate(then).toHttpDate(), now))
        assertEquals(0.milliseconds, Backoff.parseRetryAfter("Sun, 06 Nov 1994 08:49:37 GMT", GMTDate(then + 5_000)))
        assertNull(Backoff.parseRetryAfter("Sun, 32 Nov 1994 08:49:37 GMT", now))
    }
}
