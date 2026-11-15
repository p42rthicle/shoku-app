package app.shoku.data.ai

import app.shoku.data.net.BackendException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One Claude answer, reduced to what the routes need. */
data class ClaudeReply(val text: String, val stopReason: String?, val model: String, val usage: TokenUsage)

/** The calls direct mode makes to Anthropic; [AnthropicSdkApi] is the real one, tests use a fake. */
interface ClaudeApi {
    suspend fun ask(req: AskRequest, timeoutMs: Int): ClaudeReply
    /** Starts a Message Batch; returns its id. */
    suspend fun submitBatch(requests: List<Pair<String, AskRequest>>): String
    /** in_progress, canceling or ended. */
    suspend fun batchStatus(batchId: String): String
    /** One entry per request; null where the request didn't succeed. */
    suspend fun batchResults(batchId: String): List<ClaudeReply?>
}

/**
 * Answers the backend's AI routes on the phone: the same routes, request bodies and answers as
 * backend/functions/api/index.js, with Claude called by [api] and the spending kept in [ledger].
 * With [api] null (no key and no backend) usage and the cap still work and the AI routes throw
 * [AI_NOT_SET_UP].
 */
class DirectTransport(val api: ClaudeApi?, private val ledger: UsageLedger, override val id: String = "direct") : AiTransport {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun get(path: String, readTimeoutMs: Int): String {
        val route = path.substringBefore('?')
        val query = path.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        return when (route) {
            "/ai/usage" -> json.encodeToString(AiUsage.serializer(), ledger.usage())
            "/ai/units/result" -> unitsResult(query["id"])
            else -> throw BackendException(404, "no route for GET $route")
        }
    }

    override suspend fun post(path: String, body: String, readTimeoutMs: Int): String {
        val b = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw BackendException(400, "Body is not valid JSON") }
        return when (path) {
            in DirectRoutes.ASK_ROUTES -> {
                val api = api ?: throw aiNotSetUp()
                ledger.checkCap()
                ask(api, DirectRoutes.build(path, b), readTimeoutMs)
            }
            "/ai/units/submit" -> {
                val api = api ?: throw aiNotSetUp()
                ledger.checkCap()
                val requests = DirectRoutes.unitsRequests(b)
                val id = api.submitBatch(requests)
                buildJsonObject { put("batchId", id); put("requests", requests.size) }.toString()
            }
            "/ai/cap" -> {
                val usd = b["usd"]?.jsonPrimitive?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() } ?: Double.NaN
                buildJsonObject { put("capUsd", ledger.setCap(usd)) }.toString()
            }
            else -> throw BackendException(404, "no route for POST $path")
        }
    }

    /** ai.js ask(): one structured call, then the stop reason and the JSON checked. */
    private suspend fun ask(api: ClaudeApi, req: AskRequest, timeoutMs: Int): String {
        val res = api.ask(req, timeoutMs)
        val usd = costOf(res.model, res.usage)
        // Anthropic bills every answer, even one we can't use, so it counts against the cap here.
        ledger.add(usd)
        if (res.stopReason == "refusal") throw BackendException(422, "The AI declined this one.")
        if (res.stopReason == "max_tokens") throw BackendException(502, "The AI answer was cut off.")
        val data = runCatching { json.parseToJsonElement(res.text) }.getOrElse { throw BackendException(502, "The AI answer was not valid JSON.") }
        return buildJsonObject {
            put("data", data)
            putJsonObject("usage") {
                put("input", res.usage.input); put("output", res.usage.output)
                put("cacheRead", res.usage.cacheRead); put("cacheWrite", res.usage.cacheWrite)
                put("model", res.model); put("usd", usd)
            }
        }.toString()
    }

    /** A units batch: { status } while running, then { status: 'ended', foods, failed, usd }. */
    private suspend fun unitsResult(batchId: String?): String {
        if (batchId.isNullOrEmpty() || !Regex("^[A-Za-z0-9_-]+$").matches(batchId)) throw BackendException(400, "id is required")
        val api = api ?: throw aiNotSetUp()
        val status = api.batchStatus(batchId)
        if (status != "ended") return buildJsonObject { put("status", status) }.toString()
        var usd = 0.0
        var failed = 0
        val foods = buildJsonArray {
            for (m in api.batchResults(batchId)) {
                if (m == null) { failed++; continue }
                usd += costOf(m.model, m.usage, batch = true)
                val list = runCatching { json.parseToJsonElement(m.text).jsonObject["foods"] as JsonArray }.getOrNull()
                if (list == null) failed++ else list.forEach { add(it) }
            }
        }
        ledger.countBatchOnce(batchId, usd)
        return buildJsonObject { put("status", "ended"); put("foods", foods); put("failed", failed); put("usd", usd) }.toString()
    }
}
