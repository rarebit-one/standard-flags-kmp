package one.rarebit.flags.core

/**
 * A wire key the app reads, with its code default. The code default applies
 * whenever the server's value can't be used: before any payload, when the
 * entry is missing or errored, or when its value has the wrong type.
 */
public sealed class FlagDefinition<T : Any> {
    /** The wire name the server exposes to this app's audience. */
    public abstract val wire: String

    /** The value code uses when the server's can't be. */
    public abstract val default: T
}

/**
 * A boolean wire key.
 *
 * @property ceiling the server may only lower this flag: the value read is
 *   `default && server value`, so a flag that defaults off can't be switched on
 *   remotely (for example, purchases on a platform whose store rules forbid it).
 * @property killSwitch the app treats this flag as a kill switch, so a fresh
 *   value applies live in both directions, not at the next launch. Entries the
 *   server sends with reason `KILL_SWITCH` apply live whatever this says.
 */
public data class BooleanFlag(
    override val wire: String,
    override val default: Boolean,
    val ceiling: Boolean = false,
    val killSwitch: Boolean = false,
) : FlagDefinition<Boolean>()

/**
 * A string wire key (a mode or an experiment).
 *
 * @property values the values the app understands; any other value reads as
 *   the code default. Null accepts any string.
 */
public data class StringFlag(
    override val wire: String,
    override val default: String,
    val values: Set<String>? = null,
) : FlagDefinition<String>()

/** A boolean flag's tri-state: on, off, or not loaded (the code default applies). */
public enum class FlagState { On, Off, NotLoaded }

/** Why a read fell back to the code default. */
public enum class FallbackCause {
    /** No payload has been accepted yet. */
    NoPayload,

    /** The payload has no entry for the wire key (or the server withheld it). */
    Missing,

    /** The entry is an evaluationFailure. */
    Errored,

    /** The entry has no value: OFREP's "use the code default". */
    NoValue,

    /** The value isn't of the declared type. */
    WrongType,

    /** A string value outside the declared values. */
    NotAllowed,
}

/**
 * What code reads for one flag.
 *
 * @property value the value to use: the server's, or the code default.
 * @property loaded whether [value] came from the server (a ceiling that capped
 *   the server's value still counts as loaded).
 * @property reason the entry's reason when [value] is the server's value.
 * @property variant the entry's variant when [value] is the server's value.
 * @property cause why the code default applies; null when [loaded].
 * @property entry the entry read, if any.
 */
public data class FlagRead<out T : Any>(
    val value: T,
    val loaded: Boolean,
    val reason: String?,
    val variant: String?,
    val cause: FallbackCause?,
    val entry: FlagEntry?,
)

/** The tri-state of a boolean read. */
public val FlagRead<Boolean>.state: FlagState
    get() = when {
        !loaded -> FlagState.NotLoaded
        value -> FlagState.On
        else -> FlagState.Off
    }

/** Reads [definition] from [payload] (null: nothing accepted yet), by the conformance rules. */
public fun <T : Any> readFlag(payload: FlagPayload?, definition: FlagDefinition<T>): FlagRead<T> {
    fun fallback(cause: FallbackCause, entry: FlagEntry? = null) =
        FlagRead(definition.default, loaded = false, reason = null, variant = null, cause = cause, entry = entry)

    if (payload == null) return fallback(FallbackCause.NoPayload)
    val entry = payload.entries[definition.wire] ?: return fallback(FallbackCause.Missing)
    if (entry.failed) return fallback(FallbackCause.Errored, entry)
    val value = entry.value ?: return fallback(FallbackCause.NoValue, entry)

    @Suppress("UNCHECKED_CAST")
    return when (definition) {
        is BooleanFlag -> {
            val server = (value as? FlagValue.Bool)?.value ?: return fallback(FallbackCause.WrongType, entry)
            val effective = if (definition.ceiling) definition.default && server else server
            val own = effective == server
            FlagRead(
                effective,
                loaded = true,
                reason = entry.reason.takeIf { own },
                variant = entry.variant.takeIf { own },
                cause = null,
                entry = entry,
            ) as FlagRead<T>
        }
        is StringFlag -> {
            val server = (value as? FlagValue.Text)?.value ?: return fallback(FallbackCause.WrongType, entry)
            if (definition.values != null && server !in definition.values) {
                return fallback(FallbackCause.NotAllowed, entry)
            }
            FlagRead(server, loaded = true, reason = entry.reason, variant = entry.variant, cause = null, entry = entry)
                as FlagRead<T>
        }
    }
}
