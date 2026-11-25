package one.rarebit.flags.ofrep

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable

/**
 * Reads at most [maxBytes] of a streaming response body. Returns null, having
 * read nothing, when `Content-Length` declares more, and null as soon as the
 * stream passes [maxBytes] without one, so an oversized body never sits in
 * memory. Use it inside `prepare*().execute { }`, where Ktor streams the body
 * instead of saving it.
 */
public suspend fun HttpResponse.readBounded(maxBytes: Int): ByteArray? {
    val declared = contentLength()
    if (declared != null && declared > maxBytes) return null
    val channel = bodyAsChannel()
    val out = ByteArray(maxBytes + 1)
    var size = 0
    while (size <= maxBytes) {
        val read = channel.readAvailable(out, size, out.size - size)
        if (read == -1) break
        size += read
    }
    if (size > maxBytes) return null
    return out.copyOf(size)
}
