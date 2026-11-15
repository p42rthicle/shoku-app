package app.shoku.data.ai

import app.shoku.data.net.BackendException
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.RequestOptions
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.RequestTimeoutException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.batches.BatchCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration

/**
 * Direct mode's calls to Anthropic, through the official Java SDK (com.anthropic:anthropic-java). The
 * requests are the ones ai.js sends: beta Messages with the server-side refusal fallback, effort low,
 * JSON-schema output, and the library in a cached system block; the units job goes through the Batch API.
 * The SDK ships its own R8 rules (META-INF/proguard), so minifying later needs nothing extra here.
 */
class AnthropicSdkApi(apiKey: String) : ClaudeApi, AutoCloseable {
    init { app.shoku.data.net.Network.ensureAllowed("Anthropic") }

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        // ai.js uses 25 s because a Catalyst function must answer within 30 s; the phone has no such limit,
        // so each call gets the read timeout the app already gave the backend (60 s, 90 s for plans).
        .timeout(Duration.ofSeconds(90))
        .maxRetries(1)
        .build()

    override suspend fun ask(req: AskRequest, timeoutMs: Int): ClaudeReply = mapErrors {
        val options = RequestOptions.builder().timeout(Duration.ofMillis(timeoutMs.toLong())).build()
        // Async so a cancelled screen (the overlay's Cancel) stops waiting at once.
        reply(client.async().beta().messages().create(betaParams(req), options).await())
    }

    override suspend fun submitBatch(requests: List<Pair<String, AskRequest>>): String = mapErrors {
        withContext(Dispatchers.IO) { client.messages().batches().create(batchParams(requests)).id() }
    }

    override suspend fun batchStatus(batchId: String): String = mapErrors {
        withContext(Dispatchers.IO) { client.messages().batches().retrieve(batchId).processingStatus().asString() }
    }

    override suspend fun batchResults(batchId: String): List<ClaudeReply?> = mapErrors {
        withContext(Dispatchers.IO) {
            client.messages().batches().resultsStreaming(batchId).use { stream ->
                // Iterated by hand: Stream.toList() only exists from Android 14.
                stream.stream().iterator().asSequence().map { r -> r.result().succeeded().map { reply(it.message()) }.orElse(null) }.toList()
            }
        }
    }

    override fun close() = client.close()

    /** ai.js's error messages, as BackendException so the screens' existing failure paths show them. */
    private suspend fun <T> mapErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: UnauthorizedException) {
        throw BackendException(401, "Your Claude API key was rejected. Check it in Settings.")
    } catch (e: RateLimitException) {
        throw BackendException(429, "AI is busy or out of credit, try again in a minute.")
    } catch (e: RequestTimeoutException) {
        throw BackendException(504, "AI took too long, try again.")
    } catch (e: AnthropicServiceException) {
        throw BackendException(502, "AI error ${e.statusCode()}: ${e.message}")
    } catch (e: AnthropicIoException) {
        if (e.cause is InterruptedIOException) throw BackendException(504, "AI took too long, try again.")
        throw IOException(e.message, e)
    }

    companion object {
        /** The beta Messages request ai.js's ask() builds. */
        fun betaParams(req: AskRequest): MessageCreateParams = MessageCreateParams.builder()
            .model(req.model)
            .maxTokens(req.maxTokens)
            .apply {
                // If the main model declines, the API retries on a fallback model inside the same call.
                if (req.fallbacks) addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01).fallbacksDefault()
            }
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.LOW)
                    .format(BetaJsonOutputFormat.builder().schema(betaSchema(req.schema)).build())
                    .build(),
            )
            .systemOfBetaTextBlockParams(
                req.system.map { s ->
                    BetaTextBlockParam.builder().text(s.text)
                        .apply { if (s.cache) cacheControl(BetaCacheControlEphemeral.builder().build()) }
                        .build()
                },
            )
            .addUserMessageOfContentBlockParams(
                req.content.map { b ->
                    when (b) {
                        is UserBlock.Text -> BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(b.text).build())
                        is UserBlock.Image -> BetaContentBlockParam.ofImage(
                            BetaImageBlockParam.builder().source(
                                BetaBase64ImageSource.builder().data(b.base64).mediaType(BetaBase64ImageSource.MediaType.of(b.mediaType)).build(),
                            ).build(),
                        )
                    }
                },
            )
            .build()

        /** The units batch (ai.js submitUnits): plain Messages, no fallback, the food lines as the user turn. */
        fun batchParams(requests: List<Pair<String, AskRequest>>): BatchCreateParams = BatchCreateParams.builder().apply {
            requests.forEach { (customId, req) ->
                addRequest(
                    BatchCreateParams.Request.builder().customId(customId).params(
                        BatchCreateParams.Request.Params.builder()
                            .model(req.model)
                            .maxTokens(req.maxTokens)
                            .outputConfig(
                                OutputConfig.builder()
                                    .effort(OutputConfig.Effort.LOW)
                                    .format(JsonOutputFormat.builder().schema(schema(req.schema)).build())
                                    .build(),
                            )
                            .systemOfTextBlockParams(req.system.map { TextBlockParam.builder().text(it.text).build() })
                            .addUserMessage(req.content.filterIsInstance<UserBlock.Text>().joinToString("\n") { it.text })
                            .build(),
                    ).build(),
                )
            }
        }.build()

        private fun betaSchema(o: JsonObject) = BetaJsonOutputFormat.Schema.builder()
            .apply { o.forEach { (k, v) -> putAdditionalProperty(k, sdkJson(v)) } }.build()

        private fun schema(o: JsonObject) = JsonOutputFormat.Schema.builder()
            .apply { o.forEach { (k, v) -> putAdditionalProperty(k, sdkJson(v)) } }.build()

        private fun sdkJson(e: JsonElement): JsonValue = JsonValue.from(plain(e))

        private fun plain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> e.mapValues { plain(it.value) }
            is JsonArray -> e.map { plain(it) }
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
        }

        private fun reply(m: BetaMessage) = ClaudeReply(
            text = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""),
            stopReason = m.stopReason().map { it.asString() }.orElse(null),
            model = m.model().asString(),
            usage = m.usage().let { TokenUsage(it.inputTokens(), it.outputTokens(), it.cacheReadInputTokens().orElse(0L), it.cacheCreationInputTokens().orElse(0L)) },
        )

        private fun reply(m: Message) = ClaudeReply(
            text = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""),
            stopReason = m.stopReason().map { it.asString() }.orElse(null),
            model = m.model().asString(),
            usage = m.usage().let { TokenUsage(it.inputTokens(), it.outputTokens(), it.cacheReadInputTokens().orElse(0L), it.cacheCreationInputTokens().orElse(0L)) },
        )
    }
}
