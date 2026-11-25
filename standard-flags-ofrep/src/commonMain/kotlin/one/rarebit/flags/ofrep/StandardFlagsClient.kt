package one.rarebit.flags.ofrep

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 *   first run, a new build, or right after the context changed), the first
 *   payload applies at once instead of waiting a launch.
 */
public data class ApplyPolicy(
    val freshValuesAtNextLaunch: Boolean = true,
    val firstPayloadAppliesLive: Boolean = true,
)

/** The outcome of the last fetch, for diagnostics. */
public sealed interface FetchStatus {
    /** No fetch has finished yet (or the context just changed). */
    public data object Idle : FetchStatus

    /**
     * A fresh payload was accepted.
     *
     * @property appliedNow the whole payload applies now; false when it waits
     *   for the next launch.
     * @property appliedLive the wires that changed live (kill switches) when
     *   the rest waits.
     * @property saved whether the [LastGoodStore] kept it. When it didn't, the
     *   payload isn't the pending one and its ETag isn't used, so the next
     *   fetch gets the full body again.
     */
    public data class Updated(val appliedNow: Boolean, val appliedLive: Set<String>, val saved: Boolean) : FetchStatus

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
public sealed interface RefreshOutcome {
    /** A fetch ran (or one already running for this context was joined); [status] is its result. */
    public data class Completed(val status: FetchStatus) : RefreshOutcome

    /** A backoff window holds; nothing was fetched. */
    public data object SkippedBackoff : RefreshOutcome

    /** A 401/403 stop holds; nothing was fetched. */
    public data object SkippedStopped : RefreshOutcome

