package one.rarebit.flags.core

/**
 * An RFC 8259 syntax check, run before kotlinx.serialization builds the tree.
 * kotlinx's tree reader keeps any unquoted token as a literal (`tru`, `True`,
 * `NaN`, `'x'`, `01`), so it can't be trusted to say what isn't JSON. This
 * scanner can: it accepts exactly the JSON grammar (one value, surrounded by
 * optional whitespace), with no extensions.
 *
 * It also refuses raw control characters inside strings and nesting deeper
 * than [MAX_DEPTH] containers, as the server's own JSON parser does (its
 * `max_nesting` is 100), so its recursion is bounded too.
 */
internal object StrictJson {
    const val MAX_DEPTH: Int = 100

    fun isValid(text: String): Boolean = Scanner(text).document()

    private class Scanner(private val s: String) {
        private var i = 0

        // Containers open at each depth: '{' or '['.
        private val stack = CharArray(MAX_DEPTH)
        private var depth = 0

        fun document(): Boolean {
            skipWhitespace()
            if (!value()) return false
            skipWhitespace()
            return i == s.length
        }

        /** One value; containers are walked with the explicit stack. */
        private fun value(): Boolean {
            if (!scalarOrOpen()) return false
            while (depth > 0) {
                skipWhitespace()
                if (i >= s.length) return false
                val open = stack[depth - 1]
                val c = s[i]
                if ((open == '{' && c == '}') || (open == '[' && c == ']')) {
                    i++
                    depth--
                    continue
                }
                if (c != ',') return false
                i++
                skipWhitespace()
                if (!member(open)) return false
            }
            return true
        }

        /** A member after `{` or `,`: `"key": value` in an object, a value in an array. */
        private fun member(open: Char): Boolean {
            if (open == '{') {
                if (!string()) return false
                skipWhitespace()
                if (i >= s.length || s[i] != ':') return false
                i++
                skipWhitespace()
            }
            return scalarOrOpen()
        }

        /** A scalar, or an opening bracket (pushed), including an empty container. */
        private fun scalarOrOpen(): Boolean {
            if (i >= s.length) return false
            return when (val c = s[i]) {
                '{', '[' -> {
                    if (depth == MAX_DEPTH) return false
                    i++
                    skipWhitespace()
                    val close = if (c == '{') '}' else ']'
                    if (i < s.length && s[i] == close) {
                        i++
                        true
                    } else {
                        stack[depth++] = c
                        member(c)
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        private fun literal(word: String): Boolean {
            if (!s.startsWith(word, i)) return false
            i += word.length
            return true
        }

        private fun number(): Boolean {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) return false
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                return false
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (!digits()) return false
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (!digits()) return false
            }
            return i > start
        }

        private fun digits(): Boolean {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            return i > start
        }

        private fun string(): Boolean {
            if (i >= s.length || s[i] != '"') return false
            i++
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return true
                    c == '\\' -> {
                        if (i >= s.length) return false
                        when (s[i++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {}
                            'u' -> {
                                if (i + 4 > s.length) return false
                                for (k in 0 until 4) if (!s[i + k].isHex()) return false
                                i += 4
                            }
                            else -> return false
                        }
                    }
                    c.code < 0x20 -> return false
                }
            }
            return false
        }

        private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }
    }
}
