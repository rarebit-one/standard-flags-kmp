package one.rarebit.flags.core

/**
 * A client version: SemVer 2.0 (`MAJOR.MINOR.PATCH`, an optional `-prerelease`
 * and an ignored `+build`), plus the shorthands `MAJOR.MINOR` and `MAJOR`, whose
 * missing parts are 0. A shorthand takes no prerelease or build suffix.
 *
 * ```
 * Version.parse("1.4.0-beta.2")!! < Version.parse("1.4")!!  // true
 * Version.parse("v1.4")                                      // null
 * ```
 *
 * Surrounding ASCII spaces and tabs are ignored; anything else outside the
 * grammar (other whitespace, non-ASCII characters, more than 128 characters)
 * doesn't parse. Ordering is SemVer §11 precedence and nothing more: a
 * prerelease sorts before its release, numeric identifiers compare by value
 * and below alphanumeric ones, and build metadata is ignored, so
 * `1.4.0+381 == 1.4.0`. Numbers have no size limit.
 *
 * This matches the server's `StandardFlags::Version`, and the shared fixture
 * `versions/version_ranges.json` pins it.
 */
public class Version private constructor(
    private val numbers: List<String>,
    /** The prerelease identifiers, empty for a release. */
    public val prerelease: List<String>,
    /** The build metadata, which never affects ordering. */
    public val build: String?,
) : Comparable<Version> {

    /** Whether this version has a prerelease part. */
    public val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: Version): Int {
        for (i in 0..2) {
            val order = compareNumbers(numbers[i], other.numbers[i])
            if (order != 0) return order
        }
        return comparePrerelease(prerelease, other.prerelease)
    }

    override fun equals(other: Any?): Boolean = other is Version && compareTo(other) == 0

    override fun hashCode(): Int = numbers.hashCode() * 31 + prerelease.hashCode()

    /** The full form without build metadata: `1.4.0`, `1.4.0-beta.2`. */
    override fun toString(): String {
        val release = numbers.joinToString(".")
        return if (isPrerelease) "$release-${prerelease.joinToString(".")}" else release
    }

    public companion object {
        /** The longest text [parse] reads, after trimming. */
        public const val MAX_LENGTH: Int = 128

        private const val NUMBER = "0|[1-9][0-9]*"
        private const val IDENTIFIER = "0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*"
        private val PATTERN = Regex(
            "($NUMBER)(?:\\.($NUMBER)(?:\\.($NUMBER)" +
                "(?:-((?:$IDENTIFIER)(?:\\.(?:$IDENTIFIER))*))?" +
                "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                ")?)?",
        )

        /** The version for [text], or null when it isn't one. */
        public fun parse(text: String?): Version? {
            val cleaned = clean(text) ?: return null
            if (cleaned.length > MAX_LENGTH) return null
            val match = PATTERN.matchEntire(cleaned) ?: return null
            val groups = match.groupValues
            val numbers = listOf(groups[1], groups[2].ifEmpty { "0" }, groups[3].ifEmpty { "0" })
            val prerelease = if (groups[4].isEmpty()) emptyList() else groups[4].split('.')
            return Version(numbers, prerelease, groups[5].ifEmpty { null })
        }

        /**
         * [text] without surrounding ASCII spaces and tabs, or null when it is
         * null or holds anything but ASCII.
         */
        internal fun clean(text: String?): String? {
            if (text == null) return null
            if (text.any { it.code > 0x7f }) return null
            var first = 0
            var last = text.length
            while (first < last && text[first].isBlank()) first++
            while (last > first && text[last - 1].isBlank()) last--
            return text.substring(first, last)
        }

        private fun Char.isBlank(): Boolean = this == ' ' || this == '\t'

        /** Compares two digit strings without leading zeros by value. */
        internal fun compareNumbers(a: String, b: String): Int =
            if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b).coerceIn(-1, 1)

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            if (a.isEmpty() && b.isEmpty()) return 0
            if (a.isEmpty()) return 1
            if (b.isEmpty()) return -1
            for (i in 0 until minOf(a.size, b.size)) {
                val order = compareIdentifier(a[i], b[i])
                if (order != 0) return order
            }
            return a.size.compareTo(b.size)
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val aNumeric = a.all { it in '0'..'9' }
            val bNumeric = b.all { it in '0'..'9' }
            return when {
                aNumeric && bNumeric -> compareNumbers(a, b)
                aNumeric -> -1
                bNumeric -> 1
                else -> a.compareTo(b).coerceIn(-1, 1)
            }
        }
    }
}
