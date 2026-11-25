package one.rarebit.flags.gradle;

import java.util.HashSet;
import java.util.Set;

/**
 * An RFC 8259 syntax check run before Groovy's {@code JsonSlurper}, which
 * accepts trailing commas and keeps the last of a repeated member name. The
 * server's JSON parser refuses both, so a manifest it can't read is refused
 * here too. Like the server's parser it also refuses more than
 * {@value #MAX_NESTING} nested containers (the top level counting as 1). The
 * plugin is written in Java, so this is a small port of the core module's
 * scanner rather than a dependency on it.
 */
final class StrictJsonText {
    static final int MAX_NESTING = 100;

    private final String s;
    private int i;

    private StrictJsonText(String s) {
        this.s = s;
    }

    /** Throws {@link IllegalArgumentException} unless {@code text} is one strict JSON value. */
    static void check(String text) {
        StrictJsonText scanner = new StrictJsonText(text);
        scanner.whitespace();
        scanner.value(1);
        scanner.whitespace();
        if (scanner.i != text.length()) throw scanner.fail("trailing characters");
    }

    private void value(int depth) {
        if (i >= s.length()) throw fail("a value is missing");
        char c = s.charAt(i);
        switch (c) {
            case '{': object(depth); break;
            case '[': array(depth); break;
            case '"': string(); break;
            case 't': literal("true"); break;
            case 'f': literal("false"); break;
            case 'n': literal("null"); break;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) number();
                else throw fail("unexpected character");
        }
    }

    private void object(int depth) {
        if (depth > MAX_NESTING) throw fail("more than " + MAX_NESTING + " nested containers");
        i++;
        whitespace();
        if (peek('}')) {
            i++;
            return;
        }
        Set<String> names = new HashSet<>();
        while (true) {
            whitespace();
            if (!peek('"')) throw fail("a member name is missing");
            String name = string();
            if (!names.add(name)) throw fail("the member name \"" + name + "\" is repeated");
            whitespace();
            expect(':');
            whitespace();
            value(depth + 1);
            whitespace();
            if (peek(',')) {
                i++;
            } else if (peek('}')) {
                i++;
                return;
            } else {
                throw fail("expected , or }");
            }
        }
    }

    private void array(int depth) {
        if (depth > MAX_NESTING) throw fail("more than " + MAX_NESTING + " nested containers");
        i++;
        whitespace();
        if (peek(']')) {
            i++;
            return;
        }
        while (true) {
            whitespace();
            value(depth + 1);
            whitespace();
            if (peek(',')) {
                i++;
            } else if (peek(']')) {
                i++;
                return;
            } else {
                throw fail("expected , or ]");
            }
        }
    }

    /** A string at {@code i} (on its opening quote), decoded so repeated names compare by value. */
    private String string() {
        StringBuilder out = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw fail("a raw control character inside a string");
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case '"': case '\\': case '/': out.append(e); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u': out.append((char) hex4()); break;
                default: throw fail("an invalid escape");
            }
        }
        throw fail("an unterminated string");
    }

    private int hex4() {
        if (i + 4 > s.length()) throw fail("a short \\u escape");
        int value = 0;
        for (int k = 0; k < 4; k++) {
            char c = s.charAt(i + k);
            int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) throw fail("a \\u escape that isn't four hex digits");
            value = value * 16 + digit;
        }
        i += 4;
        return value;
    }

    private void number() {
        if (peek('-')) i++;
        if (peek('0')) {
            i++;
        } else if (digits() == 0) {
            throw fail("a malformed number");
        }
        if (peek('.')) {
            i++;
            if (digits() == 0) throw fail("a malformed number");
        }
        if (peek('e') || peek('E')) {
            i++;
            if (peek('+') || peek('-')) i++;
            if (digits() == 0) throw fail("a malformed number");
        }
    }

    private int digits() {
        int start = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        return i - start;
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) throw fail("unexpected character");
        i += word.length();
    }

    private void whitespace() {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw fail("expected " + c);
        i++;
    }

    private IllegalArgumentException fail(String what) {
        return new IllegalArgumentException("not strict JSON: " + what + " at offset " + i);
    }
}
