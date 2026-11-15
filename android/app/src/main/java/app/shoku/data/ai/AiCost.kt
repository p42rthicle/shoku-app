package app.shoku.data.ai

import app.shoku.data.net.BackendException
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/** Token counts from one response's usage block. */
data class TokenUsage(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0)

/** USD per million tokens, the same table as PRICES in ai.js. Batch requests cost half. */
data class Price(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

val PRICES: Map<String, Price> = linkedMapOf(
    "claude-opus-5-5" to Price(4.0, 20.0, 0.2, 5.0),
    "claude-sonnet-5-5" to Price(2.0, 10.0, 0.1, 2.5),
    "claude-haiku-5-5" to Price(0.1, 0.5, 0.01, 0.125),
)

/** Cost of one response in USD (ai.js costOf). Unknown models are priced as Opus to stay on the safe side. */
fun costOf(model: String?, u: TokenUsage, batch: Boolean = false): Double {
    val key = PRICES.keys.firstOrNull { (model ?: "").startsWith(it) } ?: PHOTO_MODEL
    val p = PRICES.getValue(key)
    val usd = (u.input * p.input + u.output * p.output + u.cacheRead * p.cacheRead + u.cacheWrite * p.cacheWrite) / 1e6
    return if (batch) usd / 2 else usd
}

/** A tiny string store, so the ledger can live in SharedPreferences on the phone and in a map in tests. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/**
 * Direct mode's copy of the backend's spending records (index.js usage/addSpend/checkCap): this month's
 * dollars and requests, and a monthly cap (default $30). Months are UTC, like the backend's.
 */
class UsageLedger(private val store: KeyValueStore, private val month: () -> String = { YearMonth.now(ZoneOffset.UTC).toString() }) {

    @Synchronized
    fun usage(): AiUsage {
        val m = month()
        return AiUsage(
            month = m,
            usd = store.get("usd:$m")?.toDoubleOrNull() ?: 0.0,
            calls = store.get("calls:$m")?.toIntOrNull() ?: 0,
            capUsd = store.get("cap")?.toDoubleOrNull()?.takeIf { it > 0 } ?: DEFAULT_CAP_USD,
        )
    }

    @Synchronized
    fun add(usd: Double) {
        val now = usage()
        store.put("usd:${now.month}", (now.usd + (if (usd.isNaN()) 0.0 else usd)).toString())
        store.put("calls:${now.month}", (now.calls + 1).toString())
    }

    /** Refuses AI work once this month's spend reaches the cap, with the backend's message. */
    fun checkCap() {
        val u = usage()
        if (u.usd >= u.capUsd) {
            throw BackendException(
                402,
                "This month's AI limit of \$${jsNumber(u.capUsd, DEFAULT_CAP_USD)} is used up (\$${String.format(Locale.US, "%.2f", u.usd)}). Raise it in Settings or wait for next month.",
            )
        }
    }

    @Synchronized
    fun setCap(usd: Double): Double {
        if (!(usd >= 1 && usd <= 100)) throw BackendException(400, "cap must be between \$1 and \$100")
        store.put("cap", usd.toString())
        return usd
    }

    /** A finished batch's cost is counted once, however often its result is fetched. */
    @Synchronized
    fun countBatchOnce(batchId: String, usd: Double) {
        if (store.get("batch:$batchId") != null) return
        store.put("batch:$batchId", "1")
        add(usd)
    }

    companion object {
        const val DEFAULT_CAP_USD = 30.0
    }
}
