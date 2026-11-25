package one.rarebit.flags.ofrep

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.rarebit.flags.core.BooleanFlag
import one.rarebit.flags.core.FlagState
import one.rarebit.flags.core.InMemoryLastGoodStore
import one.rarebit.flags.core.LastGoodStore
import one.rarebit.flags.core.PayloadLimits
import one.rarebit.flags.core.PayloadResult
import one.rarebit.flags.core.RejectReason
import one.rarebit.flags.core.StoredPayload
import one.rarebit.flags.core.StringFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class StandardFlagsClientTest {
    private val chat = BooleanFlag("chat", default = false)
    private val purchases = BooleanFlag("purchases", default = false, ceiling = true)
    private val maintenance = BooleanFlag("maintenance", default = false, killSwitch = true)
    private val hero = StringFlag("hero", default = "control", values = setOf("control", "savings"))
    private val definitions = listOf(chat, purchases, maintenance, hero)
    private val build = ClientInfo("android", "0.5.0", 381)

    private fun body(version: String, vararg entries: String) =
        """{"flags":[${entries.joinToString(",")}],"metadata":{"version":"$version"}}"""

    private fun on(key: String, reason: String = "STATIC") = """{"key":"$key","value":true,"reason":"$reason","variant":"on"}"""
    private fun off(key: String, reason: String = "STATIC") = """{"key":"$key","value":false,"reason":"$reason","variant":"off"}"""

    private fun fresh(body: String, etag: String? = null) = FetchResult.Fresh(body.encodeToByteArray(), etag)

    /** A fetcher that answers from a queue (an answer may wait on a gate) and records what it was sent. */
    private inner class Script(vararg results: Any) : FlagsFetcher {
        val queue = ArrayDeque(results.toList())
        val sent = mutableListOf<String?>()
        val maxBytes = mutableListOf<Int>()
        override val client: ClientInfo = build

        override suspend fun fetch(ifNoneMatch: String?, maxBytes: Int): FetchResult {
            sent += ifNoneMatch
            this.maxBytes += maxBytes
            return when (val next = queue.removeFirst()) {
                is CompletableDeferred<*> -> next.await() as FetchResult
                else -> next as FetchResult
            }
        }
    }

    private fun stored(body: String, key: String? = "acct", client: String? = build.fingerprint) =
        StoredPayload(body, key, client)

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
        assertEquals(FetchStatus.Updated(appliedNow = true, appliedLive = emptySet(), saved = true), flags.status.value)

        val outcome = flags.refresh()
        assertEquals(listOf(null, "\"v1\""), script.sent)
        assertEquals(FlagState.On, flags.state(chat), "fresh values wait for the next launch")
        assertEquals(RefreshOutcome.Completed(FetchStatus.Updated(false, emptySet(), true)), outcome)
        assertEquals("v2", flags.pending.value?.version)

        // The next launch reads what was saved.
        val nextLaunch = StandardFlagsClient(Script(), definitions, store, contextKey = "acct")
        nextLaunch.load()
        assertEquals(FlagState.Off, nextLaunch.state(chat))
        assertEquals("control", nextLaunch.value(hero))
    }

    @Test
    fun killSwitchesApplyLive() = runTest {
        val store = InMemoryLastGoodStore(stored(body("v1", on("chat"), off("maintenance"), on("purchases"))))
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
        assertEquals(setOf("chat", "maintenance"), assertIs<FetchStatus.Updated>(flags.status.value).appliedLive)

        flags.refresh()
        assertEquals(FlagState.Off, flags.state(chat), "releasing a reason-only kill switch waits for the next launch")
        assertEquals(FlagState.Off, flags.state(maintenance), "a declared kill switch applies live both ways")
    }

    /** A store whose save throws, or waits on a gate. */
    private class FlakyStore(var stored: StoredPayload? = null) : LastGoodStore {
        var failSave = false
        var failLoad = false
        var clearFailures = 0
        var gate: CompletableDeferred<Unit>? = null
        val saving = CompletableDeferred<Unit>()

        override suspend fun load(): StoredPayload? {
            if (failLoad) error("disk read failed")
            return stored
        }

        override suspend fun save(payload: StoredPayload) {
            saving.complete(Unit)
            gate?.await()
            if (failSave) error("disk full")
            stored = payload
        }

        override suspend fun clear() {
            if (clearFailures > 0) {
                clearFailures--
                error("disk busy")
            }
            stored = null
        }
    }

    @Test
    fun aSignOutWhileASaveIsBlockedStillClearsTheStoreEvenIfItsScopeIsCancelled() = runTest {
        val store = FlakyStore()
        store.gate = CompletableDeferred()
        val flags = StandardFlagsClient(Script(fresh(body("a1", on("chat")))), definitions, store, contextKey = "user-a")
        val refresh = launch { flags.refresh() }
        store.saving.await() // a1 is applied and its save is blocked inside the store

        val signOut = launch { flags.setContext(null) }
        runCurrent()
        assertEquals(FlagState.NotLoaded, flags.state(chat), "the old values went at once")
        signOut.cancel() // the scope that signed out is torn down while the clear waits on the save
        store.gate!!.complete(Unit)
        refresh.join()
        signOut.join()
        runCurrent()
        assertNull(store.stored, "user A's record didn't outlive the sign-out")
        assertEquals(FlagState.NotLoaded, flags.state(chat))
    }

    @Test
    fun aFailedClearIsRetriedThenReportedAndRetriedByLoad() = runTest {
        val store = FlakyStore(stored(body("a1", on("chat")), key = "user-a"))
        store.clearFailures = 2
        val flags = StandardFlagsClient(Script(), definitions, store, contextKey = "user-a")
        flags.load()
        flags.setContext("user-b")
        assertNull(store.stored, "two failures, then the third attempt cleared it")

        store.stored = stored(body("b1", on("chat")), key = "user-b")
        store.clearFailures = 10
        flags.setContext(null)
        assertEquals(StoreOperation.Clear, flags.storeError.value?.operation)
        assertEquals("user-b", store.stored?.contextKey, "still there after every attempt failed")
        store.clearFailures = 0
        flags.load()
        assertNull(store.stored, "load retried the owed clear")
    }

    @Test
    fun aRecordThatCantBeReadIsClearedOnSignOut() = runTest {
        val store = FlakyStore(stored(body("a1", on("chat")), key = "user-a"))
        val flags = StandardFlagsClient(Script(), definitions, store, contextKey = "user-a")
        flags.load()
        store.failLoad = true
        flags.setContext(null)
        assertNull(store.stored)
    }

    @Test
    fun aSeedRacingARefreshNeverUndoesTheFetchedKillSwitch() = runTest {
        val store = FlakyStore(stored(body("v1", on("chat"))))
        val flags = StandardFlagsClient(Script(fresh(body("v2", off("chat", "KILL_SWITCH")))), definitions, store, contextKey = "acct")
        flags.load()
        store.gate = CompletableDeferred()
        val refresh = launch { flags.refresh() }
        store.saving.await() // the kill switch is live; its save is blocked
        assertEquals(FlagState.Off, flags.state(chat))

        val seeded = async { flags.seed(body("boot", on("chat"))) }
        runCurrent()
        assertEquals(FlagState.Off, flags.state(chat), "the older bootstrap body didn't undo the kill switch")
        store.gate!!.complete(Unit)
        refresh.join()
        assertIs<PayloadResult.Accepted>(seeded.await())
        assertEquals(FlagState.Off, flags.state(chat))
        assertEquals("v2", flags.pending.value?.version)
        assertTrue(store.stored!!.body.contains("\"v2\""))
    }

    @Test
    fun aSeedOvertakenByAFetchNeverBecomesThePendingOne() = runTest {
        val store = FlakyStore()
        store.gate = CompletableDeferred()
        val flags = StandardFlagsClient(Script(fresh(body("v2", off("chat", "KILL_SWITCH")))), definitions, store, contextKey = "acct")
        val seeded = launch { flags.seed(body("boot", on("chat"))) }
        store.saving.await() // the seed applied and its save is blocked
        assertEquals(FlagState.On, flags.state(chat))
        val refresh = launch { flags.refresh() }
        runCurrent()
        assertEquals(FlagState.Off, flags.state(chat), "the fetched kill switch applied live")
        store.gate!!.complete(Unit)
        seeded.join()
        refresh.join()
        assertEquals("v2", flags.pending.value?.version)
        assertTrue(store.stored!!.body.contains("\"v2\""))
        assertEquals(FlagState.Off, flags.state(chat))
    }

    @Test
    fun aSeedAfterAFetchOrA304IsIgnored() = runTest {
        val flags = StandardFlagsClient(Script(fresh(body("v2", off("chat")))), definitions, contextKey = "acct")
        flags.refresh()
        flags.seed(body("boot", on("chat")))
        assertEquals(FlagState.Off, flags.state(chat))
        assertEquals("v2", flags.pending.value?.version)

        val store = InMemoryLastGoodStore(stored(body("v3", off("chat"))))
        val confirmed = StandardFlagsClient(Script(FetchResult.NotModified), definitions, store, contextKey = "acct")
        confirmed.refresh()
        confirmed.seed(body("boot", on("chat")))
        assertEquals(FlagState.Off, confirmed.state(chat), "a 304 confirmed v3; the bootstrap body is no newer")
    }

    @Test
    fun aJoinerWhoseOwnerIsCancelledRunsItsOwnFetchOnce() = runTest {
        val gate = CompletableDeferred<FetchResult>()
        val script = Script(gate, fresh(body("v1", on("chat"))))
        val flags = StandardFlagsClient(script, definitions)
        val owner = launch { flags.refresh() }
        runCurrent()
        val joiner = async { flags.refresh() }
        runCurrent()
        owner.cancel()
        val outcome = joiner.await()
        assertIs<RefreshOutcome.Completed>(outcome)
        assertEquals(2, script.sent.size, "one fetch for the owner, one for the joiner, no spin")
        assertEquals(FlagState.On, flags.state(chat))
    }

    @Test
    fun aKillSwitchAppliesEvenWhenTheSaveFailsAndTheNextFetchIsNotA304() = runTest {
        val store = FlakyStore(stored(body("v1", on("chat"))))
        val script = Script(fresh(body("v2", off("chat", "KILL_SWITCH")), "\"v2\""), FetchResult.NotModified)
        val flags = StandardFlagsClient(script, definitions, store, contextKey = "acct")
        flags.load()
        store.failSave = true

        val outcome = flags.refresh()
        assertEquals(FlagState.Off, flags.state(chat), "the kill switch applied without waiting on the store")
        assertEquals(RefreshOutcome.Completed(FetchStatus.Updated(false, setOf("chat"), saved = false)), outcome)
        assertEquals(StoreOperation.Save, flags.storeError.value?.operation)
        assertEquals("v1", flags.pending.value?.version, "an unsaved payload doesn't become the pending one")
        assertTrue(store.stored!!.body.contains("\"v1\""))

        flags.refresh()
        assertEquals<List<String?>>(listOf("\"v1\"", "\"v1\""), script.sent, "the next fetch still sends the last saved ETag, so it can't be a 304 for v2")
    }

    @Test
    fun aCancelledRefreshStillAppliesTheKillSwitchAndFinishesTheSave() = runTest {
        val store = FlakyStore(stored(body("v1", on("chat"))))
        store.gate = CompletableDeferred()
        val flags = StandardFlagsClient(Script(fresh(body("v2", off("chat", "KILL_SWITCH")))), definitions, store, contextKey = "acct")
        flags.load()

        val job = launch { flags.refresh() }
        store.saving.await()
        assertEquals(FlagState.Off, flags.state(chat), "applied before the save started")
        job.cancel()
        store.gate!!.complete(Unit)
        job.join()
        runCurrent()
        assertEquals("v2", flags.pending.value?.version, "the save ran to the end despite the cancellation")
        assertTrue(store.stored!!.body.contains("\"v2\""))
    }

    @Test
    fun aStoreThatFailsToLoadIsRetriedAndNeverThrows() = runTest {
        val store = FlakyStore(stored(body("v1", on("chat"))))
        store.failLoad = true
        val flags = StandardFlagsClient(Script(), definitions, store, contextKey = "acct")
        flags.load()
        assertEquals(FlagState.NotLoaded, flags.state(chat))
        assertEquals(StoreOperation.Load, flags.storeError.value?.operation)
        store.failLoad = false
        flags.load()
        assertEquals(FlagState.On, flags.state(chat), "a failed load runs again")
    }

    @Test
    fun aPayloadSavedByAnotherBuildIsNotApplied() = runTest {
        val older = InMemoryLastGoodStore(stored(body("v1", on("chat")), client = "android/0.4.0/300"))
        val script = Script(fresh(body("v2", off("chat"))))
        val flags = StandardFlagsClient(script, definitions, older, contextKey = "acct")
        flags.load()
        assertEquals(FlagState.NotLoaded, flags.state(chat), "the old build's answer could withhold or serve the wrong wires")
        flags.refresh()
        assertEquals(FlagState.Off, flags.state(chat), "the new build's first payload applies live")
        assertEquals(build.fingerprint, older.load()?.client)

        val unknownFormat = InMemoryLastGoodStore(StoredPayload(body("v1", on("chat")), "acct", build.fingerprint, format = 99))
        val other = StandardFlagsClient(Script(), definitions, unknownFormat, contextKey = "acct")
        other.load()
        assertNull(other.payload.value)
    }

    @Test
    fun ceilingsOnlyLower() = runTest {
        val flags = StandardFlagsClient(Script(fresh(body("v1", on("purchases")))), definitions, contextKey = "acct")
        flags.refresh()
        assertEquals(FlagState.Off, flags.state(purchases))
        assertNull(flags.read(purchases).reason)
    }

    @Test
    fun withoutFirstPayloadLiveOnlyKillSwitchesApplyBeforeTheNextLaunch() = runTest {
        val flags = StandardFlagsClient(
            Script(fresh(body("v1", on("chat"), off("insights", "KILL_SWITCH")))),
            definitions,
            policy = ApplyPolicy(firstPayloadAppliesLive = false),
        )
        flags.refresh()
        assertEquals(FlagState.NotLoaded, flags.state(chat))
        assertEquals(FlagState.Off, flags.state(BooleanFlag("insights", default = true)))
        assertEquals("v1", flags.pending.value?.version)
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
        assertIs<RefreshOutcome.Completed>(flags.refresh())
        assertEquals(FlagState.Off, flags.state(chat), "freshValuesAtNextLaunch = false applies at once")
    }

    @Test
    fun oneLimitAppliesToFetchedSeededAndStoredPayloads() = runTest {
        val big = body("v1", on("chat"), """{"key":"pad","value":"${"x".repeat(200)}"}""")
        val script = Script(fresh(big), FetchResult.TooLarge)
        val limits = PayloadLimits(maxBytes = 128)
        val flags = StandardFlagsClient(script, definitions, limits = limits, timeSource = TestTimeSource())
        flags.refresh()
        assertEquals(listOf(128), script.maxBytes, "the fetcher reads at most the client's limit")
        assertEquals(FetchStatus.Rejected(RejectReason.TooLarge), flags.status.value)
        assertIs<PayloadResult.Rejected>(flags.seed(big))

        val store = InMemoryLastGoodStore(stored(big))
        val launch = StandardFlagsClient(Script(), definitions, store, limits = limits, contextKey = "acct")
        launch.load()
        assertNull(launch.payload.value)
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
    fun aMalformedVersionSendsNoEtagAndRepeatedClientFailuresDropIt() = runTest {
        val time = TestTimeSource()
        val script = Script(
            fresh("""{"flags":[],"metadata":{"version":"bad\r\nversion"}}"""),
            FetchResult.NotModified,
            fresh(body("good", on("chat"))),
            FetchResult.Failed(null, null, IllegalStateException("header refused")),
            FetchResult.Failed(null, null, IllegalStateException("header refused")),
            FetchResult.Failed(null, null, IllegalStateException("header refused")),
            FetchResult.NotModified,
        )
        val flags = StandardFlagsClient(script, definitions, backoff = Backoff(initial = 1.seconds, max = 1.seconds), timeSource = time)
        flags.refresh()
        flags.refresh()
        assertEquals(null, script.sent[1], "a version that isn't a valid entity tag is never sent")
        flags.refresh()
        repeat(4) {
            time += 1.hours
            flags.refresh()
        }
        assertEquals(listOf<String?>(null, null, null, "\"good\"", "\"good\"", "\"good\"", null), script.sent)
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
    fun aContextChangeTakesEffectWhileAFetchStallsAndItsAnswerIsDiscarded() = runTest {
        val store = InMemoryLastGoodStore(stored(body("a0", on("chat")), key = "user-a"))
        val stalled = CompletableDeferred<FetchResult>()
        val script = Script(stalled, fresh(body("b1", off("chat"))))
        val flags = StandardFlagsClient(script, definitions, store, contextKey = "user-a")
        flags.load()

        val first = async { flags.refresh() }
        runCurrent()
        flags.setContext(null)
        assertEquals(FlagState.NotLoaded, flags.state(chat), "sign-out didn't wait for the stalled fetch")
        assertNull(store.load())

        stalled.complete(fresh(body("a1", on("chat"))))
        assertEquals(RefreshOutcome.Discarded, first.await())
        assertEquals(FlagState.NotLoaded, flags.state(chat), "user A's late answer is thrown away")
        assertNull(store.load(), "and never saved")

        flags.refresh()
        assertEquals(FlagState.Off, flags.state(chat))
    }

    @Test
    fun concurrentRefreshesShareOneFetch() = runTest {
        val gate = CompletableDeferred<FetchResult>()
        val script = Script(gate)
        val flags = StandardFlagsClient(script, definitions)
        val a = async { flags.refresh() }
        val b = async { flags.refresh() }
        runCurrent()
        gate.complete(fresh(body("v1", on("chat"))))
        assertEquals(a.await(), b.await())
        assertEquals(1, script.sent.size)
    }

    @Test
    fun setContextBeforeLoadAdoptsTheKeyWithoutClearingItsPayload() = runTest {
        val store = InMemoryLastGoodStore(stored(body("v1", on("chat"))))
        val flags = StandardFlagsClient(Script(), definitions, store)
        flags.setContext("  acct ")
        flags.load()
        assertEquals(FlagState.On, flags.state(chat))
        assertEquals("acct", store.load()?.contextKey)
        assertEquals("acct", StandardFlagsClient.normalizeContextKey(" acct "))
        assertNull(StandardFlagsClient.normalizeContextKey("  "))
    }

    @Test
    fun aStoredPayloadForAnotherContextIsIgnored() = runTest {
        val store = InMemoryLastGoodStore(stored(body("v1", on("chat")), key = "someone-else"))
        val flags = StandardFlagsClient(Script(), definitions, store, contextKey = "me")
        flags.load()
        assertEquals(FlagState.NotLoaded, flags.state(chat))
    }

    @Test
    fun aCorruptStoredPayloadIsIgnored() = runTest {
        val store = InMemoryLastGoodStore(stored("{\"flags\": tru", key = "me"))
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