    /** The context changed while the fetch ran, so its answer was thrown away. */
    public data object Discarded : RefreshOutcome
}

/** Which [LastGoodStore] call failed. */
public enum class StoreOperation { Load, Save, Clear }

/** A [LastGoodStore] failure. It never escapes the client; the in-memory values stay. */
public data class StoreError(val operation: StoreOperation, val cause: Throwable)

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
 * flags.load()                     // at launch: the cached values
 * scope.launch { flags.refresh() } // and on every foreground
 * when (flags.state(Chat)) { FlagState.On -> …; FlagState.Off -> …; FlagState.NotLoaded -> … }
 * ```
 *
 * Reads never suspend and never throw, and neither does any suspending call
 * except by cancellation. The lock that guards the state is never held across
 * a fetch or a store call, so [setContext] takes effect at once even while a
 * request stalls; an answer for the old context is thrown away.
 *
 * The stored payload is keyed by the context and by the build
 * ([ClientInfo.fingerprint]). A payload saved for another account, or by an
 * older or newer build, is never applied: the server filters by build, so the
 * old build's answer could withhold or serve the wrong wires. The first payload
 * for the new build then applies live (with the default [ApplyPolicy]).
 *
 * Switch credentials before calling [setContext]: a fetch started after it
 * carries whatever credentials the HTTP client then has.
 *
 * @param limits the payload limits, applied to fetched, seeded and stored
 *   payloads alike (the fetcher reads at most `limits.maxBytes`).
 * @param contextKey whom the payload is for (an account or install ID).
 *   Blank means none. Keep it equal to the OpenFeature targeting key when you
 *   use the OpenFeature provider.
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
    private val clientFingerprint: String? = fetcher.client?.fingerprint

    // Guards the fields below. Never held across a fetch or a store call.
    private val mutex = Mutex()

    // Serialises store calls, so a save for an old context can't land after the clear.
    private val storeMutex = Mutex()

    private var contextKey: String? = normalizeContextKey(contextKey)
    private var generation = 0L
    private var loaded = false
    private var etag: String? = null
    private var failures = 0
    private var networkFailures = 0
    private var retryAt: TimeMark? = null
    private var stopped = false
    private var inFlight: InFlight? = null

    private class InFlight(val generation: Long, val etag: String?) {
        val outcome = CompletableDeferred<RefreshOutcome>()
    }

    private val active = MutableStateFlow<FlagPayload?>(null)
    private val latest = MutableStateFlow<FlagPayload?>(null)
    private val fetchStatus = MutableStateFlow<FetchStatus>(FetchStatus.Idle)
    private val storeFailure = MutableStateFlow<StoreError?>(null)

    /** The payload code reads now: the launch values plus any live kill switches. Null: nothing loaded. */
    public val payload: StateFlow<FlagPayload?> = active.asStateFlow()

    /** The newest saved payload, which applies at the next launch. */
    public val pending: StateFlow<FlagPayload?> = latest.asStateFlow()

    /** The outcome of the last fetch. */
    public val status: StateFlow<FetchStatus> = fetchStatus.asStateFlow()

    /** The last [LastGoodStore] failure, if any. */
    public val storeError: StateFlow<StoreError?> = storeFailure.asStateFlow()

    /** Reads [definition] from the active payload. */
    public fun <T : Any> read(definition: FlagDefinition<T>): FlagRead<T> = readFlag(active.value, definition)

    /** The tri-state of a boolean flag. */
    public fun state(definition: BooleanFlag): FlagState = read(definition).state

    /** The value code should use: the server's when usable, else the code default. */
    public fun <T : Any> value(definition: FlagDefinition<T>): T = read(definition).value

    /** The declared definition for [wire], if any. */
    public fun definition(wire: String): FlagDefinition<*>? = definitions[wire]

    /**
     * Loads the last good payload for the current context and build from the
     * store. Call it at launch before the first read; the values it loads are
     * the launch values. It runs once per context: a store failure or a
     * cancellation leaves it to run again on the next call.
     */
    public suspend fun load() {
        val (generation, key) = mutex.withLock {
            if (loaded) return
            generation to contextKey
        }
        val read = storeMutex.withLock { storeCall(StoreOperation.Load) { store.load() } }
        // A failed read leaves loaded false, so the next call tries again.
        if (read.isFailure) return
        mutex.withLock {
            if (this.generation != generation || loaded) return
            loaded = true
            val stored = read.getOrNull() ?: return
            if (active.value != null) return
            if (stored.format != StoredPayload.FORMAT || stored.contextKey != key || stored.client != clientFingerprint) return
            val result = FlagPayload.parse(stored.body, limits)
            if (result is PayloadResult.Accepted) {
                active.value = result.payload
                latest.value = result.payload
                etag = result.payload.etag
            }
        }
    }

    /**
     * Seeds the cache from an embedded bootstrap payload (the same bulk body,
     * for example inside a `/me` response). It is treated like a fetched
     * payload: saved, used for If-None-Match, and applied by the [ApplyPolicy].
     */
    public suspend fun seed(body: String): PayloadResult {
        load()
        val result = FlagPayload.parse(body, limits)
        if (result is PayloadResult.Accepted) {
            val generation = mutex.withLock { generation }
            accept(generation, result.payload, null)
        }
        return result
    }

    /**
     * Fetches the bulk evaluation unless a backoff window or a 401/403 stop
     * holds. Call it on launch, on foreground and after the context changes.
     * A call while a fetch for the same context runs joins that fetch.
     */
    public suspend fun refresh(): RefreshOutcome {
        load()
        var joined: InFlight? = null
        val ticket = mutex.withLock {
            if (stopped) return RefreshOutcome.SkippedStopped
            if (retryAt?.hasPassedNow() == false) return RefreshOutcome.SkippedBackoff
            val running = inFlight
            if (running != null && running.generation == generation) {
                joined = running
                null
            } else {
                InFlight(generation, etag).also { inFlight = it }
            }
        }
        if (ticket == null) {
            return try {
                joined!!.outcome.await()
            } catch (cancelled: CancellationException) {
                // The fetch we joined was cancelled with its caller; run our own unless we are cancelled too.
                currentCoroutineContext().ensureActive()
                refresh()
            }
        }
        try {
            val result = try {
                fetcher.fetch(ticket.etag, limits.maxBytes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                FetchResult.Failed(null, null, error)
            }
            val outcome = handle(ticket.generation, result)
            ticket.outcome.complete(outcome)
            return outcome
        } catch (error: Throwable) {
            ticket.outcome.cancel(CancellationException("the fetch was cancelled", error))
            throw error
        } finally {
            withContext(NonCancellable) { mutex.withLock { if (inFlight === ticket) inFlight = null } }
        }
    }

    /**
     * The context changed (sign-in, sign-out, another account). Values for the
     * old context are dropped at once, an answer still in flight for it is
     * thrown away, the 401/403 stop and the backoff are reset, and a stored
     * payload for another context is cleared. The next payload applies live
     * (with the default policy). Call [refresh] afterwards.
     *
     * Called before [load] (as the OpenFeature provider does at start-up), it
     * only adopts the key, so the stored payload for that key still loads.
     */
    public suspend fun setContext(contextKey: String?) {
        val key = normalizeContextKey(contextKey)
        mutex.withLock {
            stopped = false
            if (key == this.contextKey) return
            this.contextKey = key
            generation++
            loaded = false
            active.value = null
            latest.value = null
            etag = null
            failures = 0
            networkFailures = 0
            retryAt = null
            fetchStatus.value = FetchStatus.Idle
        }
        storeMutex.withLock {
            val stored = storeCall(StoreOperation.Load) { store.load() }.getOrNull()
            if (stored != null && stored.contextKey != key) storeCall(StoreOperation.Clear) { store.clear() }
        }
    }

    private suspend fun handle(generation: Long, result: FetchResult): RefreshOutcome {
        if (result is FetchResult.Fresh) {
            return when (val parsed = FlagPayload.parse(result.body, limits)) {
                is PayloadResult.Accepted -> accept(generation, parsed.payload, result.etag?.takeIf(::isEntityTag))
                is PayloadResult.Rejected -> settle(generation) { fail(FetchStatus.Rejected(parsed.reason), null, null, null) }
            }
        }
        return settle(generation) {
            when (result) {
                FetchResult.TooLarge -> fail(FetchStatus.Rejected(RejectReason.TooLarge), null, null, null)
                FetchResult.NotModified -> {
                    succeed()
                    fetchStatus.value = FetchStatus.NotModified
                }
                is FetchResult.Unauthorized -> {
                    stopped = true
                    fetchStatus.value = FetchStatus.Stopped(result.status)
                }
                is FetchResult.Failed -> fail(null, result.status, result.retryAfter, result.cause)
                is FetchResult.Fresh -> error("handled above")
            }
        }
    }

    /** Runs [block] under the lock unless the context changed since [generation]. */
    private suspend fun settle(generation: Long, block: () -> Unit): RefreshOutcome = mutex.withLock {
        if (generation != this.generation) return RefreshOutcome.Discarded
        block()
        RefreshOutcome.Completed(fetchStatus.value)
    }

    /**
     * Applies first (kill switches must never wait on the disk), then saves.
     * Only a saved payload becomes the pending one and supplies the ETag.
     */
    private suspend fun accept(generation: Long, payload: FlagPayload, responseEtag: String?): RefreshOutcome {
        val applied: Boolean
        val live: Set<String>
        val record: StoredPayload
        mutex.withLock {
            if (generation != this.generation) return RefreshOutcome.Discarded
            succeed()
            val current = active.value
            applied = !policy.freshValuesAtNextLaunch || (current == null && policy.firstPayloadAppliesLive)
            if (applied) {
                active.value = payload
                live = emptySet()
            } else {
                val wires = declaredKillSwitches + payload.entries.values.filter { it.isKillSwitch }.map { it.key }
                val merged = FlagPayload.overlay(current, payload, wires)
                live = wires.filterTo(mutableSetOf()) { merged.entries[it] != current?.entries?.get(it) }
                if (live.isNotEmpty()) active.value = merged
            }
            record = StoredPayload(payload.body, contextKey, clientFingerprint)
            fetchStatus.value = FetchStatus.Updated(applied, live, saved = false)
        }
        // The save and what follows it run to the end even if the caller is cancelled, and never throw.
        return withContext(NonCancellable) {
            val saved = storeMutex.withLock {
                if (mutex.withLock { generation != this@StandardFlagsClient.generation }) return@withLock false
                storeCall(StoreOperation.Save) { store.save(record) }.isSuccess
            }
            mutex.withLock {
                if (generation != this@StandardFlagsClient.generation) return@withLock RefreshOutcome.Discarded
                if (saved) {
                    latest.value = payload
                    etag = responseEtag ?: payload.etag
                }
                val status = FetchStatus.Updated(applied, live, saved)
                fetchStatus.value = status
                RefreshOutcome.Completed(status)
            }
        }
    }

    private suspend fun <T> storeCall(operation: StoreOperation, call: suspend () -> T): Result<T> = try {
        Result.success(call())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        storeFailure.value = StoreError(operation, error)
        Result.failure(error)
    }

    private fun succeed() {
        failures = 0
        networkFailures = 0
        retryAt = null
    }

    private fun fail(rejected: FetchStatus.Rejected?, status: Int?, retryAfter: Duration?, cause: Throwable?) {
        failures++
        if (rejected == null && status == null) {
            // Repeated client-side failures (a header the HTTP stack refuses, say): stop sending the ETag.
            if (++networkFailures >= CLIENT_FAILURES_BEFORE_ETAG_RESET) etag = null
        }
        val wait = backoff.delay(failures, retryAfter)
        retryAt = timeSource.markNow() + wait
        fetchStatus.value = rejected ?: FetchStatus.Failed(status, wait, cause)
    }

    public companion object {
        private const val CLIENT_FAILURES_BEFORE_ETAG_RESET = 3
        private val ENTITY_TAG = Regex("(W/)?\"[\\x21\\x23-\\x7e]{0,256}\"")

        /** The form [StandardFlagsClient] and the OpenFeature provider both key contexts by: trimmed, blank as none. */
        public fun normalizeContextKey(key: String?): String? = key?.trim()?.takeIf { it.isNotEmpty() }

        private fun isEntityTag(value: String): Boolean = ENTITY_TAG.matches(value)
    }
}
