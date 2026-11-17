package app.shoku

import app.shoku.data.ai.AI_NOT_SET_UP
import app.shoku.data.ai.AiMode
import app.shoku.data.ai.AiTransports
import app.shoku.data.ai.AnthropicSdkApi
import app.shoku.data.ai.AskRequest
import app.shoku.data.ai.ClaudeApi
import app.shoku.data.ai.ClaudeReply
import app.shoku.data.ai.DirectTransport
import app.shoku.data.ai.LABEL_TASK
import app.shoku.data.ai.PRODUCT_TASK
import app.shoku.data.ai.BILL_TASK
import app.shoku.data.ai.HINTS_INTRO
import app.shoku.data.ai.REFS_INTRO
import app.shoku.data.ai.MEAL_TASK_TEXT
import app.shoku.data.ai.MemoryStore
import app.shoku.data.ai.PHOTO_MODEL
import app.shoku.data.ai.PLAN_TASK
import app.shoku.data.ai.PLATE_TASK
import app.shoku.data.ai.RECIPE_TASK
import app.shoku.data.ai.RULES
import app.shoku.data.ai.TEXT_MODEL
import app.shoku.data.ai.TokenUsage
import app.shoku.data.ai.UNITS_TASK
import app.shoku.data.ai.UsageLedger
import app.shoku.data.ai.UserBlock
import app.shoku.data.ai.costOf
import app.shoku.data.ai.mealTask
import app.shoku.data.net.BackendException
import com.anthropic.models.beta.AnthropicBeta
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Direct mode without the network: which transport is picked, what is sent, and what it costs. */
class AiTransportTest {

    /** Records requests and answers with canned replies. */
    private class FakeClaude(private val answer: String = """{"items":[],"meal":"Lunch"}""") : ClaudeApi {
        val asked = mutableListOf<AskRequest>()
        var batch: List<Pair<String, AskRequest>> = emptyList()
        override suspend fun ask(req: AskRequest, timeoutMs: Int): ClaudeReply {
            asked += req
            return ClaudeReply(answer, "end_turn", req.model, TokenUsage(input = 1_000, output = 500, cacheRead = 10_000))
        }
        override suspend fun submitBatch(requests: List<Pair<String, AskRequest>>): String { batch = requests; return "msgbatch_1" }
        override suspend fun batchStatus(batchId: String) = "ended"
        override suspend fun batchResults(batchId: String) = listOf(
            ClaudeReply("""{"foods":[{"k":"a","grams_per_unit":null,"portions":[{"label":"tbsp","amount":15}]}]}""", "end_turn", TEXT_MODEL, TokenUsage(input = 2_000, output = 1_000)),
            null,
        )
    }

    private fun failure(block: suspend () -> Unit): BackendException =
        runCatching { runBlocking { block() } }.exceptionOrNull() as? BackendException ?: throw AssertionError("expected a BackendException")

    @Test
    fun aKeyWinsThenTheBackendThenNothing() {
        assertEquals(AiMode.DIRECT, AiTransports.mode("sk-ant-test", backendConfigured = true))
        assertEquals(AiMode.DIRECT, AiTransports.mode("sk-ant-test", backendConfigured = false))
        assertEquals(AiMode.BACKEND, AiTransports.mode(null, backendConfigured = true))
        assertEquals(AiMode.BACKEND, AiTransports.mode("  ", backendConfigured = true))
        assertEquals(AiMode.NONE, AiTransports.mode(null, backendConfigured = false))
    }

