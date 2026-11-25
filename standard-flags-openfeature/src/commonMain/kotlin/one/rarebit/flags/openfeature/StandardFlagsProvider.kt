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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import one.rarebit.flags.core.BooleanFlag
import one.rarebit.flags.core.FallbackCause
import one.rarebit.flags.core.FlagRead
import one.rarebit.flags.core.StringFlag
import one.rarebit.flags.ofrep.StandardFlagsClient
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
 * Reads go through the client's declarations, so ceilings and allowed values
 * hold. A key the client doesn't declare reads as a plain boolean or string
 * with the caller's default. The caller's default is the code default.
 *
 * Apps that bind their own seams to core don't need this module.
 *
 * @param initialFetchTimeout how long [initialize] waits for the first fetch
 *   before reporting ready with the cached values (or code defaults).
 */
public class StandardFlagsProvider(
    private val flags: StandardFlagsClient,
    private val initialFetchTimeout: Duration = 5.seconds,
    override val hooks: List<Hook<*>> = emptyList(),
) : FeatureProvider {

    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String = NAME
    }

    override suspend fun initialize(initialContext: EvaluationContext?) {
        flags.load()
        initialContext?.let { flags.setContext(contextKey(it)) }
        withTimeoutOrNull(initialFetchTimeout) { flags.refresh() }
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        flags.setContext(contextKey(newContext))
        withTimeoutOrNull(initialFetchTimeout) { flags.refresh() }
    }

    override fun shutdown() {}

    override fun getBooleanEvaluation(key: String, defaultValue: Boolean, context: EvaluationContext?): ProviderEvaluation<Boolean> {
        val declared = flags.definition(key) as? BooleanFlag
        if (flags.definition(key) != null && declared == null) return mismatch(key, defaultValue)
        return evaluate(key, flags.read(declared?.copy(default = defaultValue) ?: BooleanFlag(key, defaultValue)))
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

    /** A configuration-changed event whenever the values code reads change (a live kill switch, a new context). */
    override fun observe(): Flow<OpenFeatureProviderEvents> = flags.payload.drop(1).map {
        OpenFeatureProviderEvents.ProviderConfigurationChanged(
            OpenFeatureProviderEvents.EventDetails(flagsChanged = it?.entries?.keys.orEmpty()),
        )
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

    private fun contextKey(context: EvaluationContext): String? = context.getTargetingKey().ifEmpty { null }

    public companion object {
        public const val NAME: String = "standard_flags"
    }
}
