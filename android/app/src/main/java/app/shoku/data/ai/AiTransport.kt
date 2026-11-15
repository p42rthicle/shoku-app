package app.shoku.data.ai

import app.shoku.data.net.Backend
import app.shoku.data.net.BackendException
import app.shoku.data.net.Network

/**
 * Where the AI routes ("/ai/meal", "/ai/usage", ...) are answered. Both sides speak the backend's wire
 * format (the same request bodies and the same `{ data, usage }` answers), so [AiClient], [PlanAi] and
 * [UnitsFiller] don't care which one they talk to.
 */
interface AiTransport {
    /** Changes when the place answering changes (backend, or a different API key), so pending batches can be dropped. */
    val id: String
    suspend fun get(path: String, readTimeoutMs: Int = 40_000): String
    suspend fun post(path: String, body: String, readTimeoutMs: Int = 40_000): String
}

/** A self-hosted backend (backend/, see docs/AI.md). */
object BackendTransport : AiTransport {
    override val id = "backend"
    override suspend fun get(path: String, readTimeoutMs: Int) = Backend.get(path, readTimeoutMs)
    override suspend fun post(path: String, body: String, readTimeoutMs: Int) = Backend.post(path, body, readTimeoutMs)
}

enum class AiMode { DIRECT, BACKEND, NONE }

const val AI_NOT_SET_UP = "Add your Claude API key in Settings"

/** Thrown in place of an AI answer when there is neither a key nor a backend; screens show the message. */
fun aiNotSetUp() = BackendException(0, AI_NOT_SET_UP)

object AiTransports {
    /** A key entered in the app wins; otherwise the backend if this build has one; otherwise nothing. */
    fun mode(apiKey: String?, backendConfigured: Boolean): AiMode = when {
        !apiKey.isNullOrBlank() -> AiMode.DIRECT
        backendConfigured -> AiMode.BACKEND
        else -> AiMode.NONE
    }

    fun mode(): AiMode = if (Network.blocked) AiMode.NONE else mode(AiSettings.apiKey(), Backend.configured)

    private var direct: Pair<String, DirectTransport>? = null

    /**
     * The transport for this call, picked fresh each time so a key saved in Settings takes effect at once.
     * With neither a key nor a backend, the AI routes throw [AI_NOT_SET_UP]; usage and the cap still work
     * (on the phone), so the Settings card shows $0 of $30 instead of an error.
     */
    @Synchronized
    fun current(): AiTransport {
        // Tests (see Network): no key, no backend, no client. Usage still answers from the phone's ledger.
        if (Network.blocked) return DirectTransport(null, AiSettings.ledger, "none")
        val key = AiSettings.apiKey()
        return when (mode(key, Backend.configured)) {
            AiMode.BACKEND -> BackendTransport
            AiMode.NONE -> DirectTransport(null, AiSettings.ledger, "none")
            AiMode.DIRECT -> {
                val k = key!!
                direct?.takeIf { it.first == k }?.second ?: run {
                    (direct?.second?.api as? AnthropicSdkApi)?.close()
                    DirectTransport(AnthropicSdkApi(k), AiSettings.ledger, "direct:${k.hashCode()}").also { direct = k to it }
                }
            }
        }
    }
}
