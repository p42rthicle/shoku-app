package app.shoku

import app.shoku.data.ai.AI_NOT_SET_UP
import app.shoku.data.ai.AiMode
import app.shoku.data.ai.AiTransports
import app.shoku.data.ai.AnthropicSdkApi
import app.shoku.data.net.Backend
import app.shoku.data.net.BackendException
import app.shoku.data.net.Network
import app.shoku.data.reference.OpenFoodFacts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM tests (screenshots included) can't reach the sync server, Anthropic or Open Food Facts. */
class NetworkSwitchTest {
    @Before fun guard() = NoNetwork.install()
    @After fun unguard() = NoNetwork.uninstall()

    @Test
    fun everyTestRunsWithTheNetworkSwitchedOff() = runBlocking {
        assertTrue("app/build.gradle.kts must pass -Dshoku.network=off to every test", Network.blocked)

        // Sync: not configured whatever local.properties holds, and a direct call throws before connecting.
        assertFalse(Backend.configured)
        assertThrows(BackendException::class.java) { Backend.get("/sync/pull?since=0") }
        assertThrows(BackendException::class.java) { Backend.post("/sync/push", "{}") }

        // AI: no backend, no key, no client; usage still answers locally.
        assertEquals(AiMode.NONE, AiTransports.mode())
        val ai = AiTransports.current()
        val e = runCatching { ai.post("/ai/meal", """{"text":"two eggs"}""") }.exceptionOrNull()
        assertEquals(AI_NOT_SET_UP, e?.message)
        assertTrue(ai.get("/ai/usage").contains("usd"))
        assertThrows(BackendException::class.java) { AnthropicSdkApi("sk-ant-test") }

        // Open Food Facts: search is quietly empty, a barcode lookup throws.
        assertTrue(OpenFoodFacts.search("masala peanuts").isEmpty())
        assertTrue(runCatching { OpenFoodFacts.lookup("8900000000001") }.exceptionOrNull() is BackendException)

        assertEquals("connections attempted", emptyList<Any>(), NoNetwork.attempts.toList())
    }
}
