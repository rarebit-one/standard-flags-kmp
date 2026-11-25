package one.rarebit.flags.ofrep

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.rarebit.flags.core.BooleanFlag
import one.rarebit.flags.core.FlagDefinition
import one.rarebit.flags.core.FlagPayload
import one.rarebit.flags.core.FlagRead
import one.rarebit.flags.core.FlagState
import one.rarebit.flags.core.InMemoryLastGoodStore
import one.rarebit.flags.core.LastGoodStore
import one.rarebit.flags.core.PayloadLimits
import one.rarebit.flags.core.PayloadResult
import one.rarebit.flags.core.RejectReason
import one.rarebit.flags.core.StoredPayload
import one.rarebit.flags.core.readFlag
import one.rarebit.flags.core.state
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * When fresh values reach code.
 *
 * @property freshValuesAtNextLaunch fresh values are saved and apply at the
 *   next launch, so a screen never changes under the user. Kill switches
 *   (reason `KILL_SWITCH`, or a [BooleanFlag.killSwitch]) always apply live.
 *   False applies every fresh payload at once.
 * @property firstPayloadAppliesLive when nothing was loaded at launch (a
 *   first run, or right after the context changed), the first payload applies
 *   at once instead of waiting a launch.
 */
public data class ApplyPolicy(
    val freshValuesAtNextLaunch: Boolean = true,
    val firstPayloadAppliesLive: Boolean = true,
)

/** The outcome of the last fetch, for diagnostics. */
public sealed interface FetchStatus {
    /** No fetch has finished yet. */
    public data object Idle : FetchStatus

    /** A fresh payload was accepted. [appliedNow] is false when it waits for the next launch. */
    public data class Updated(val appliedNow: Boolean) : FetchStatus

    /** The server answered 304. */
    public data object NotModified : FetchStatus

    /** The server's payload was rejected; the last good one stays. */
    public data class Rejected(val reason: RejectReason) : FetchStatus

    /** The fetch failed; the next one waits [retryIn]. */
    public data class Failed(val status: Int?, val retryIn: Duration, val cause: Throwable?) : FetchStatus

    /** The server answered 401 or 403; fetching stops until [StandardFlagsClient.setContext]. */
    public data class Stopped(val status: Int) : FetchStatus
}

/** What a call to [StandardFlagsClient.refresh] did. */
public enum class RefreshOutcome { Fetched, SkippedBackoff, SkippedStopped }

/**
 * The client-side flag cache: last good payload, apply-at-launch, live kill
 * switches, backoff and the stop on 401/403.
 *
 * ```
 * val flags = StandardFlagsClient(
 *     fetcher = OfrepClient(http, "https://api.example.com/ofrep/v1/evaluate/flags", ClientInfo("ios", "1.4.0", 212)),
 *     definitions = listOf(Chat, Purchases, HomeHero),
 *     store = myStore,
 *     contextKey = account.id,
 * )
 * flags.load()                   // at launch: the cached values
 * scope.launch { flags.refresh() } // and on every foreground
 * when (flags.state(Chat)) { FlagState.On -> …; FlagState.Off -> …; FlagState.NotLoaded -> … }
 * ```
 *
 * Reads never suspend and never throw. All mutation is serialised.
 *
 * @param contextKey whom the payload is for (an account or install ID). A
 *   stored payload for another key is never applied.
 */
