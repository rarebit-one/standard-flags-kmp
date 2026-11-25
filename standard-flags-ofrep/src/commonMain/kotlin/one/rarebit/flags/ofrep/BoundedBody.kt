package one.rarebit.flags.ofrep

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable

/**
 * Reads at most [maxBytes] of a streaming response body. Returns null, having
 * read nothing, when `Content-Length` declares more, and null as soon as the
 * stream passes [maxBytes] without one, so an oversized body never sits in
 * memory. The buffer starts small (or at the declared length) and grows as
 * the body arrives. Use it inside `prepare*().execute { }`, where Ktor streams
 * the body instead of saving it.
 */
public suspend fun HttpResponse.readBounded(maxBytes: Int): ByteArray? {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val declared = contentLength()
    if (declared != null && declared > maxBytes) return null
    // One byte past the limit proves a body is too large; Int.MAX_VALUE can't go past.
    val ceiling = if (maxBytes == Int.MAX_VALUE) Int.MAX_VALUE else maxBytes + 1
    val channel = bodyAsChannel()
    var out = ByteArray(minOf(ceiling, declared?.toInt()?.plus(1) ?: INITIAL_BUFFER).coerceAtLeast(1))
    var size = 0
    while (size < ceiling) {
        if (size == out.size) out = out.copyOf(minOf(ceiling.toLong(), out.size.toLong() * 2).toInt())
        val read = channel.readAvailable(out, size, out.size - size)
        if (read == -1) break
        size += read
    }
    if (size > maxBytes) return null
    return if (size == out.size) out else out.copyOf(size)
}

private const val INITIAL_BUFFER = 8 * 1024
