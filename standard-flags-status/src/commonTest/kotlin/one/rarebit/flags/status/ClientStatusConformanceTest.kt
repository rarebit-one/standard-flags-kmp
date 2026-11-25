package one.rarebit.flags.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.rarebit.flags.core.conformance.ConformanceFixtures
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `wire/client_status.json` (standard_flags 3c): every case's expected payload
 * is a golden `/client/status` body, and the parser must read each field
 * exactly. Server-scope cases still have valid bodies, so they run too.
 */
class ClientStatusConformanceTest {
    @Test
    fun parsesEveryGoldenStatusBody() {
        val text = assertNotNull(ConformanceFixtures.files["wire/client_status.json"], "wire/client_status.json isn't vendored")
        val document = Json.parseToJsonElement(text).jsonObject
        val failures = mutableListOf<String>()
        val cases = document.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject
            val status = ClientStatusParser.parse(Json.encodeToString(JsonElement.serializer(), expected))
            if (status == null) {
                failures += "$name: didn't parse"
                continue
            }
            val kind = when (status) {
                is ClientStatus.Ok -> "ok"
                is ClientStatus.UpgradeRecommended -> "upgrade_recommended"
                is ClientStatus.UpgradeRequired -> "upgrade_required"
                is ClientStatus.Unknown -> "unknown"
            }
            if (kind != expected.text("status")) failures += "$name: status $kind"
            val details = status.details ?: StatusDetails()
            val read = mapOf(
                "platform" to details.platform,
                "version" to details.version,
                "min_version" to details.minVersion,
                "recommended" to details.recommended,
                "enforce_at" to details.enforceAt,
                "store_url" to details.storeUrl,
            )
            for ((field, value) in read) if (value != expected.text(field)) failures += "$name: $field $value"
            if (status is ClientStatus.UpgradeRecommended && status.enforceAt != expected.text("enforce_at")) {
                failures += "$name: enforceAt ${status.enforceAt}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun JsonObject.text(name: String): String? = this[name]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
}
