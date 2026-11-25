package one.rarebit.flags.openfeature

import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlinx.coroutines.test.runTest
import one.rarebit.flags.core.BooleanFlag
import one.rarebit.flags.core.FlagPayload
import one.rarebit.flags.core.StringFlag
import one.rarebit.flags.ofrep.FetchResult
import one.rarebit.flags.ofrep.FlagsFetcher
import one.rarebit.flags.ofrep.StandardFlagsClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StandardFlagsProviderTest {
    private val body = """{"flags":[
        {"key":"chat","value":false,"reason":"KILL_SWITCH","variant":"off"},
        {"key":"purchases","value":true,"reason":"STATIC","variant":"on"},
        {"key":"hero","value":"savings","reason":"SPLIT","variant":"savings"},
        {"key":"broken","errorCode":"PARSE_ERROR","errorDetails":"the flag could not be evaluated; use the code default"},
        {"key":"codeDefault","reason":"DEFAULT"}
    ],"metadata":{"version":"v1"}}"""

    private fun provider(): Pair<StandardFlagsProvider, StandardFlagsClient> {
        val fetcher = FlagsFetcher { FetchResult.Fresh(FlagPayload.parse(body), null) }
        val flags = StandardFlagsClient(
            fetcher,
            listOf(BooleanFlag("purchases", default = false, ceiling = true), StringFlag("hero", "control", setOf("control", "savings"))),
        )
        return StandardFlagsProvider(flags) to flags
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

        val purchases = provider.getBooleanEvaluation("purchases", false, null)
        assertEquals(false, purchases.value, "a declared ceiling holds through OpenFeature")

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
    }

    @Test
    fun aNewTargetingKeyDropsTheOldValuesAndRefetches() = runTest {
        val (provider, flags) = provider()
        provider.initialize(ImmutableContext(targetingKey = "a"))
        assertEquals("v1", flags.payload.value?.version)
        provider.onContextSet(ImmutableContext(targetingKey = "a"), ImmutableContext(targetingKey = "b"))
        assertEquals("v1", flags.payload.value?.version, "refetched for the new context")
    }
}
