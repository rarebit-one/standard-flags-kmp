package one.rarebit.flags.core.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.rarebit.flags.core.BooleanFlag
import one.rarebit.flags.core.BuildRange
import one.rarebit.flags.core.Etag
import one.rarebit.flags.core.FlagCache
import one.rarebit.flags.core.FlagDefinition
import one.rarebit.flags.core.FlagPayload
import one.rarebit.flags.core.FlagRead
import one.rarebit.flags.core.FlagState
import one.rarebit.flags.core.PayloadLimits
import one.rarebit.flags.core.PayloadResult
import one.rarebit.flags.core.StringFlag
import one.rarebit.flags.core.VersionRange
import one.rarebit.flags.core.state
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the vendored wire-scope fixtures (conformance/, pinned in PIN.json).
 * Server-scope fixtures aren't vendored: clients never evaluate rules.
 */
class WireConformanceTest {
    private fun fixture(path: String): JsonObject {
        val text = ConformanceFixtures.files[path] ?: fail("conformance/$path is not vendored")
        val document = Json.parseToJsonElement(text).jsonObject
        assertEquals("wire", document.getValue("scope").jsonPrimitive.content, "$path must be wire scope")
        return document
    }

    @Test
    fun versionRanges() {
        val document = fixture("versions/version_ranges.json")
        assertEquals("version_ranges", document.str("suite"))
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.size > 50)
        val failures = mutableListOf<String>()
        for (case in cases.map { it.jsonObject }) {
            // server_matched records where only the Ruby server differs; clients use matched.
            val range = VersionRange.parse(case.str("range"))
            if (range == null) {
                failures += "range ${case["range"]} didn't parse"
                continue
            }
            val version = case.getValue("version").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val expected = case.getValue("matched").jsonPrimitive.boolean
            if (range.matches(version) != expected) failures += "$range vs ${case["version"]}: expected $expected"
        }
        for (range in document.getValue("invalid_ranges").jsonArray) {
            val parsed = VersionRange.parse(range.jsonPrimitive.content)
            if (parsed != null) failures += "invalid range $range parsed as $parsed"
        }
        for (case in document.getValue("build_cases").jsonArray.map { it.jsonObject }) {
            val range = BuildRange.parse(case.str("range"))
            if (range == null) {
                failures += "build range ${case["range"]} didn't parse"
                continue
            }
            val build = case.getValue("build").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val expected = case.getValue("matched").jsonPrimitive.boolean
            if (range.matches(build) != expected) failures += "$range vs build ${case["build"]}: expected $expected"
        }
        for (range in document.getValue("invalid_build_ranges").jsonArray) {
            val parsed = BuildRange.parse(range.jsonPrimitive.content)
            if (parsed != null) failures += "invalid build range $range parsed as $parsed"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun clientState() {
        val document = fixture("wire/client_state.json")
        assertEquals("client_state", document.str("suite"))
        val limits = document.getValue("limits").jsonObject.let {
            PayloadLimits(maxBytes = it.getValue("max_bytes").jsonPrimitive.int, maxFlags = it.getValue("max_flags").jsonPrimitive.int)
        }
        val failures = mutableListOf<String>()
        val cases = document.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val name = case.str("name")
            val declared = declarations((case["declared"] ?: document.getValue("declared")).jsonObject)
            val cache = FlagCache(limits)
            case.getValue("steps").jsonArray.forEachIndexed { index, stepElement ->
                val step = stepElement.jsonObject
                val label = "$name, step ${index + 1}"
                val accepted = when {
                    step["not_modified"] != null -> {
                        cache.notModified()
                        true
                    }
                    step["raw"] != null -> cache.receive(step.str("raw").encodeToByteArray()) is PayloadResult.Accepted
                    else -> cache.receive(Json.encodeToString(JsonElement.serializer(), step.getValue("payload")).encodeToByteArray()) is PayloadResult.Accepted
                }
                val expected = step.getValue("expected").jsonObject
                if (accepted != expected.getValue("accepted").jsonPrimitive.boolean) failures += "$label: accepted $accepted"
                val version = expected.getValue("version").let { if (it is JsonNull) null else it.jsonPrimitive.content }
                if (cache.version != version) failures += "$label: version ${cache.version}, expected $version"
                for ((wire, read) in expected.getValue("reads").jsonObject) {
                    val definition = declared.getValue(wire)
                    val actual = cache.read(definition)
                    val problem = compareRead(definition, actual, read.jsonObject)
                    if (problem != null) failures += "$label: $wire $problem"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun deliveryEtagsAndBodies() {
        val document = fixture("wire/delivery.json")
        assertEquals("delivery", document.str("suite"))
        val failures = mutableListOf<String>()
        val cases = document.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 20)
        val etags = mutableMapOf<String, String>()
        for (case in cases) {
            val name = case.str("name")
            val expected = case.getValue("expected").jsonObject
            val flags = expected.getValue("flags").jsonArray
            val etag = expected.str("etag")
            val computed = Etag.of(case.str("audience"), flags)
            if (computed != etag) failures += "$name: ETag $computed, expected $etag"
            etags[name] = etag
            for (other in names(case["etag_same_as"])) {
                if (etags[other] != etag) failures += "$name: ETag should equal $other's"
            }
            for (other in names(case["etag_differs_from"])) {
                if (etags[other] == null || etags[other] == etag) failures += "$name: ETag should differ from $other's"
            }

            // Every golden body decodes, keeps its version, and reads back each entry.
            val body = JsonObject(mapOf("flags" to flags, "metadata" to JsonObject(mapOf("version" to JsonPrimitive(etag)))))
            when (val result = FlagPayload.parse(Json.encodeToString(JsonElement.serializer(), body))) {
                is PayloadResult.Rejected -> failures += "$name: golden body rejected (${result.reason})"
                is PayloadResult.Accepted -> {
                    val payload = result.payload
                    if (payload.version != etag) failures += "$name: version ${payload.version}"
                    if (payload.etag != "\"$etag\"") failures += "$name: If-None-Match ${payload.etag}"
                    for (entry in flags.map { it.jsonObject }) {
                        val key = entry.str("key")
                        val value = entry["value"] as? JsonPrimitive
                        val definition: FlagDefinition<*> = if (value != null && value.isString) {
                            StringFlag(key, default = "")
                        } else {
                            BooleanFlag(key, default = false)
                        }
                        val read = readOf(payload, definition)
                        if (entry["errorCode"] != null) {
                            if (read.loaded) failures += "$name: errored $key read as loaded"
                        } else {
                            if (!read.loaded || read.value.toString() != value?.content) failures += "$name: $key read $read"
                            if (read.reason != entry.str("reason")) failures += "$name: $key reason ${read.reason}"
                        }
                    }
                    for (secret in document.getValue("never_on_wire").jsonArray) {
                        if (payload.body.contains(secret.jsonPrimitive.content)) failures += "$name: payload carries $secret"
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun laterWireFixturesNeedARunner() {
        // Gem phase 3d adds wire/manifest_check.json (the Gradle contract check).
        // It isn't at the pinned commit; when a pin bump vendors it, this fails
        // until ManifestContract runs it, so a new fixture can't sit unrun.
        // (wire/client_status.json from 3c already has its runner in the status module.)
        assertTrue("wire/manifest_check.json" !in ConformanceFixtures.files, "wire/manifest_check.json is vendored but not yet run (see ManifestContract)")
        assertNull(ConformanceFixtures.files["schema/fixture.schema.json"], "the schema isn't a fixture")
    }

    private fun declarations(json: JsonObject): Map<String, FlagDefinition<*>> = json.mapValues { (wire, element) ->
        val declaration = element.jsonObject
        when (declaration.str("type")) {
            "boolean" -> BooleanFlag(
                wire,
                default = declaration.getValue("default").jsonPrimitive.boolean,
                ceiling = declaration["ceiling"]?.jsonPrimitive?.boolean ?: false,
            )
            "string" -> StringFlag(
                wire,
                default = declaration.str("default"),
                values = declaration["values"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
            )
            else -> fail("unknown type for $wire")
        }
    }

    private fun readOf(payload: FlagPayload, definition: FlagDefinition<*>): FlagRead<Any> {
        val cache = FlagCache()
        cache.accept(payload)
        return cache.read(definition)
    }

    private fun compareRead(definition: FlagDefinition<*>, actual: FlagRead<Any>, expected: JsonObject): String? {
        val state = expected.str("state")
        val actualState = when (definition) {
            is BooleanFlag -> when (@Suppress("UNCHECKED_CAST") (actual as FlagRead<Boolean>).state) {
                FlagState.On -> "on"
                FlagState.Off -> "off"
                FlagState.NotLoaded -> "not_loaded"
            }
            is StringFlag -> if (actual.loaded) "loaded" else "not_loaded"
        }
        if (actualState != state) return "state $actualState, expected $state"
        val value = expected.getValue("value").jsonPrimitive
        val expectedValue: Any = if (value.isString) value.content else value.boolean
        if (actual.value != expectedValue) return "value ${actual.value}, expected $expectedValue"
        val reason = expected.getValue("reason").let { if (it is JsonNull) null else it.jsonPrimitive.content }
        if (actual.reason != reason) return "reason ${actual.reason}, expected $reason"
        return null
    }

    // A relation names one earlier case or a list of them.
    private fun names(element: JsonElement?): List<String> = when (element) {
        null -> emptyList()
        is JsonArray -> element.map { it.jsonPrimitive.content }
        else -> listOf(element.jsonPrimitive.content)
    }

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content
}
