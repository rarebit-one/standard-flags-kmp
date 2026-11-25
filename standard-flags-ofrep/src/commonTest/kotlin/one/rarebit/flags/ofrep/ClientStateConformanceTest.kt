package one.rarebit.flags.ofrep

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.rarebit.flags.core.BooleanFlag
import one.rarebit.flags.core.FlagDefinition
import one.rarebit.flags.core.FlagState
import one.rarebit.flags.core.PayloadLimits
import one.rarebit.flags.core.StringFlag
import one.rarebit.flags.core.conformance.ConformanceFixtures
import one.rarebit.flags.core.state
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours
import kotlin.time.TestTimeSource

/**
 * `wire/client_state.json` through the shipped state machine: each step is
 * an answer from a scripted fetcher to [StandardFlagsClient.refresh], with the
 * fixture's limits as the client's (and so the fetch's) limits. Fresh values
 * apply at once here, since when they apply is app policy the fixture leaves out.
 */
class ClientStateConformanceTest {
    private class Scripted : FlagsFetcher {
        var next: FetchResult = FetchResult.NotModified
        var maxBytes = 0
        val sent = mutableListOf<String?>()

        override suspend fun fetch(ifNoneMatch: String?, maxBytes: Int): FetchResult {
            sent += ifNoneMatch
            this.maxBytes = maxBytes
            return next
        }
    }

    @Test
    fun clientStateRunsThroughStandardFlagsClient() = runTest {
        val document = Json.parseToJsonElement(ConformanceFixtures.files.getValue("wire/client_state.json")).jsonObject
        val limits = document.getValue("limits").jsonObject.let {
            PayloadLimits(it.getValue("max_bytes").jsonPrimitive.int, it.getValue("max_flags").jsonPrimitive.int)
        }
        val failures = mutableListOf<String>()
        for (case in document.getValue("cases").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val declared = declarations((case["declared"] ?: document.getValue("declared")).jsonObject)
            val fetcher = Scripted()
            val time = TestTimeSource()
            val flags = StandardFlagsClient(
                fetcher,
                declared.values,
                policy = ApplyPolicy(freshValuesAtNextLaunch = false),
                limits = limits,
                timeSource = time,
            )
            case.getValue("steps").jsonArray.forEachIndexed { index, element ->
                val step = element.jsonObject
                val label = "$name, step ${index + 1}"
                val bytes = when {
                    step["not_modified"] != null -> null
                    step["raw"] != null -> step.getValue("raw").jsonPrimitive.content.encodeToByteArray()
                    else -> Json.encodeToString(JsonElement.serializer(), step.getValue("payload")).encodeToByteArray()
                }
                // The fetcher is what enforces max_bytes on the wire; the client enforces it on what it is handed.
                fetcher.next = when {
                    bytes == null -> FetchResult.NotModified
                    bytes.size > limits.maxBytes -> FetchResult.TooLarge
                    else -> FetchResult.Fresh(bytes, null)
                }
                time += 2.hours // past any backoff from a rejected step
                val outcome = flags.refresh()
                if (fetcher.maxBytes != limits.maxBytes) failures += "$label: fetch limit ${fetcher.maxBytes}"
                val status = (outcome as? RefreshOutcome.Completed)?.status ?: fail("$label: $outcome")
                val accepted = status is FetchStatus.Updated || status is FetchStatus.NotModified
                val expected = step.getValue("expected").jsonObject
                if (accepted != expected.getValue("accepted").jsonPrimitive.boolean) failures += "$label: accepted $accepted ($status)"
                val version = expected.getValue("version").let { if (it is JsonNull) null else it.jsonPrimitive.content }
                if (flags.pending.value?.version != version) failures += "$label: version ${flags.pending.value?.version}, expected $version"
                for ((wire, read) in expected.getValue("reads").jsonObject) {
                    val problem = compare(declared.getValue(wire), flags, read.jsonObject)
                    if (problem != null) failures += "$label: $wire $problem"
                }
            }
            // The version kept is what the next fetch sends.
            time += 2.hours
            fetcher.next = FetchResult.NotModified
            flags.refresh()
            val kept = flags.pending.value?.version?.let { "\"$it\"" }
            if (fetcher.sent.last() != kept) failures += "$name: sent ${fetcher.sent.last()}, expected $kept"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun declarations(json: JsonObject): Map<String, FlagDefinition<*>> = json.mapValues { (wire, element) ->
        val declaration = element.jsonObject
        when (declaration.getValue("type").jsonPrimitive.content) {
            "boolean" -> BooleanFlag(wire, declaration.getValue("default").jsonPrimitive.boolean, declaration["ceiling"]?.jsonPrimitive?.boolean ?: false)
            "string" -> StringFlag(
                wire,
                declaration.getValue("default").jsonPrimitive.content,
                declaration["values"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
            )
            else -> fail("unknown type for $wire")
        }
    }

    private fun compare(definition: FlagDefinition<*>, flags: StandardFlagsClient, expected: JsonObject): String? {
        val read = flags.read(definition)
        val state = when (definition) {
            is BooleanFlag -> when (flags.read(definition).state) {
                FlagState.On -> "on"
                FlagState.Off -> "off"
                FlagState.NotLoaded -> "not_loaded"
            }
            is StringFlag -> if (read.loaded) "loaded" else "not_loaded"
        }
        if (state != expected.getValue("state").jsonPrimitive.content) return "state $state"
        val value = expected.getValue("value").jsonPrimitive
        val expectedValue: Any = if (value.isString) value.content else value.boolean
        if (read.value != expectedValue) return "value ${read.value}"
        val reason = expected.getValue("reason").let { if (it is JsonNull) null else it.jsonPrimitive.content }
        if (read.reason != reason) return "reason ${read.reason}"
        return null
    }
}
