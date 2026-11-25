package one.rarebit.flags.ofrep

import kotlinx.coroutines.test.runTest
import one.rarebit.flags.core.BooleanFlag
import one.rarebit.flags.core.FlagPayload
import one.rarebit.flags.core.FlagState
import one.rarebit.flags.core.InMemoryLastGoodStore
import one.rarebit.flags.core.PayloadResult
import one.rarebit.flags.core.RejectReason
import one.rarebit.flags.core.StoredPayload
import one.rarebit.flags.core.StringFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class StandardFlagsClientTest {
    private val chat = BooleanFlag("chat", default = false)
    private val purchases = BooleanFlag("purchases", default = false, ceiling = true)
    private val maintenance = BooleanFlag("maintenance", default = false, killSwitch = true)
    private val hero = StringFlag("hero", default = "control", values = setOf("control", "savings"))
    private val definitions = listOf(chat, purchases, maintenance, hero)

    private fun body(version: String, vararg entries: String) =
        """{"flags":[${entries.joinToString(",")}],"metadata":{"version":"$version"}}"""

    private fun on(key: String, reason: String = "STATIC") = """{"key":"$key","value":true,"reason":"$reason","variant":"on"}"""
    private fun off(key: String, reason: String = "STATIC") = """{"key":"$key","value":false,"reason":"$reason","variant":"off"}"""

    /** A fetcher that answers from a queue and records the If-None-Match it was sent. */
    private class Script(vararg results: FetchResult) : FlagsFetcher {
        val queue = ArrayDeque(results.toList())
        val sent = mutableListOf<String?>()

        override suspend fun fetch(ifNoneMatch: String?): FetchResult {
            sent += ifNoneMatch
            return queue.removeFirst()
        }
    }

    private fun fresh(body: String, etag: String? = null) =
        FetchResult.Fresh(FlagPayload.parse(body), etag)

    @Test
    fun aFirstRunAppliesTheFirstPayloadThenWaitsForTheNextLaunch() = runTest {
        val store = InMemoryLastGoodStore()
        val script = Script(
            fresh(body("v1", on("chat"), """{"key":"hero","value":"savings","reason":"SPLIT","variant":"savings"}"""), "\"v1\""),
            fresh(body("v2", off("chat"))),
        )
        val flags = StandardFlagsClient(script, definitions, store, contextKey = "acct")
        flags.load()
        assertEquals(FlagState.NotLoaded, flags.state(chat))

        flags.refresh()
        assertEquals(FlagState.On, flags.state(chat))
        assertEquals("savings", flags.value(hero))
        assertEquals(FetchStatus.Updated(appliedNow = true), flags.status.value)

        flags.refresh()
        assertEquals(listOf(null, "\"v1\""), script.sent)
        assertEquals(FlagState.On, flags.state(chat), "fresh values wait for the next launch")
        assertEquals(FetchStatus.Updated(appliedNow = false), flags.status.value)
        assertEquals("v2", flags.pending.value?.version)

        // The next launch reads what was saved.
        val nextLaunch = StandardFlagsClient(Script(), definitions, store, contextKey = "acct")
        nextLaunch.load()
        assertEquals(FlagState.Off, nextLaunch.state(chat))
        assertEquals("control", nextLaunch.value(hero))
    }

    @Test
    fun killSwitchesApplyLive() = runTest {
        val store = InMemoryLastGoodStore(StoredPayload(body("v1", on("chat"), off("maintenance"), on("purchases")), "acct"))
        val script = Script(
            fresh(body("v2", off("chat", "KILL_SWITCH"), on("maintenance"), on("purchases"))),
            fresh(body("v3", on("chat"), off("maintenance"), on("purchases"))),
        )
        val flags = StandardFlagsClient(script, definitions, store, contextKey = "acct")
        flags.load()
        assertEquals(FlagState.On, flags.state(chat))

        flags.refresh()
        assertEquals(FlagState.Off, flags.state(chat), "a KILL_SWITCH reason applies live")
        assertEquals("KILL_SWITCH", flags.read(chat).reason)
        assertEquals(FlagState.On, flags.state(maintenance), "a declared kill switch applies live")

        flags.refresh()
        assertEquals(FlagState.Off, flags.state(chat), "releasing a reason-only kill switch waits for the next launch")
        assertEquals(FlagState.Off, flags.state(maintenance), "a declared kill switch applies live both ways")
    }

    @Test
    fun withoutFirstPayloadLiveOnlyKillSwitchesApplyBeforeTheNextLaunch() = runTest {
        val flags = StandardFlagsClient(
            Script(fresh(body("v1", on("chat"), off("hero", "KILL_SWITCH"), off("insights", "KILL_SWITCH")))),
            definitions,
            policy = ApplyPolicy(firstPayloadAppliesLive = false),
        )
        flags.refresh()
        assertEquals(FlagState.NotLoaded, flags.state(chat))
        assertEquals(FlagState.Off, flags.state(BooleanFlag("insights", default = true)))
        assertEquals("v1", flags.pending.value?.version)
    }

    @Test
    fun ceilingsOnlyLower() = runTest {
        val flags = StandardFlagsClient(Script(fresh(body("v1", on("purchases")))), definitions, contextKey = "acct")
        flags.refresh()
        assertEquals(FlagState.Off, flags.state(purchases))
        assertNull(flags.read(purchases).reason)
    }

    @Test
    fun aRejectedPayloadKeepsTheLastGoodOneAndBacksOff() = runTest {
        val time = TestTimeSource()
        val script = Script(
            fresh(body("v1", on("chat"))),
            fresh("""{"flags":{}}"""),
            fresh(body("v2", off("chat"))),
        )
        val flags = StandardFlagsClient(
            script,
            definitions,
            policy = ApplyPolicy(freshValuesAtNextLaunch = false),
            backoff = Backoff(initial = 10.seconds, max = 10.seconds),
            timeSource = time,
        )
        flags.refresh()
        flags.refresh()
        assertEquals(FetchStatus.Rejected(RejectReason.FlagsNotAnArray), flags.status.value)
        assertEquals(FlagState.On, flags.state(chat))

        assertEquals(RefreshOutcome.SkippedBackoff, flags.refresh())
        time += 11.seconds
        assertEquals(RefreshOutcome.Fetched, flags.refresh())
        assertEquals(FlagState.Off, flags.state(chat), "freshValuesAtNextLaunch = false applies at once")
    }

    @Test
    fun failuresHonourRetryAfter() = runTest {
        val time = TestTimeSource()
        val script = Script(FetchResult.Failed(503, 60.seconds, null), FetchResult.NotModified)
        val flags = StandardFlagsClient(script, definitions, backoff = Backoff(initial = 1.seconds), timeSource = time)
        flags.refresh()
        assertEquals(60.seconds, assertIs<FetchStatus.Failed>(flags.status.value).retryIn)
        time += 59.seconds
        assertEquals(RefreshOutcome.SkippedBackoff, flags.refresh())
        time += 2.seconds
        flags.refresh()
        assertEquals(FetchStatus.NotModified, flags.status.value)
    }

    @Test
    fun unauthorizedStopsUntilTheContextChanges() = runTest {
        val store = InMemoryLastGoodStore()
        val script = Script(
            fresh(body("a1", on("chat"))),
            FetchResult.Unauthorized(401),
            fresh(body("b1", off("chat"))),
        )
        val flags = StandardFlagsClient(script, definitions, store, contextKey = "user-a")
        flags.refresh()
        flags.refresh()
        assertEquals(FetchStatus.Stopped(401), flags.status.value)
        assertEquals(RefreshOutcome.SkippedStopped, flags.refresh())
        assertEquals(FlagState.On, flags.state(chat), "a 401 keeps the cached values")

        flags.setContext("user-b")
        assertEquals(FlagState.NotLoaded, flags.state(chat), "another user's values are dropped at once")
        assertNull(store.load())
        flags.refresh()
        assertEquals(FlagState.Off, flags.state(chat))
        assertEquals(listOf(null, "\"a1\"", null), script.sent)
    }

    @Test
    fun aStoredPayloadForAnotherContextIsIgnored() = runTest {
        val store = InMemoryLastGoodStore(StoredPayload(body("v1", on("chat")), "someone-else"))
        val flags = StandardFlagsClient(Script(), definitions, store, contextKey = "me")
        flags.load()
        assertEquals(FlagState.NotLoaded, flags.state(chat))
    }

    @Test
    fun aCorruptStoredPayloadIsIgnored() = runTest {
        val store = InMemoryLastGoodStore(StoredPayload("{\"flags\": tru", "me"))
        val flags = StandardFlagsClient(Script(), definitions, store, contextKey = "me")
        flags.load()
        assertNull(flags.payload.value)
    }

    @Test
    fun seedingFromABootstrapPayloadSetsTheEtag() = runTest {
        val script = Script(FetchResult.NotModified)
        val flags = StandardFlagsClient(script, definitions, contextKey = "acct")
        assertIs<PayloadResult.Accepted>(flags.seed(body("boot", on("chat"))))
        assertEquals(FlagState.On, flags.state(chat))
        flags.refresh()
        assertEquals(listOf<String?>("\"boot\""), script.sent)
        assertEquals(FlagState.On, flags.state(chat), "a 304 keeps the seeded values")
        assertIs<PayloadResult.Rejected>(flags.seed("nope"))
        assertEquals(FlagState.On, flags.state(chat))
    }
}
