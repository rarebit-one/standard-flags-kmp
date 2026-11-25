package one.rarebit.flags.openfeature

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.withTimeoutOrNull
import one.rarebit.flags.core.BooleanFlag
import one.rarebit.flags.core.FallbackCause
import one.rarebit.flags.core.FlagPayload
import one.rarebit.flags.core.FlagRead
import one.rarebit.flags.core.StringFlag
import one.rarebit.flags.ofrep.StandardFlagsClient
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An OpenFeature [FeatureProvider] over a [StandardFlagsClient], so shared
 * code can read flags through the OpenFeature kotlin-sdk:
 *
 * ```
 * OpenFeatureAPI.setProviderAndWait(StandardFlagsProvider(flags), ImmutableContext(targetingKey = account.id))
 * val chat = OpenFeatureAPI.getClient().getBooleanValue("chat_enabled", false)
 * ```
 *
 * Reads go through the client's declarations, so allowed values hold, and a
 * ceiling flag is always capped by its declared default, whatever default the
 * caller passes. Otherwise the caller's default is the code default. A key the
 * client doesn't declare reads as a plain boolean or string.
 *
 * The targeting key is the client's context key: the provider passes it to
 * [StandardFlagsClient.setContext] before the first load, so both share one
 * cache key and the stored payload for that key loads at start-up.
 *
 * Apps that bind their own seams to core don't need this module.
 *
 * @param initialFetchTimeout how long [initialize] and [onContextSet] wait for
 *   a fetch before reporting ready with the cached values (or code defaults).
 *   The fetch itself carries on in [scope] and applies when it lands.
 * @param scope where those fetches run. Each [initialize] opens a child of it
 *   and [shutdown] cancels only that child, so the provider can be registered
 *   again. If [scope] itself is cancelled, [initialize] fails with
 *   [OpenFeatureError.ProviderFatalError].
 */
