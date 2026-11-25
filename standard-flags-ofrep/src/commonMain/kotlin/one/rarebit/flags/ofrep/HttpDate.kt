package one.rarebit.flags.ofrep

/**
 * Parses an HTTP-date (RFC 9110 §5.6.7) to epoch milliseconds: the preferred
 * IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`) and the two obsolete forms a
 * recipient must also accept, RFC 850 (`Sunday, 06-Nov-94 08:49:37 GMT`) and
 * asctime (`Sun Nov  6 08:49:37 1994`). Null when it is none of them.
 */
internal object HttpDate {
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private const val TIME = "([0-9]{2}):([0-9]{2}):([0-9]{2})"
    private val IMF = Regex("[A-Z][a-z]{2}, ([0-9]{2}) ([A-Z][a-z]{2}) ([0-9]{4}) $TIME GMT")
    private val RFC850 = Regex("[A-Z][a-z]+, ([0-9]{2})-([A-Z][a-z]{2})-([0-9]{2}) $TIME GMT")
    private val ASCTIME = Regex("[A-Z][a-z]{2} ([A-Z][a-z]{2}) ([ 0-9][0-9]) $TIME ([0-9]{4})")

    fun parse(text: String, nowYear: Int): Long? {
        IMF.matchEntire(text)?.groupValues?.let { g ->
            return epochMillis(g[3].toInt(), g[2], g[1].trim().toInt(), g[4], g[5], g[6])
        }
        RFC850.matchEntire(text)?.groupValues?.let { g ->
            // RFC 9110: a two-digit year more than 50 years in the future means the past century.
            var year = nowYear / 100 * 100 + g[3].toInt()
            if (year > nowYear + 50) year -= 100
            return epochMillis(year, g[2], g[1].toInt(), g[4], g[5], g[6])
        }
        ASCTIME.matchEntire(text)?.groupValues?.let { g ->
            return epochMillis(g[6].toInt(), g[1], g[2].trim().toInt(), g[3], g[4], g[5])
        }
        return null
    }

    private fun epochMillis(year: Int, monthName: String, day: Int, h: String, m: String, s: String): Long? {
        val month = MONTHS.indexOf(monthName) + 1
        val hour = h.toInt()
        val minute = m.toInt()
        val second = s.toInt()
        if (month == 0 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        return (daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second) * 1000L
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