    @Test
    fun withNeitherAiAsksForAKeyButUsageStillWorks() = runBlocking {
        val t = DirectTransport(null, UsageLedger(MemoryStore()))
        val e = failure { t.post("/ai/meal", """{"text":"2 eggs","library":[]}""") }
        assertEquals(AI_NOT_SET_UP, e.message)
        val usage = Json.parseToJsonElement(t.get("/ai/usage")).jsonObject
        assertEquals(30.0, usage["capUsd"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(0, usage["calls"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun pricesMatchTheBackendTable() {
        val u = TokenUsage(input = 1_000_000, output = 1_000_000, cacheRead = 1_000_000, cacheWrite = 1_000_000)
        assertEquals(2 + 10 + 0.1 + 2.5, costOf("claude-sonnet-5-5", u), 1e-9)
        assertEquals(4 + 20 + 0.2 + 5.0, costOf("claude-opus-5-5", u), 1e-9)
        assertEquals((4 + 20 + 0.2 + 5.0) / 2, costOf("claude-opus-5-5", u, batch = true), 1e-9)
        // Unknown models are priced as Opus.
        assertEquals(costOf("claude-opus-5-5", u), costOf("something-new", u), 1e-9)
    }

    @Test
    fun theLedgerCountsMonthsAndEnforcesTheCap() {
        var month = "2026-11"
        val ledger = UsageLedger(MemoryStore()) { month }
        ledger.add(0.25); ledger.add(0.5)
        assertEquals(0.75, ledger.usage().usd, 1e-9)
        assertEquals(2, ledger.usage().calls)
        ledger.countBatchOnce("b1", 1.0); ledger.countBatchOnce("b1", 1.0)
        assertEquals(1.75, ledger.usage().usd, 1e-9)

        assertEquals(400, failure { ledger.setCap(500.0) }.status)
        ledger.setCap(1.0)
        val e = failure { ledger.checkCap() }
        assertEquals(402, e.status)
        assertEquals("This month's AI limit of \$1 is used up (\$1.75). Raise it in Settings or wait for next month.", e.message)

        month = "2026-12"
        assertEquals(0.0, ledger.usage().usd, 0.0)
        ledger.checkCap()
    }

    @Test
    fun aMealGoesOutLikeTheBackendsAndIsPriced() = runBlocking {
        val fake = FakeClaude()
        val ledger = UsageLedger(MemoryStore())
        val t = DirectTransport(fake, ledger)
        val out = Json.parseToJsonElement(
            t.post("/ai/meal", """{"text":"${"x".repeat(2500)}","library":[{"k":"f0","name":"Egg","units":["pc","g"],"aka":"anda"}]}"""),
        ).jsonObject
        assertEquals("Lunch", out["data"]!!.jsonObject["meal"]!!.jsonPrimitive.content)

        val req = fake.asked.single()
        assertEquals(TEXT_MODEL, req.model)
        assertEquals(4000L, req.maxTokens)
        assertTrue(req.fallbacks)
        assertEquals(RULES, req.system[0].text)
        assertFalse(req.system[0].cache)
        assertEquals("Their food library:\nf0 | Egg | pc, g | anda", req.system[1].text)
        assertTrue(req.system[1].cache)
        assertEquals(UserBlock.Text(MEAL_TASK_TEXT), req.content[0])
        assertEquals(2000, (req.content[1] as UserBlock.Text).text.length)

        val usd = (1_000 * 2.0 + 500 * 10.0 + 10_000 * 0.1) / 1e6
        assertEquals(usd, ledger.usage().usd, 1e-12)
        assertEquals(1, ledger.usage().calls)
    }

    @Test
    fun photosUseOpusAndTheCapStopsCallsBeforeTheyAreMade() = runBlocking {
        val fake = FakeClaude()
        val ledger = UsageLedger(MemoryStore())
        val t = DirectTransport(fake, ledger)
        t.post("/ai/plate", """{"images":["AAA","BBB"],"mediaType":"image/jpeg","text":"with raita","library":[]}""")
        val req = fake.asked.single()
        assertEquals(PHOTO_MODEL, req.model)
        assertEquals(2, req.content.count { it is UserBlock.Image })
        assertTrue((req.content[0] as UserBlock.Text).text.endsWith("count each food once.\nThey add: with raita"))

        ledger.setCap(1.0); ledger.add(5.0)
        assertEquals(402, failure { t.post("/ai/meal", """{"text":"toast"}""") }.status)
        assertEquals(1, fake.asked.size)
    }

    @Test
    fun theUnitsBatchIsSubmittedInChunksAndCountedOnceAtHalfPrice() = runBlocking {
        val fake = FakeClaude()
        val ledger = UsageLedger(MemoryStore())
        val t = DirectTransport(fake, ledger)
        val foods = (1..45).joinToString(",") { """{"k":"id$it","name":"Food $it","unit":"g"}""" }
        val submitted = Json.parseToJsonElement(t.post("/ai/units/submit", """{"foods":[$foods]}""")).jsonObject
        assertEquals("msgbatch_1", submitted["batchId"]!!.jsonPrimitive.content)
        assertEquals(listOf("u0", "u1"), fake.batch.map { it.first })
        assertEquals("id1 | Food 1 | g | ", (fake.batch[0].second.content.single() as UserBlock.Text).text.lines().first())
        assertFalse(fake.batch[0].second.fallbacks)

        repeat(2) {
            val r = Json.parseToJsonElement(t.get("/ai/units/result?id=msgbatch_1")).jsonObject
            assertEquals("ended", r["status"]!!.jsonPrimitive.content)
            assertEquals(1, r["foods"]!!.jsonArray.size)
            assertEquals(1, r["failed"]!!.jsonPrimitive.content.toInt())
        }
        assertEquals((2_000 * 2.0 + 1_000 * 10.0) / 1e6 / 2, ledger.usage().usd, 1e-12)
        assertEquals(1, ledger.usage().calls)
    }

    @Test
    fun theSdkRequestCarriesTheFallbackEffortAndCache() {
        val req = AskRequest(TEXT_MODEL, 4000, listOf(app.shoku.data.ai.SystemBlock(RULES), app.shoku.data.ai.SystemBlock("lib", cache = true)), listOf(UserBlock.Text("t")), app.shoku.data.ai.MEAL_RESULT)
        val p = AnthropicSdkApi.betaParams(req)
        assertEquals(TEXT_MODEL, p.model().asString())
        assertEquals(4000L, p.maxTokens())
        assertTrue(p.betas().get().contains(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01))
        assertTrue(p.fallbacks().isPresent)
        assertEquals("low", p.outputConfig().get().effort().get().asString())
        val system = p.system().get().asBetaTextBlockParams()
        assertFalse(system[0].cacheControl().isPresent)
        assertTrue(system[1].cacheControl().isPresent)
    }

    /** The prompts are copied from ai.js; this keeps them identical. */
    @Test
    fun promptsMatchTheBackendWordForWord() {
        val file = File("../../backend/functions/api/ai.js")
        assumeTrue(file.isFile)
        val js = file.readText()
        listOf(RULES, UNITS_TASK, PLAN_TASK, MEAL_TASK_TEXT, LABEL_TASK, PRODUCT_TASK, BILL_TASK, HINTS_INTRO, REFS_INTRO, PLATE_TASK, RECIPE_TASK, mealTask("\${meal}")).forEach {
            assertTrue("not in ai.js: ${it.take(60)}", it in js)
        }
    }
}
