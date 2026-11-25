package one.rarebit.flags.ofrep

import io.ktor.http.fromHttpToGmtDate
import io.ktor.util.date.GMTDate
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Jittered exponential backoff between failed fetches. The n-th consecutive
 * failure waits `min(max, initial * multiplier^(n-1))`, of which a random half
 * is jitter ("equal jitter"), so many clients that failed together don't
 * retry together. A server `Retry-After` is honoured as a floor, capped at
 * [maxRetryAfter] so a bad header can't silence the client for days.
 */
public class Backoff(
    public val initial: Duration = 2.seconds,
    public val max: Duration = 5.minutes,
    public val multiplier: Double = 2.0,
    public val maxRetryAfter: Duration = 1.hours,
    private val random: Random = Random.Default,
) {
    init {
        require(initial.isPositive()) { "initial must be positive" }
        require(max >= initial) { "max must be at least initial" }
        require(multiplier >= 1.0) { "multiplier must be at least 1" }
    }

    /** How long to wait after [failures] consecutive failures (at least 1). */
    public fun delay(failures: Int, retryAfter: Duration? = null): Duration {
        val exponent = (failures.coerceAtLeast(1) - 1).toDouble()
        val ceiling = min(max.inWholeMilliseconds.toDouble(), initial.inWholeMilliseconds * multiplier.pow(exponent))
        val half = ceiling / 2
        val jittered = (half + random.nextDouble() * half).milliseconds
        val floor = retryAfter?.coerceIn(Duration.ZERO, maxRetryAfter) ?: Duration.ZERO
        return maxOf(jittered, floor)
    }

    public companion object {
        /**
         * A `Retry-After` header as a duration from now: delta-seconds or an
         * HTTP-date. Null when absent or unreadable; never negative.
         */
        public fun parseRetryAfter(value: String?, now: GMTDate = GMTDate()): Duration? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (text.all { it in '0'..'9' }) return text.toLongOrNull()?.seconds
            return try {
                (text.fromHttpToGmtDate().timestamp - now.timestamp).coerceAtLeast(0).milliseconds
            } catch (_: Exception) {
                null
            }
        }
    }
}
