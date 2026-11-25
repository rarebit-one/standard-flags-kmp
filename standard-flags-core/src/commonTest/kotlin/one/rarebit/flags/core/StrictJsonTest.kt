package one.rarebit.flags.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "Not JSON" rejects the payload and keeps the last good one, as the shared
 * client_state rule says. These are the bodies kotlinx's tree reader alone
 * would have accepted, run the way client_state.json runs `raw` steps.
 */
class StrictJsonTest {
    private val good = """{"flags":[{"key":"chat","value":true,"reason":"STATIC","variant":"on"}],"metadata":{"version":"v1"}}"""

    private fun cacheWithGood(): FlagCache = FlagCache(PayloadLimits(maxBytes = 1 shl 20)).also {
        assertIs<PayloadResult.Accepted>(it.receive(good.encodeToByteArray()))
    }

    @Test
    fun rejectsWhatIsNotJsonAndKeepsTheLastGoodPayload() {
        val notJson = listOf(
            """{"flags":[{"key":"chat","value":tru}]}""",
            """{"flags":[{"key":"chat","value":True}]}""",
            """{"flags":[{"key":"chat","value":NaN}]}""",
            """{"flags":[{"key":"chat","value":Infinity}]}""",
            """{"flags":[{"key":"chat","value":'on'}]}""",
            """{'flags':[]}""",
            """{flags:[]}""",
            """{"flags":[{"key":"chat","value":01}]}""",
            """{"flags":[{"key":"chat","value":1.}]}""",
            """{"flags":[{"key":"chat","value":-}]}""",
            """{"flags":[],"metadata":{"version":abc}}""",
            """{"flags":[],}""",
            """{"flags":[1,]}""",
            """{"flags":[]} {}""",
            """{"flags":[{"key":"chat","value":"a${'\u0001'}b"}]}""",
            """{"flags":[{"key":"chat","value":"\x41"}]}""",
            """{"flags":[{"key":"chat","value":"\u00G1"}]}""",
            """{"flags":[]""",
            "",
            "  ",
            "{\"flags\":[" + "[".repeat(100) + "]".repeat(100) + "]}",
        )
        for (body in notJson) {
            val cache = cacheWithGood()
            assertEquals(PayloadResult.Rejected(RejectReason.NotJson), cache.receive(body.encodeToByteArray()), body.take(60))
            assertEquals("v1", cache.version, "kept the last good version after $body")
            assertEquals(FlagState.On, cache.read(BooleanFlag("chat", false)).state)
        }
    }

    @Test
    fun acceptsEveryJsonGrammarForm() {
        val valid = listOf(
            """ {"flags":[], "metadata":{"version":"v2","n":[-0.5e-3,1E+2,0,123,true,false,null,{},[]]}} """,
            "{\"flags\":[{\"key\":\"chat\",\"value\":\"esc \\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u00e9\"}]}",
            "\t\r\n{\"flags\":[]}\n",
            // 99 levels inside the top-level object and the flags array: 101 containers would be refused.
            "{\"flags\":[" + "[".repeat(98) + "]".repeat(98) + "]}",
        )
        for (body in valid) assertTrue(StrictJson.isValid(body), body.take(60))
        assertTrue(!StrictJson.isValid("{\"flags\":[" + "[".repeat(99) + "]".repeat(99) + "]}"), "101 nested containers")
    }

    @Test
    fun surrogateEscapesMustPairHighThenLow() {
        assertTrue(StrictJson.isValid("[\"\\ud83d\\ude00\"]"), "a high surrogate followed by a low one")
        for (bad in listOf("[\"\\ud83d\"]", "[\"\\ude00\"]", "[\"\\ude00\\ud83d\"]", "[\"\\ud83dx\"]", "[\"\\ud83d\\u0041\"]")) {
            assertTrue(!StrictJson.isValid(bad), bad)
        }
    }

    @Test
    fun hexEscapesTakeOnlyAsciiDigits() {
        assertTrue(StrictJson.isValid("[\"\\u00e9\\u00C9\"]"))
        // A fullwidth 8 (U+FF18), an Arabic-Indic 4 (U+0664) and a fullwidth A (U+FF21) are digits to digitToIntOrNull.
        for (bad in listOf("[\"\\u00\uFF181\"]", "[\"\\u00\u06641\"]", "[\"\\u00\uFF21A\"]", "[\"\\uD\uFF183D\\uDE00\"]")) {
            assertTrue(!StrictJson.isValid(bad), bad)
        }
    }

    @Test
    fun theNestingLimitIsConfigurable() {
        val three = "{\"flags\":[[]]}"
        assertTrue(StrictJson.isValid(three, maxNesting = 3))
        assertTrue(!StrictJson.isValid(three, maxNesting = 2))
        assertEquals(PayloadResult.Rejected(RejectReason.NotJson), FlagPayload.parse(three, PayloadLimits(maxNesting = 2)))
        assertEquals(PayloadLimits.MAX_NESTING_CAP, PayloadLimits(maxNesting = PayloadLimits.MAX_NESTING_CAP).maxNesting)
        for (absurd in listOf(0, PayloadLimits.MAX_NESTING_CAP + 1, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("maxNesting $absurd") { PayloadLimits(maxNesting = absurd) }
        }
    }

    @Test
    fun aVersionThatCantBeAnEntityTagIsIgnored() {
        for (version in listOf("bad\r\nversion", "has \"quote\"", "spaced out", "é", "tab\tbed", "", "x".repeat(257))) {
            val body = """{"flags":[],"metadata":{"version":${kotlinx.serialization.json.JsonPrimitive(version)}}}"""
            val payload = assertIs<PayloadResult.Accepted>(FlagPayload.parse(body)).payload
            assertEquals(null, payload.version, version)
            assertEquals(null, payload.etag)
        }
        assertTrue(FlagPayload.isUsableVersion("0123abcdef".repeat(6) + "0123"))
        assertTrue(FlagPayload.isUsableVersion("v1"))
        assertTrue(FlagPayload.isUsableVersion("x".repeat(256)), "at most 256 characters")
        assertTrue(!FlagPayload.isUsableVersion("x".repeat(257)))
    }
}
