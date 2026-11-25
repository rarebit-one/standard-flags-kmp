package one.rarebit.flags.openfeature

import kotlinx.coroutines.ExperimentalCoroutinesApi
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.rarebit.flags.core.BooleanFlag
import one.rarebit.flags.core.InMemoryLastGoodStore
import one.rarebit.flags.core.StoredPayload
import one.rarebit.flags.core.StringFlag
import one.rarebit.flags.ofrep.ClientInfo
import one.rarebit.flags.ofrep.FetchResult
import one.rarebit.flags.ofrep.FetchStatus
import one.rarebit.flags.ofrep.FlagsFetcher
import one.rarebit.flags.ofrep.StandardFlagsClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class StandardFlagsProviderTest {
    private val body = """{"flags":[
        {"key":"chat","value":false,"reason":"KILL_SWITCH","variant":"off"},
        {"key":"purchases","value":true,"reason":"STATIC","variant":"on"},
        {"key":"hero","value":"savings","reason":"SPLIT","variant":"savings"},
        {"key":"broken","errorCode":"PARSE_ERROR","errorDetails":"the flag could not be evaluated; use the code default"},
        {"key":"codeDefault","reason":"DEFAULT"}
    ],"metadata":{"version":"v1"}}"""

    private val build = ClientInfo("ios", "1.4.0", 212)

    private class Fetcher(private val build: ClientInfo, private val answer: suspend () -> FetchResult) : FlagsFetcher {
        var calls = 0
        override val client: ClientInfo = build

        override suspend fun fetch(ifNoneMatch: String?, maxBytes: Int): FetchResult {
            calls++
            return answer()
        }
    }

    private val definitions = listOf(BooleanFlag("purchases", default = false, ceiling = true), StringFlag("hero", "control", setOf("control", "savings")))

    // The provider's own scope, on the test scheduler, so its timeouts run in virtual time.
    private fun TestScope.providerScope() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))

    private fun TestScope.provider(store: InMemoryLastGoodStore = InMemoryLastGoodStore()): Pair<StandardFlagsProvider, StandardFlagsClient> {
        val flags = StandardFlagsClient(Fetcher(build) { FetchResult.Fresh(body.encodeToByteArray(), null) }, definitions, store)
        return StandardFlagsProvider(flags, scope = providerScope()) to flags
    }

    @Test
    fun evaluatesThroughTheClient() = runTest {
        val (provider, _) = provider()
        val before = provider.getBooleanEvaluation("chat", true, null)
        assertEquals(ErrorCode.PROVIDER_NOT_READY, before.errorCode)
        assertEquals(true, before.value)

        provider.initialize(ImmutableContext(targetingKey = "acct"))

        val chat = provider.getBooleanEvaluation("chat", true, null)
        assertEquals(false, chat.value)
        assertEquals("KILL_SWITCH", chat.reason)
        assertEquals("off", chat.variant)
        assertNull(chat.errorCode)

        assertEquals("savings", provider.getStringEvaluation("hero", "control", null).value)
        assertEquals(ErrorCode.TYPE_MISMATCH, provider.getBooleanEvaluation("hero", false, null).errorCode)

        val broken = provider.getBooleanEvaluation("broken", true, null)
        assertEquals(true, broken.value)
        assertEquals(ErrorCode.PARSE_ERROR, broken.errorCode)
        assertEquals(Reason.ERROR.name, broken.reason)

        val codeDefault = provider.getStringEvaluation("codeDefault", "x", null)
        assertEquals("x", codeDefault.value)
        assertEquals(Reason.DEFAULT.name, codeDefault.reason)
        assertNull(codeDefault.errorCode)

        assertEquals(ErrorCode.FLAG_NOT_FOUND, provider.getBooleanEvaluation("nope", false, null).errorCode)
        assertEquals(ErrorCode.TYPE_MISMATCH, provider.getIntegerEvaluation("chat", 1, null).errorCode)
        assertEquals(ErrorCode.TYPE_MISMATCH, provider.getObjectEvaluation("chat", Value.Null, null).errorCode)
        assertEquals(StandardFlagsProvider.NAME, provider.metadata.name)
        provider.shutdown()
    }

    @Test
    fun aCallerDefaultOfTrueCannotLiftACeiling() = runTest {
        val (provider, _) = provider()
        provider.initialize(ImmutableContext(targetingKey = "acct"))
        // The server says true; the declared ceiling (default false) holds whatever the caller passes.
        assertEquals(false, provider.getBooleanEvaluation("purchases", true, null).value)
        assertEquals(false, provider.getBooleanEvaluation("purchases", false, null).value)
        provider.shutdown()
    }

    @Test
    fun theStoredPayloadForTheTargetingKeyIsTheLaunchValue() = runTest {
        val saved = body.replace("\"value\":false,\"reason\":\"KILL_SWITCH\",\"variant\":\"off\"", "\"value\":true,\"reason\":\"STATIC\",\"variant\":\"on\"")
            .replace("\"version\":\"v1\"", "\"version\":\"v0\"")
        val store = InMemoryLastGoodStore(StoredPayload(saved, "acct", build.fingerprint))
        val gate = CompletableDeferred<FetchResult>()
        val flags = StandardFlagsClient(Fetcher(build) { gate.await() }, definitions, store)
        val provider = StandardFlagsProvider(flags, initialFetchTimeout = 10.milliseconds, scope = providerScope())

        provider.initialize(ImmutableContext(targetingKey = "acct"))
        assertEquals(true, provider.getBooleanEvaluation("chat", false, null).value, "the saved value loads before the fetch lands")
        assertEquals("acct", store.load()?.contextKey, "the saved payload wasn't wiped")

        // The fetch outlived the timeout and still lands (the kill switch applies live).
        gate.complete(FetchResult.Fresh(body.encodeToByteArray(), null))
        flags.status.first { it is FetchStatus.Updated }
        assertEquals(false, provider.getBooleanEvaluation("chat", true, null).value)
        provider.shutdown()
    }

    @Test
    fun eventsNameOnlyTheWiresThatChanged() = runTest {
        val (provider, flags) = provider()
        val events = mutableListOf<OpenFeatureProviderEvents>()
        val collecting = launch { provider.observe().collect { events += it } }
        runCurrent()
        provider.initialize(ImmutableContext(targetingKey = "a"))
        runCurrent()
        provider.onContextSet(ImmutableContext(targetingKey = "a"), ImmutableContext(targetingKey = "b"))
        runCurrent()
        collecting.cancel()
        val changed = events.map { it.eventDetails?.flagsChanged }
        val all = setOf("broken", "chat", "codeDefault", "hero", "purchases")
        assertEquals(listOf(all, all, all), changed, "first payload, context dropped, new context's payload")
        assertEquals("v1", flags.payload.value?.version)
        provider.shutdown()
    }
}
