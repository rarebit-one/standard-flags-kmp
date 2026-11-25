package one.rarebit.flags.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether this build may keep running, from the server's client registry.
 *
 * The four states follow the server's `/client/status` answer. A failed
 * check never produces [UpgradeRequired]: only the server can say that.
 */
public sealed interface ClientStatus {
    /** The fields every answer may carry. */
    public val details: StatusDetails?

    /** Nothing known yet, or the server doesn't know this build (no platform or version). Never block. */
    public data class Unknown(override val details: StatusDetails? = null) : ClientStatus

    /** This build is current enough. */
    public data class Ok(override val details: StatusDetails) : ClientStatus

    /**
     * A newer build is available. [enforceAt] is when this build will be
     * required to upgrade (an ISO 8601 instant), null when no enforcement is
     * scheduled. Nudge, don't block.
     */
    public data class UpgradeRecommended(override val details: StatusDetails, val enforceAt: String?) : ClientStatus

    /** This build is below the enforced minimum. Show a blocking upgrade screen. */
    public data class UpgradeRequired(override val details: StatusDetails) : ClientStatus

    public companion object {
        /** Unknown, with no details. */
        public val Initial: ClientStatus = Unknown()
    }
}

/**
 * The fields of a `/client/status` answer, each null when absent or of the
 * wrong type.
 *
 * @property storeUrl where to send the user to upgrade.
 */
public data class StatusDetails(
    val platform: String? = null,
    val version: String? = null,
    val minVersion: String? = null,
    val recommended: String? = null,
    val enforceAt: String? = null,
    val storeUrl: String? = null,
)

/**
 * Parses status answers. Strict about the `status` value (anything it doesn't
 * know reads as [ClientStatus.Unknown]) and tolerant about everything else.
 */
public object ClientStatusParser {
    private val json = Json

    /**
     * The status in a `/client/status` body:
     * `{"status": "ok" | "upgrade_recommended" | "upgrade_required" | "unknown", "platform", "version",
     * "min_version", "recommended", "enforce_at", "store_url"}`. Null when the body isn't a JSON object.
     */
    // TODO(standard_flags 3c): run wire/client_status.json once the gem ships it
    // (scripts/sync-conformance vendors it when the pinned commit has it).
    public fun parse(body: String): ClientStatus? {
        val document = parseObject(body) ?: return null
        val details = StatusDetails(
            platform = document.text("platform"),
            version = document.text("version"),
            minVersion = document.text("min_version"),
            recommended = document.text("recommended"),
            enforceAt = document.text("enforce_at"),
            storeUrl = document.text("store_url"),
        )
        return when (document.text("status")) {
            "ok" -> ClientStatus.Ok(details)
            "upgrade_recommended" -> ClientStatus.UpgradeRecommended(details, details.enforceAt)
            "upgrade_required" -> ClientStatus.UpgradeRequired(details)
            else -> ClientStatus.Unknown(details)
        }
    }

    /**
     * The status in a 426 answer from any API call: a JSON:API error document
     * whose error has code `client_upgrade_required`, with `meta.min_version`
     * and `meta.store_url`. Null when the body isn't one.
     */
    public fun parseUpgradeRequired(body: String): ClientStatus.UpgradeRequired? {
        val document = parseObject(body) ?: return null
        val errors = document["errors"] as? JsonArray ?: return null
        val error = errors.filterIsInstance<JsonObject>().firstOrNull { it.text("code") == UPGRADE_REQUIRED_CODE }
            ?: return null
        val meta = error["meta"] as? JsonObject
        return ClientStatus.UpgradeRequired(
            StatusDetails(minVersion = meta?.text("min_version"), storeUrl = meta?.text("store_url")),
        )
    }

    public const val UPGRADE_REQUIRED_CODE: String = "client_upgrade_required"

    private fun parseObject(body: String): JsonObject? = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
