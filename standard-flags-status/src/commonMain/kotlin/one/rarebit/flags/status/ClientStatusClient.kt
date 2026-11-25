package one.rarebit.flags.status

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.readByteArray
import one.rarebit.flags.ofrep.ClientInfo

/**
 * The upgrade status of this build as a [StateFlow], for a nudging or
 * blocking screen.
 *
 * ```
 * val status = ClientStatusClient(http, "https://api.example.com/client/status", ClientInfo("android", "0.5.0", 381))
 * scope.launch { status.check() }     // at launch and on foreground
 * status.status.collect { when (it) { is ClientStatus.UpgradeRequired -> showBlocker(it.details.storeUrl) … } }
 * ```
 *
 * A failed check keeps the last known status (initially [ClientStatus.Unknown]),
 * so a network error never blocks the app. Install [UpgradeRequiredDetector]
 * on the app's API client to flip to [ClientStatus.UpgradeRequired] as soon as
 * any call answers 426.
 */
public class ClientStatusClient(
    private val http: HttpClient,
    private val url: String,
    private val client: ClientInfo?,
    initial: ClientStatus = ClientStatus.Initial,
) {
    private val state = MutableStateFlow(initial)

    /** The latest known status. */
    public val status: StateFlow<ClientStatus> = state.asStateFlow()

    /** Asks the server. Returns the new status, or null when the check failed (the old one stays). */
    public suspend fun check(): ClientStatus? {
        val parsed = try {
            val response = http.get(url) {
                expectSuccess = false
                client?.applyTo(headers)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
            val body = response.bodyAsChannel().readBuffer(MAX_BYTES + 1).readByteArray()
            when {
                body.size > MAX_BYTES -> null
                response.status == HttpStatusCode.OK -> ClientStatusParser.parse(body.decodeToString())
                response.status.value == 426 -> ClientStatusParser.parseUpgradeRequired(body.decodeToString())
                else -> null
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        if (parsed != null) state.value = parsed
        return parsed
    }

    /** Records an upgrade-required answer seen elsewhere (a 426 from an API call). */
    public fun reportUpgradeRequired(status: ClientStatus.UpgradeRequired) {
        // An upgrade-required answer from /client/status already says so, with more detail.
        if (state.value !is ClientStatus.UpgradeRequired) state.value = status
    }

    private companion object {
        const val MAX_BYTES = 16 * 1024L
    }
}

/** Configuration for [UpgradeRequiredDetector]. */
public class UpgradeRequiredDetectorConfig {
    /** Where a 426 `client_upgrade_required` answer is reported. */
    public var status: ClientStatusClient? = null
}

/**
 * A Ktor client plugin that watches every response for 426 with the
 * `client_upgrade_required` error and reports it to a [ClientStatusClient]:
 *
 * ```
 * HttpClient { install(UpgradeRequiredDetector) { status = clientStatus } }
 * ```
 *
 * It reads the body of 426 responses only (Ktor 3 keeps response bodies, so
 * the app can still read it too).
 */
public val UpgradeRequiredDetector: ClientPlugin<UpgradeRequiredDetectorConfig> =
    createClientPlugin("StandardFlagsUpgradeRequiredDetector", ::UpgradeRequiredDetectorConfig) {
        val target = pluginConfig.status
        onResponse { response ->
            if (response.status.value != 426 || target == null) return@onResponse
            val status = try {
                ClientStatusParser.parseUpgradeRequired(response.bodyAsText())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            // Only the server's own error counts: a 426 from a proxy says nothing about this build.
            if (status != null) target.reportUpgradeRequired(status)
        }
    }
