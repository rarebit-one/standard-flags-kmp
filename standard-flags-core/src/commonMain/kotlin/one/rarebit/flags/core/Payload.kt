package one.rarebit.flags.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A value an OFREP entry carries. The standard_flags wire only sends booleans and strings. */
public sealed interface FlagValue {
    public data class Bool(val value: Boolean) : FlagValue
    public data class Text(val value: String) : FlagValue

    /** Anything else (a number, an object, null): never usable, kept so nothing is lost. */
    public data class Other(val json: JsonElement) : FlagValue

    public companion object {
        internal fun of(json: JsonElement): FlagValue = when {
            json !is JsonPrimitive || json is JsonNull -> Other(json)
            json.isString -> Text(json.content)
            json.content == "true" || json.content == "false" -> Bool(json.content == "true")
            else -> Other(json)
        }
    }
}

/**
 * One entry of an OFREP bulk response. Parsing is strict about the shape the
 * conformance suite pins (an object with a non-empty string `key`) and tolerant
 * about everything else: unknown fields are ignored, and a field of the wrong
 * type reads as absent.
 *
 * A result type: later versions may add fields (OFREP entries can also carry
 * `metadata`), so read its properties by name. Don't destructure it or rely on
 * its `copy` or constructor signature.
 */
public data class FlagEntry(
    /** The wire name. */
    val key: String,
    /** The value, or null for OFREP's code-default form (no `value`). */
    val value: FlagValue?,
    /** The entry's `reason` when it is a string (STATIC, KILL_SWITCH, …). */
    val reason: String?,
    /** The entry's `variant` when it is a string. */
    val variant: String?,
    /** Whether the entry carries a non-null `errorCode`, which makes it an evaluationFailure. */
    val failed: Boolean,
    /** The `errorCode` when it is a string. */
    val errorCode: String?,
    /** The `errorDetails` when it is a string. */
    val errorDetails: String?,
) {
    /** Whether the server says this flag is held off by a kill switch. */
    val isKillSwitch: Boolean get() = !failed && reason == REASON_KILL_SWITCH

    public companion object {
        public const val REASON_KILL_SWITCH: String = "KILL_SWITCH"

        internal fun of(json: JsonObject, key: String): FlagEntry {
            val error = json["errorCode"]
            return FlagEntry(
                key = key,
                value = json["value"]?.let(FlagValue::of),
                reason = json.string("reason"),
                variant = json.string("variant"),
                failed = error != null && error !is JsonNull,
                errorCode = json.string("errorCode"),
                errorDetails = json.string("errorDetails"),
            )
        }
    }
}

/**
 * Limits a payload must meet before it replaces the last good one.
 *
 * A regular class, not a data class, so a limit can be added later without
 * changing `copy` or `componentN`. When adding one, keep the previous
 * constructor as a secondary constructor (for example
 * `@Deprecated(level = DeprecationLevel.HIDDEN)`), so binaries built against
 * an earlier version still link.
 */
public class PayloadLimits(
    /** The most bytes a payload may have. A payload of exactly this size is accepted. */
    public val maxBytes: Int = 256 * 1024,
    /** The most entries `flags` may hold. */
    public val maxFlags: Int = 2048,
    /** The most nested containers (objects and arrays, the top level counting as 1), at most [MAX_NESTING_CAP]. */
    public val maxNesting: Int = DEFAULT_MAX_NESTING,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxFlags >= 0) { "maxFlags must not be negative" }
        require(maxNesting in 1..MAX_NESTING_CAP) { "maxNesting must be between 1 and $MAX_NESTING_CAP" }
    }

    override fun equals(other: Any?): Boolean =
        other is PayloadLimits &&
            maxBytes == other.maxBytes &&
            maxFlags == other.maxFlags &&
            maxNesting == other.maxNesting

    override fun hashCode(): Int {
        var result = maxBytes
        result = 31 * result + maxFlags
        result = 31 * result + maxNesting
        return result
    }

    override fun toString(): String =
        "PayloadLimits(maxBytes=$maxBytes, maxFlags=$maxFlags, maxNesting=$maxNesting)"

    public companion object {
        /** The server's own JSON parser's `max_nesting`. */
        public const val DEFAULT_MAX_NESTING: Int = 100

        /** The largest [maxNesting] accepted: the scanner allocates a stack of that depth on every parse. */
        public const val MAX_NESTING_CAP: Int = 1000
    }
}

/** Why a payload was rejected. The last good payload stays in place. */
public enum class RejectReason {
    TooLarge,
    NotJson,
    NotAnObject,
    FlagsNotAnArray,
    TooManyFlags,
    BadEntry,
    DuplicateKey,
    MetadataNotAnObject,
}