public class StandardFlagsProvider(
    private val flags: StandardFlagsClient,
    private val initialFetchTimeout: Duration = 5.seconds,
    override val hooks: List<Hook<*>> = emptyList(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : FeatureProvider {

    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String = NAME
    }

    // The child of [scope] this registration's fetches run in; replaced by each initialize.
    @Volatile
    private var fetches: CoroutineScope? = null

    override suspend fun initialize(initialContext: EvaluationContext?) {
        val parent = scope.coroutineContext[Job]
        if (parent != null && !parent.isActive) {
            throw OpenFeatureError.ProviderFatalError("the provider's coroutine scope is cancelled; pass an active scope")
        }
        fetches?.cancel()
        fetches = CoroutineScope(scope.coroutineContext + SupervisorJob(parent))
        // Adopt the key before loading, so the payload saved for it is the launch value.
        initialContext?.let { flags.setContext(it.getTargetingKey()) }
        flags.load()
        fetchAndWait()
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        flags.setContext(newContext.getTargetingKey())
        fetchAndWait()
    }

    override fun shutdown() {
        fetches?.cancel()
        fetches = null
    }

    /** Starts a refresh in [scope] and waits up to [initialFetchTimeout]; a slow fetch still completes. */
    private suspend fun fetchAndWait() {
        val fetches = fetches ?: throw OpenFeatureError.ProviderNotReadyError("the provider is shut down; initialize it again")
        val fetch = fetches.async { flags.refresh() }
        try {
            withTimeoutOrNull(initialFetchTimeout) { fetch.await() }
        } catch (cancelled: CancellationException) {
            // A shutdown cancelled the fetch, not us: report ready with what is cached.
            currentCoroutineContext().ensureActive()
        }
    }

    override fun getBooleanEvaluation(key: String, defaultValue: Boolean, context: EvaluationContext?): ProviderEvaluation<Boolean> {
        val declared = flags.definition(key) as? BooleanFlag
        if (flags.definition(key) != null && declared == null) return mismatch(key, defaultValue)
        val definition = when {
            declared == null -> BooleanFlag(key, defaultValue)
            // A ceiling is the declared default; a caller can't raise it.
            declared.ceiling -> declared
            else -> declared.copy(default = defaultValue)
        }
        return evaluate(key, flags.read(definition))
    }

    override fun getStringEvaluation(key: String, defaultValue: String, context: EvaluationContext?): ProviderEvaluation<String> {
        val declared = flags.definition(key) as? StringFlag
        if (flags.definition(key) != null && declared == null) return mismatch(key, defaultValue)
        return evaluate(key, flags.read(declared?.copy(default = defaultValue) ?: StringFlag(key, defaultValue)))
    }

    // The standard_flags wire carries booleans and strings only.
    override fun getIntegerEvaluation(key: String, defaultValue: Int, context: EvaluationContext?): ProviderEvaluation<Int> =
        mismatch(key, defaultValue)

    override fun getLongEvaluation(key: String, defaultValue: Long, context: EvaluationContext?): ProviderEvaluation<Long> =
        mismatch(key, defaultValue)

    override fun getDoubleEvaluation(key: String, defaultValue: Double, context: EvaluationContext?): ProviderEvaluation<Double> =
        mismatch(key, defaultValue)

    override fun getObjectEvaluation(key: String, defaultValue: Value, context: EvaluationContext?): ProviderEvaluation<Value> =
        mismatch(key, defaultValue)

    /**
     * A configuration-changed event whenever the values code reads change (a
     * live kill switch, the first payload, a new context), naming the wires
     * whose entries changed.
     */
    override fun observe(): Flow<OpenFeatureProviderEvents> = flags.payload
        .runningFold(null as Pair<FlagPayload?, FlagPayload?>?) { previous, next -> previous?.second to next }
        .drop(2)
        .mapNotNull { pair ->
            val (before, after) = pair ?: return@mapNotNull null
            val old = before?.entries.orEmpty()
            val new = after?.entries.orEmpty()
            val changed = (old.keys + new.keys).filter { old[it] != new[it] }.sorted().toSet()
            if (changed.isEmpty()) {
                null
            } else {
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails(flagsChanged = changed))
            }
        }

    private fun <T : Any> evaluate(key: String, read: FlagRead<T>): ProviderEvaluation<T> {
        if (read.loaded) return ProviderEvaluation(read.value, read.variant, read.reason ?: Reason.UNKNOWN.name)
        val entry = read.entry
        return when (read.cause) {
            FallbackCause.NoValue -> ProviderEvaluation(read.value, reason = Reason.DEFAULT.name)
            FallbackCause.NoPayload -> error(read.value, ErrorCode.PROVIDER_NOT_READY, "no flags loaded yet")
            FallbackCause.Missing -> error(read.value, ErrorCode.FLAG_NOT_FOUND, "the server sent no flag $key")
            FallbackCause.Errored -> error(read.value, errorCode(entry?.errorCode), entry?.errorDetails)
            FallbackCause.WrongType -> error(read.value, ErrorCode.TYPE_MISMATCH, "the server's value has another type")
            FallbackCause.NotAllowed -> error(read.value, ErrorCode.TYPE_MISMATCH, "the server's value isn't one the app declares")
            null -> error(read.value, ErrorCode.GENERAL, null)
        }
    }

    private fun <T> mismatch(key: String, defaultValue: T): ProviderEvaluation<T> =
        error(defaultValue, ErrorCode.TYPE_MISMATCH, "$key is not a flag of this type")

    private fun <T> error(value: T, code: ErrorCode, message: String?): ProviderEvaluation<T> =
        ProviderEvaluation(value, reason = Reason.ERROR.name, errorCode = code, errorMessage = message)

    private fun errorCode(code: String?): ErrorCode = when (code) {
        "PARSE_ERROR" -> ErrorCode.PARSE_ERROR
        "TARGETING_KEY_MISSING" -> ErrorCode.TARGETING_KEY_MISSING
        "INVALID_CONTEXT" -> ErrorCode.INVALID_CONTEXT
        "FLAG_NOT_FOUND" -> ErrorCode.FLAG_NOT_FOUND
        "TYPE_MISMATCH" -> ErrorCode.TYPE_MISMATCH
        else -> ErrorCode.GENERAL
    }

    public companion object {
        public const val NAME: String = "standard_flags"
    }
}
