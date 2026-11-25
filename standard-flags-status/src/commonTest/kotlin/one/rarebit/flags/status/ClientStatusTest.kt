package one.rarebit.flags.status

import kotlinx.coroutines.DelicateCoroutinesApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.test.runTest
import one.rarebit.flags.ofrep.ClientInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(DelicateCoroutinesApi::class)
class ClientStatusTest {
    private val requests = mutableListOf<HttpRequestData>()

    @Test
    fun parsesEveryStatus() {
        val recommended = ClientStatusParser.parse(
            """{"status":"upgrade_recommended","platform":"android","version":"0.4.0","min_version":"0.4.0","recommended":"0.5.0","enforce_at":"2026-12-08","store_url":"https://store.example/app","extra":1}""",
        )
        val upgrade = assertIs<ClientStatus.UpgradeRecommended>(recommended)
        assertEquals("2026-12-08", upgrade.enforceAt)
        assertEquals("0.5.0", upgrade.details.recommended)
        assertEquals("https://store.example/app", upgrade.details.storeUrl)

        val timestamp = assertIs<ClientStatus.UpgradeRecommended>(ClientStatusParser.parse("""{"status":"upgrade_recommended","enforce_at":"2026-12-08T00:00:00Z"}"""))
        assertNull(timestamp.enforceAt, "enforce_at is a date, so anything else is ignored")
        assertNull(timestamp.details.enforceAt)
        val required = assertIs<ClientStatus.UpgradeRequired>(ClientStatusParser.parse("""{"status":"upgrade_required","enforce_at":"2026-12-08"}"""))
        assertEquals("2026-12-08", required.details.enforceAt)
        assertIs<ClientStatus.Ok>(ClientStatusParser.parse("""{"status":"ok"}"""))
        assertIs<ClientStatus.UpgradeRequired>(ClientStatusParser.parse("""{"status":"upgrade_required","min_version":"0.4.0"}"""))
        assertIs<ClientStatus.Unknown>(ClientStatusParser.parse("""{"status":"unknown"}"""))
        assertIs<ClientStatus.Unknown>(ClientStatusParser.parse("""{"status":"blocked_forever"}"""), "an unknown status never blocks")
        assertIs<ClientStatus.Unknown>(ClientStatusParser.parse("""{"status":7,"min_version":1}"""))
        assertNull(ClientStatusParser.parse("[]"))
        assertNull(ClientStatusParser.parse("<html>"))
    }

    @Test
    fun parsesA426Body() {
        val status = ClientStatusParser.parseUpgradeRequired(
            """{"errors":[{"status":"426","code":"client_upgrade_required","meta":{"min_version":"0.4.0","store_url":"https://store.example/app"}}]}""",
        )
        assertEquals(StatusDetails(minVersion = "0.4.0", storeUrl = "https://store.example/app"), status?.details)
        assertNull(ClientStatusParser.parseUpgradeRequired("""{"errors":[{"code":"feature_disabled"}]}"""))
    }

    @Test
    fun checkSendsClientHeadersAndAFailureKeepsTheLastStatus() = runTest {
        var answer: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"status":"upgrade_required","store_url":"https://store.example/app"}"""
        val http = HttpClient(MockEngine { request -> requests += request; respond(answer.second, answer.first) })
        val status = ClientStatusClient(http, "https://api.example.test/client/status", ClientInfo("android", "0.3.0", 12))
        assertIs<ClientStatus.Unknown>(status.status.value)

        status.check()
        assertIs<ClientStatus.UpgradeRequired>(status.status.value)
        assertEquals("android", requests.single().headers[ClientInfo.HEADER_PLATFORM])
        assertEquals("0.3.0", requests.single().headers[ClientInfo.HEADER_VERSION])

        answer = HttpStatusCode.ServiceUnavailable to "down"
        assertNull(status.check())
        assertIs<ClientStatus.UpgradeRequired>(status.status.value)

        answer = HttpStatusCode.OK to """{"status":"ok"}"""
        status.check()
        assertIs<ClientStatus.Ok>(status.status.value)
    }

    @Test
    fun anOversizedStatusBodyIsRefusedUnreadAndKeepsTheStatus() = runTest {
        // Reading this body throws, so a null result (not a parse) proves it was never read.
        val http = HttpClient(
            MockEngine {
                val channel = GlobalScope.writer { error("the body was read") }.channel
                respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000000"))
            },
        )
        val status = ClientStatusClient(http, "https://x.test/client/status", null)
        assertNull(status.check())
        assertIs<ClientStatus.Unknown>(status.status.value)
    }

    @Test
    fun detectorFlipsTheStatusOnTheServersOwn426() = runTest {
        val status = ClientStatusClient(HttpClient(MockEngine { respond("""{"status":"ok"}""") }), "https://x.test/client/status", null)
        var body = """{"errors":[{"code":"client_upgrade_required","meta":{"min_version":"0.4.0"}}]}"""
        var code = HttpStatusCode.fromValue(426)
        val api = HttpClient(MockEngine { respond(body, code) }) {
            install(UpgradeRequiredDetector) { this.status = status }
        }

        code = HttpStatusCode.fromValue(426)
        body = "Upgrade to TLS"
        api.get("https://x.test/me")
        assertIs<ClientStatus.Unknown>(status.status.value, "a 426 without the server's error says nothing")

        body = """{"errors":[{"code":"client_upgrade_required","meta":{"min_version":"0.4.0"}}]}"""
        val response = api.get("https://x.test/me")
        assertEquals("0.4.0", assertIs<ClientStatus.UpgradeRequired>(status.status.value).details.minVersion)
        assertEquals(body, response.bodyAsText(), "the app can still read the body")
    }
}