/** The outcome of validating a payload. */
public sealed interface PayloadResult {
    public data class Accepted(val payload: FlagPayload) : PayloadResult
    public data class Rejected(val reason: RejectReason) : PayloadResult
}

/**
 * An accepted OFREP bulk payload: the entries by wire name, the version to
 * send as If-None-Match, and the exact body (for the [LastGoodStore]).
 */
public class FlagPayload internal constructor(
    public val entries: Map<String, FlagEntry>,
    /** `metadata.version` when it is a string [isUsableVersion] accepts, else null. */
    public val version: String?,
    /** The body as received. */
    public val body: String,
) {
    /** The value `If-None-Match` should carry, or null when the payload had no version. */
    public val etag: String? get() = version?.let { "\"$it\"" }

    public companion object {
        /**
         * [base] with the [wires] of [fresh] applied: each listed wire takes
         * [fresh]'s entry, or loses its entry when [fresh] has none. Every other
         * wire keeps [base]'s entry (none when [base] is null). The result keeps
         * [base]'s version and body, so it is never mistaken for [fresh].
         */
        public fun overlay(base: FlagPayload?, fresh: FlagPayload, wires: Set<String>): FlagPayload {
            val entries = LinkedHashMap(base?.entries.orEmpty())
            for (wire in wires) {
                val entry = fresh.entries[wire]
                if (entry == null) entries.remove(wire) else entries[wire] = entry
            }
            return FlagPayload(entries, base?.version, base?.body.orEmpty())
        }

        private val json = Json

        /** Validates [bytes] as an OFREP bulk body: the rules in conformance/README.md. */
        public fun parse(bytes: ByteArray, limits: PayloadLimits = PayloadLimits()): PayloadResult {
            if (bytes.size > limits.maxBytes) return PayloadResult.Rejected(RejectReason.TooLarge)
            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return PayloadResult.Rejected(RejectReason.NotJson)
            }
            return parseText(text, limits)
        }

        /**
         * Whether a `metadata.version` is kept to send back as an entity tag:
         * 1 to 256 characters, every one printable ASCII other than a space or
         * `"` (U+0021, U+0023 to U+007E), as conformance/README.md pins. The
         * server sends 64 hex digits. Anything else is ignored, so no
         * If-None-Match is sent rather than a header the HTTP stack, a proxy or
         * the server refuses on every request. A stored payload is parsed again
         * when loaded, so the same rule applies to its version.
         */
        public fun isUsableVersion(version: String): Boolean =
            version.length in 1..MAX_VERSION_LENGTH && version.all { it.code == 0x21 || it.code in 0x23..0x7e }

        private const val MAX_VERSION_LENGTH = 256

        /** Validates [text]; its UTF-8 size is measured against [PayloadLimits.maxBytes]. */
        public fun parse(text: String, limits: PayloadLimits = PayloadLimits()): PayloadResult =
            parse(text.encodeToByteArray(), limits)

        private fun parseText(text: String, limits: PayloadLimits): PayloadResult {
            // A byte-order mark makes the body not JSON (RFC 8259 §8.1).
            if (text.startsWith('\uFEFF')) return PayloadResult.Rejected(RejectReason.NotJson)
            // kotlinx keeps unquoted tokens (tru, NaN, 'x') as literals; check the grammar first.
            if (!StrictJson.isValid(text, limits.maxNesting)) return PayloadResult.Rejected(RejectReason.NotJson)
            val document = try {
                json.parseToJsonElement(text)
            } catch (_: SerializationException) {
                return PayloadResult.Rejected(RejectReason.NotJson)
            } catch (_: IllegalArgumentException) {
                return PayloadResult.Rejected(RejectReason.NotJson)
            }
            if (document !is JsonObject) return PayloadResult.Rejected(RejectReason.NotAnObject)
            val flags = document["flags"] as? JsonArray
                ?: return PayloadResult.Rejected(RejectReason.FlagsNotAnArray)
            val metadata = document["metadata"]
            if (metadata != null && metadata !is JsonObject) {
                return PayloadResult.Rejected(RejectReason.MetadataNotAnObject)
            }
            if (flags.size > limits.maxFlags) return PayloadResult.Rejected(RejectReason.TooManyFlags)
            val entries = LinkedHashMap<String, FlagEntry>(flags.size)
            for (element in flags) {
                val entry = element as? JsonObject ?: return PayloadResult.Rejected(RejectReason.BadEntry)
                val key = entry.string("key")
                if (key.isNullOrEmpty()) return PayloadResult.Rejected(RejectReason.BadEntry)
                if (key in entries) return PayloadResult.Rejected(RejectReason.DuplicateKey)
                entries[key] = FlagEntry.of(entry, key)
            }
            val version = metadata?.string("version")?.takeIf(::isUsableVersion)
            return PayloadResult.Accepted(FlagPayload(entries, version, text))
        }
    }
}

internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