public class StandardFlagsClient(
    private val fetcher: FlagsFetcher,
    definitions: Collection<FlagDefinition<*>> = emptyList(),
    private val store: LastGoodStore = InMemoryLastGoodStore(),
    private val policy: ApplyPolicy = ApplyPolicy(),
    private val backoff: Backoff = Backoff(),
    private val limits: PayloadLimits = PayloadLimits(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    contextKey: String? = null,
) {
    private val definitions: Map<String, FlagDefinition<*>> = definitions.associateBy { it.wire }
    private val declaredKillSwitches: Set<String> =
        definitions.filterIsInstance<BooleanFlag>().filter { it.killSwitch }.map { it.wire }.toSet()
    private val mutex = Mutex()

    private var contextKey: String? = contextKey
    private var loaded = false
    private var etag: String? = null
    private var failures = 0
    private var retryAt: TimeMark? = null
    private var stopped = false

    private val active = MutableStateFlow<FlagPayload?>(null)
    private val latest = MutableStateFlow<FlagPayload?>(null)
    private val fetchStatus = MutableStateFlow<FetchStatus>(FetchStatus.Idle)

    /** The payload code reads now: the launch values plus any live kill switches. Null: nothing loaded. */
    public val payload: StateFlow<FlagPayload?> = active.asStateFlow()

    /** The newest accepted payload, which applies at the next launch. */
    public val pending: StateFlow<FlagPayload?> = latest.asStateFlow()

    /** The outcome of the last fetch. */
    public val status: StateFlow<FetchStatus> = fetchStatus.asStateFlow()

    /** Reads [definition] from the active payload. */
    public fun <T : Any> read(definition: FlagDefinition<T>): FlagRead<T> = readFlag(active.value, definition)

    /** The tri-state of a boolean flag. */
    public fun state(definition: BooleanFlag): FlagState = read(definition).state

    /** The value code should use: the server's when usable, else the code default. */
    public fun <T : Any> value(definition: FlagDefinition<T>): T = read(definition).value

    /** The declared definition for [wire], if any. */
    public fun definition(wire: String): FlagDefinition<*>? = definitions[wire]

    /**
     * Loads the last good payload from the store, once. Call it at launch
     * before the first read; the values it loads are the launch values.
     */
    public suspend fun load(): Unit = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        val stored = store.load() ?: return
        if (stored.contextKey != contextKey) return
        val result = FlagPayload.parse(stored.body, limits)
        if (result is PayloadResult.Accepted) {
            active.value = result.payload
            latest.value = result.payload
            etag = result.payload.etag
        }
    }

    /**
     * Seeds the cache from an embedded bootstrap payload (the same bulk body,
     * for example inside a `/me` response). It is treated like a fetched
     * payload: saved, used for If-None-Match, and applied by the [ApplyPolicy].
     */
    public suspend fun seed(body: String): PayloadResult = mutex.withLock {
        loadLocked()
        val result = FlagPayload.parse(body, limits)
        if (result is PayloadResult.Accepted) accept(result.payload, null)
        result
    }

    /**
     * Fetches the bulk evaluation unless a backoff window or a 401/403 stop
     * holds. Call it on launch, on foreground and after the context changes.
     */
    public suspend fun refresh(): RefreshOutcome = mutex.withLock {
        loadLocked()
        if (stopped) return@withLock RefreshOutcome.SkippedStopped
        if (retryAt?.hasPassedNow() == false) return@withLock RefreshOutcome.SkippedBackoff
        when (val result = fetcher.fetch(etag)) {
            is FetchResult.Fresh -> when (val payload = result.result) {
                is PayloadResult.Accepted -> accept(payload.payload, result.etag)
                is PayloadResult.Rejected -> fail(FetchStatus.Rejected(payload.reason), null, null, null)
            }
            FetchResult.NotModified -> {
                succeed()
                fetchStatus.value = FetchStatus.NotModified
            }
            is FetchResult.Unauthorized -> {
                stopped = true
                fetchStatus.value = FetchStatus.Stopped(result.status)
            }
            is FetchResult.Failed -> fail(null, result.status, result.retryAfter, result.cause)
        }
        RefreshOutcome.Fetched
    }

    /**
     * The context changed (sign-in, sign-out, another account). Values for the
     * old context are dropped at once and the stored payload is cleared, the
     * 401/403 stop and the backoff are reset, and the next payload applies live
     * (with the default policy). Call [refresh] afterwards.
     */
    public suspend fun setContext(contextKey: String?): Unit = mutex.withLock {
        loadLocked()
        if (contextKey == this.contextKey) {
            stopped = false
            return@withLock
        }
        this.contextKey = contextKey
        active.value = null
        latest.value = null
        etag = null
        stopped = false
        failures = 0
        retryAt = null
        fetchStatus.value = FetchStatus.Idle
        store.clear()
    }

    private suspend fun accept(payload: FlagPayload, responseEtag: String?) {
        succeed()
        latest.value = payload
        etag = responseEtag ?: payload.etag
        store.save(StoredPayload(payload.body, contextKey))
        val current = active.value
        val applyAll = !policy.freshValuesAtNextLaunch || (current == null && policy.firstPayloadAppliesLive)
        if (applyAll) {
            active.value = payload
        } else {
            val live = declaredKillSwitches + payload.entries.values.filter { it.isKillSwitch }.map { it.key }
            val merged = FlagPayload.overlay(current, payload, live)
            if (merged.entries != current?.entries.orEmpty()) active.value = merged
        }
        fetchStatus.value = FetchStatus.Updated(appliedNow = applyAll)
    }

    private fun succeed() {
        failures = 0
        retryAt = null
    }

    private fun fail(rejected: FetchStatus.Rejected?, status: Int?, retryAfter: Duration?, cause: Throwable?) {
        failures++
        val wait = backoff.delay(failures, retryAfter)
        retryAt = timeSource.markNow() + wait
        fetchStatus.value = rejected ?: FetchStatus.Failed(status, wait, cause)
    }
}
