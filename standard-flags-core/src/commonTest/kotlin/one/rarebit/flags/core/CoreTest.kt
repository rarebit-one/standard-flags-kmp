package one.rarebit.flags.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun versionsOrderBySemverPrecedence() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.4", "2")
            .map { Version.parse(it)!! }
        assertEquals(ordered, ordered.shuffled().sorted())
        assertEquals(Version.parse("1.4.0+381"), Version.parse("1.4"))
        assertEquals("1.4.0-beta.2", Version.parse(" 1.4.0-beta.2+7\t").toString())
        assertNull(Version.parse("v1.4"))
        assertNull(Version.parse("1.4-beta"))
        assertTrue(Version.parse("99999999999999999999.0.0")!! > Version.parse("9999999999999999999.9.9")!!)
    }

    @Test
    fun rangesKeepTheirCanonicalForm() {
        assertEquals(">=1.4.0 <2.0.0 || =0.9.5-beta.1", VersionRange.parse(" >= 1.4 <2||=0.9.5-beta.1 ").toString())
        assertTrue(BuildRange.parse("=7 || >=12")!!.matches(12L))
        assertFalse(BuildRange.parse(">=1")!!.matches(-1L))
        assertNull(VersionRange.parse("~> 1.4"))
    }

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun jcsWritesOnlyTheRequiredEscapes() {
        val document = JsonObject(
            mapOf(
                "b" to JsonPrimitive("Grüße   \u0001 \" / \\ \u007f\n"),
                "a" to JsonArray(listOf(JsonPrimitive(true), JsonPrimitive(12))),
            ),
        )
        assertEquals("{\"a\":[true,12],\"b\":\"Grüße   \\u0001 \\\" / \\\\ \u007f\\n\"}", Jcs.canonicalize(document))
        assertFailsWith<IllegalArgumentException> { Jcs.canonicalize(JsonPrimitive(1.5)) }
        assertEquals("[0,0]", Jcs.canonicalize(JsonArray(listOf(JsonPrimitive(0), kotlinx.serialization.json.Json.parseToJsonElement("-0")))), "RFC 8785 writes -0 as 0")
        // The example in the server's docs.
        val entries = JsonArray(listOf(JsonObject(mapOf("key" to JsonPrimitive("a"), "reason" to JsonPrimitive("DEFAULT"), "value" to JsonPrimitive(true), "variant" to JsonPrimitive("on")))))
        assertEquals(Sha256.hex("{\"audience\":\"mobile\",\"flags\":[{\"key\":\"a\",\"reason\":\"DEFAULT\",\"value\":true,\"variant\":\"on\"}]}".encodeToByteArray()), Etag.of("mobile", entries))
    }

    @Test
    fun parsingIgnoresUnknownFieldsAndKeepsOddValues() {
        val result = FlagPayload.parse("""{"flags":[{"key":"a","value":3,"reason":"STATIC","extra":{}},{"key":"b","value":null}],"metadata":{"version":"v9","other":1},"more":true}""")
        val payload = assertIs<PayloadResult.Accepted>(result).payload
        assertIs<FlagValue.Other>(payload.entries.getValue("a").value)
        assertIs<FlagValue.Other>(payload.entries.getValue("b").value)
        assertEquals("\"v9\"", payload.etag)
        assertEquals(FallbackCause.WrongType, readFlag(payload, BooleanFlag("a", false)).cause)
    }

    @Test
    fun parsingRefusesInvalidUtf8AndBom() {
        val invalid = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte())
        assertEquals(PayloadResult.Rejected(RejectReason.NotJson), FlagPayload.parse(invalid))
        assertEquals(PayloadResult.Rejected(RejectReason.NotJson), FlagPayload.parse("﻿{\"flags\":[]}"))
        assertEquals(PayloadResult.Rejected(RejectReason.NotJson), FlagPayload.parse("{\"flags\":[]} x"))
    }

    @Test
    fun overlayAppliesOnlyTheListedWires() {
        val base = (FlagPayload.parse("""{"flags":[{"key":"a","value":true},{"key":"b","value":true}],"metadata":{"version":"old"}}""") as PayloadResult.Accepted).payload
        val fresh = (FlagPayload.parse("""{"flags":[{"key":"a","value":false,"reason":"KILL_SWITCH"},{"key":"b","value":false}],"metadata":{"version":"new"}}""") as PayloadResult.Accepted).payload
        val merged = FlagPayload.overlay(base, fresh, setOf("a", "c"))
        assertEquals(false, readFlag(merged, BooleanFlag("a", true)).value)
        assertEquals(true, readFlag(merged, BooleanFlag("b", false)).value)
        assertEquals("old", merged.version)
        assertTrue(fresh.entries.getValue("a").isKillSwitch)
        val fromNothing = FlagPayload.overlay(null, fresh, setOf("a"))
        assertEquals(FlagState.NotLoaded, readFlag(fromNothing, BooleanFlag("b", false)).state)
        assertEquals(FlagState.Off, readFlag(fromNothing, BooleanFlag("a", true)).state)
    }

    @Test
    fun stringStoreRoundTripsAndToleratesGarbage() = runTest {
        var value: String? = null
        val store = StringLastGoodStore(read = { value }, write = { value = it })
        assertNull(store.load())
        store.save(StoredPayload("""{"flags":[]}""", "acct", "ios/1.4.0/212"))
        assertEquals(StoredPayload("""{"flags":[]}""", "acct", "ios/1.4.0/212"), store.load())
        assertTrue(value!!.contains("\"format\":1"), "the record carries its format: $value")
        value = "not json"
        assertNull(store.load())
        store.clear()
        assertNull(value)
    }

    @Test
    fun cacheKeepsTheLastGoodPayload() {
        val cache = FlagCache(PayloadLimits(maxBytes = 64))
        assertIs<PayloadResult.Accepted>(cache.receive("""{"flags":[{"key":"a","value":true}]}""".encodeToByteArray()))
        assertEquals(PayloadResult.Rejected(RejectReason.TooLarge), cache.receive(ByteArray(65) { ' '.code.toByte() }))
        assertEquals(FlagState.On, cache.read(BooleanFlag("a", false)).state)
    }

    @Test
    fun payloadLimitsCompareByValue() {
        val limits = PayloadLimits(maxBytes = 64, maxFlags = 8, maxNesting = 10)
        assertEquals(PayloadLimits(maxBytes = 64, maxFlags = 8, maxNesting = 10), limits)
        assertEquals(PayloadLimits(maxBytes = 64, maxFlags = 8, maxNesting = 10).hashCode(), limits.hashCode())
        assertFalse(limits == PayloadLimits(maxBytes = 64, maxFlags = 8, maxNesting = 11))
        assertEquals(PayloadLimits(), PayloadLimits(256 * 1024, 2048, PayloadLimits.DEFAULT_MAX_NESTING))
        assertEquals("PayloadLimits(maxBytes=64, maxFlags=8, maxNesting=10)", limits.toString())
        assertFailsWith<IllegalArgumentException> { PayloadLimits(maxNesting = PayloadLimits.MAX_NESTING_CAP + 1) }
    }
}
