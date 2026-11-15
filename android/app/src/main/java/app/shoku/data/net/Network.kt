package app.shoku.data.net

/**
 * Test-only kill switch for everything that leaves the phone: the sync backend ([Backend]), AI
 * ([app.shoku.data.ai.AiTransports] and the Anthropic SDK), Open Food Facts and the Health Connect export.
 *
 * Off in the app. Every JVM test turns it on with the system property `shoku.network=off`
 * (set in app/build.gradle.kts), so a local build whose local.properties points at a real sync server can
 * still never send test or demo data anywhere. With it on, [Backend.configured] is false, AI answers
 * "not set up" without building a client, and any request that slips through throws before connecting.
 */
object Network {
    @Volatile
    var blocked: Boolean = System.getProperty("shoku.network") == "off"

    /** Throws when the switch is on; call right before opening a connection. */
    fun ensureAllowed(what: String) {
        if (blocked) throw BackendException(0, "Network is switched off for tests ($what)")
    }
}
