package one.rarebit.flags.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The server's ETag for a bulk response: the SHA-256, in lowercase hex, of the
 * RFC 8785 (JCS) canonical JSON of `{"audience": audience, "flags": entries}`,
 * as UTF-8. It is also the response's `metadata.version`. Clients don't need
 * it to work; it lets a test or a debug screen check what the server sent.
 */
public object Etag {
    public fun of(audience: String, entries: JsonArray): String {
        val document = JsonObject(mapOf("audience" to JsonPrimitive(audience), "flags" to entries))
        return Sha256.hex(Jcs.canonicalize(document).encodeToByteArray())
    }
}

/**
 * RFC 8785 JSON canonicalization for the values standard_flags puts on the
 * wire: objects with keys sorted by UTF-16 code units, no whitespace, strings
 * raw UTF-8 with only the escapes JCS requires. Numbers never occur on this
 * wire, so only integers that a double holds exactly are written; other numbers
 * are refused rather than written in a form another serializer might not match.
 */
public object Jcs {
    public fun canonicalize(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    string(key, out)
                    out.append(':')
                    write(element.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                element.isString -> string(element.content, out)
                element.content == "true" || element.content == "false" -> out.append(element.content)
                element.content == "-0" -> out.append('0')
                SAFE_INTEGER.matches(element.content) -> out.append(element.content)
                else -> throw IllegalArgumentException("JCS: unsupported number ${element.content}")
            }
        }
    }

    private val SAFE_INTEGER = Regex("-?(0|[1-9][0-9]{0,14})")

    private fun string(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xf])
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private const val HEX = "0123456789abcdef"
}

/** SHA-256 (FIPS 180-4) in common code, so the core module needs no crypto dependency. */
public object Sha256 {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    public fun digest(input: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )
        val bitLength = input.size.toLong() * 8
        val padded = ByteArray(((input.size + 9 + 63) / 64) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()

        val w = IntArray(64)
        for (block in padded.indices step 64) {
            for (t in 0 until 16) {
                val o = block + t * 4
                w[t] = (padded[o].toInt() and 0xff shl 24) or (padded[o + 1].toInt() and 0xff shl 16) or
                    (padded[o + 2].toInt() and 0xff shl 8) or (padded[o + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (h[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    public fun hex(input: ByteArray): String =
        digest(input).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
