package one.rarebit.flags.core

/** A comparison operator in a [VersionRange] or [BuildRange]. */
public enum class RangeOperator(public val symbol: String) {
    AtLeast(">="),
    Above(">"),
    AtMost("<="),
    Below("<"),
    Exactly("="),
    ;

    internal fun holds(order: Int): Boolean = when (this) {
        AtLeast -> order >= 0
        Above -> order > 0
        AtMost -> order <= 0
        Below -> order < 0
        Exactly -> order == 0
    }

    internal companion object {
        fun of(symbol: String): RangeOperator = entries.first { it.symbol == symbol }
    }
}

/** One `operator operand` test. */
public data class RangeComparator<T : Comparable<T>>(val operator: RangeOperator, val operand: T) {
    public fun satisfiedBy(value: T): Boolean = operator.holds(value.compareTo(operand))

    override fun toString(): String = "${operator.symbol}$operand"
}

/**
 * The neutral range syntax the server and every client share: comparators
 * (`>=`, `>`, `<=`, `<`, `=`) separated by ASCII spaces or tabs, all of which
 * must hold, joined by `||` alternatives. Whitespace after an operator is
 * optional. A range is at most 1024 characters of ASCII.
 */
internal object RangeSyntax {
    const val MAX_LENGTH: Int = 1024

    private const val COMPARATOR = "(>=|<=|>|<|=)[ \\t]*([^ \\t<>=|]+)"
    private val COMPARATOR_REGEX = Regex(COMPARATOR)
    private val ALTERNATIVE = Regex("[ \\t]*$COMPARATOR(?:[ \\t]+$COMPARATOR)*[ \\t]*")

    fun <T : Comparable<T>> parse(text: String?, operand: (String) -> T?): List<List<RangeComparator<T>>>? {
        val cleaned = Version.clean(text) ?: return null
        if (cleaned.length > MAX_LENGTH) return null
        val alternatives = mutableListOf<List<RangeComparator<T>>>()
        for (alternative in cleaned.split("||")) {
            if (!ALTERNATIVE.matches(alternative)) return null
            val comparators = mutableListOf<RangeComparator<T>>()
            for (match in COMPARATOR_REGEX.findAll(alternative)) {
                val value = operand(match.groupValues[2]) ?: return null
                comparators += RangeComparator(RangeOperator.of(match.groupValues[1]), value)
            }
            alternatives += comparators
        }
        return alternatives.ifEmpty { null }
    }

    fun <T : Comparable<T>> format(alternatives: List<List<RangeComparator<T>>>): String =
        alternatives.joinToString(" || ") { it.joinToString(" ") }

    fun <T : Comparable<T>> satisfied(alternatives: List<List<RangeComparator<T>>>, value: T): Boolean =
        alternatives.any { comparators -> comparators.all { it.satisfiedBy(value) } }
}

/**
 * A range of client versions in the neutral syntax:
 *
 * ```
 * val range = VersionRange.parse(">=1.4.0 <2.0.0 || =0.9.5-beta.1")!!
 * range.matches("1.9.3")   // true
 * ```
 *
 * Comparison is plain SemVer precedence: `<2.0.0` admits `2.0.0-beta.1`, and a
 * shorthand is an exact version, not a wildcard (`>1.4` admits `1.4.1`, `<=1.4`
 * excludes `1.4.5`). A version that doesn't parse, and a null version, match
 * nothing. RubyGems requirements (`~>`, commas) are server-only and don't parse.
 */
public class VersionRange private constructor(
    /** The alternatives, each a list of comparators that must all hold. */
    public val alternatives: List<List<RangeComparator<Version>>>,
) {
    /** Whether [version] meets every comparator of at least one alternative. */
    public fun matches(version: Version?): Boolean =
        version != null && RangeSyntax.satisfied(alternatives, version)

    /** Whether [version] parses and meets the range. */
    public fun matches(version: String?): Boolean = matches(Version.parse(version))

    override fun equals(other: Any?): Boolean = other is VersionRange && other.alternatives == alternatives

    override fun hashCode(): Int = alternatives.hashCode()

    /** The canonical form: `>=1.4.0 <2.0.0 || =0.9.5`. */
    override fun toString(): String = RangeSyntax.format(alternatives)

    public companion object {
        /** The range for [text], or null when it isn't one. */
        public fun parse(text: String?): VersionRange? =
            RangeSyntax.parse(text) { Version.parse(it) }?.let(::VersionRange)
    }
}

/**
 * A range of client build numbers in the same syntax: `>=120 <200`,
 * `=7 || >=12`. A build number is 1 to 18 ASCII digits, so it fits a [Long];
 * leading zeros are allowed and ignored, signs are not.
 */
public class BuildRange private constructor(
    /** The alternatives, each a list of comparators that must all hold. */
    public val alternatives: List<List<RangeComparator<Long>>>,
) {
    /** Whether [build] meets every comparator of at least one alternative. */
    public fun matches(build: Long?): Boolean =
        build != null && build in 0 until LIMIT && RangeSyntax.satisfied(alternatives, build)

    /** Whether [build] is a build number and meets the range. */
    public fun matches(build: String?): Boolean = matches(number(build))

    override fun equals(other: Any?): Boolean = other is BuildRange && other.alternatives == alternatives

    override fun hashCode(): Int = alternatives.hashCode()

    override fun toString(): String = RangeSyntax.format(alternatives)

    public companion object {
        private const val LIMIT = 1_000_000_000_000_000_000L
        private val NUMBER = Regex("[0-9]{1,18}")

        /** The range for [text], or null when it isn't one. */
        public fun parse(text: String?): BuildRange? =
            RangeSyntax.parse(text) { number(it) }?.let(::BuildRange)

        /** The build number [text] holds (1 to 18 digits after trimming), or null. */
        public fun number(text: String?): Long? {
            val cleaned = Version.clean(text) ?: return null
            return if (NUMBER.matches(cleaned)) cleaned.toLong() else null
        }
    }
}
