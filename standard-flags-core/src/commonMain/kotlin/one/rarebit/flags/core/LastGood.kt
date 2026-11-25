package one.rarebit.flags.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The last good payload, keeping it across rejected payloads and 304s: what
 * `wire/client_state.json` pins. Not thread-safe; the OFREP client confines it.
 */
public class FlagCache(public val limits: PayloadLimits = PayloadLimits()) {
    /** The last accepted payload, or null before any. */
    public var payload: FlagPayload? = null
        private set

    /** The version to send as If-None-Match, from the last accepted payload. */
    public val version: String? get() = payload?.version

    /** Validates [bytes]; an accepted payload replaces the last good one. */
    public fun receive(bytes: ByteArray): PayloadResult =
        FlagPayload.parse(bytes, limits).also { if (it is PayloadResult.Accepted) payload = it.payload }

    /** Replaces the last good payload with one validated elsewhere. */
    public fun accept(payload: FlagPayload) {
        this.payload = payload
    }

    /** A 304 Not Modified: nothing changes, even before any payload. */
    public fun notModified() {}

    /** Reads [definition] from the last good payload. */
    public fun <T : Any> read(definition: FlagDefinition<T>): FlagRead<T> = readFlag(payload, definition)
}

/**
 * A payload as the [LastGoodStore] keeps it.
 *
 * @property body the response body, re-validated when it is loaded.
 * @property contextKey the context it was fetched for (a user or install key);
 *   a stored payload for another context is never applied.
 * @property client the build it was fetched for (platform, version and build,
 *   see `ClientInfo.fingerprint` in the ofrep module). The server filters by
 *   build (`min_client`, deprecated wires), so a payload saved by another build
 *   is never applied either.
 * @property format the record's format; a record of a format this version
 *   doesn't know is ignored.
 */
@Serializable
public data class StoredPayload(
    val body: String,
    val contextKey: String? = null,
    val client: String? = null,
    val format: Int = FORMAT,
) {
    public companion object {
        /** The format this version writes and reads. */
        public const val FORMAT: Int = 1
    }
}

/**
 * Where the last good payload survives a cold start. Back it with the app's
 * own key-value store (DataStore, NSUserDefaults, a file); [StringLastGoodStore]
 * adapts any string store.
 *
 * A custom store must keep every field of [StoredPayload], `client` and
 * `format` included: a record that comes back without them never matches the
 * running build, so it is never applied. Return null when nothing is stored.
 * A store may throw; the client reports the failure (`storeError`) and keeps
 * its in-memory values, so a throw is never fatal, only unsaved.
 */
public interface LastGoodStore {
    public suspend fun load(): StoredPayload?

    public suspend fun save(payload: StoredPayload)

    public suspend fun clear()
}

/** A [LastGoodStore] that lives as long as the process. For tests and previews. */
public class InMemoryLastGoodStore(private var stored: StoredPayload? = null) : LastGoodStore {
    override suspend fun load(): StoredPayload? = stored

    override suspend fun save(payload: StoredPayload) {
        stored = payload
    }

    override suspend fun clear() {
        stored = null
    }
}

/**
 * A [LastGoodStore] over a single string value in the app's key-value store.
 * A value that doesn't decode (another format, a truncated write) reads as
 * nothing stored.
 *
 * ```
 * StringLastGoodStore(
 *     read = { settings.getStringOrNull("flags.last_good") },
 *     write = { value -> if (value == null) settings.remove("flags.last_good") else settings.putString("flags.last_good", value) },
 * )
 * ```
 */
public class StringLastGoodStore(
    private val read: suspend () -> String?,
    private val write: suspend (String?) -> Unit,
) : LastGoodStore {
    override suspend fun load(): StoredPayload? {
        val text = read() ?: return null
        return try {
            json.decodeFromString(StoredPayload.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override suspend fun save(payload: StoredPayload) {
        write(json.encodeToString(StoredPayload.serializer(), payload))
    }

    override suspend fun clear() {
        write(null)
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
