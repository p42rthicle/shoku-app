package app.shoku.data.net

import app.shoku.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** Error from the Shoku backend, with the message the server sent. */
class BackendException(val status: Int, message: String) : Exception(message)

/** Plain HTTPS calls to the Catalyst function, authenticated with the shared key. */
object Backend {
    /** False when [Network.blocked] (tests), whatever local.properties says. */
    val configured: Boolean get() = !Network.blocked && BuildConfig.SYNC_URL.isNotBlank() && BuildConfig.SYNC_KEY.isNotBlank()

    fun get(path: String, readTimeoutMs: Int = 40_000) = request("GET", path, null, readTimeoutMs)
    fun post(path: String, body: String, readTimeoutMs: Int = 40_000) = request("POST", path, body, readTimeoutMs)

    private fun request(method: String, path: String, body: String?, readTimeoutMs: Int): String {
        Network.ensureAllowed("backend $path")
        val conn = URL(BuildConfig.SYNC_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("X-Shoku-Key", BuildConfig.SYNC_KEY)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val msg = Regex("\"error\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: text.take(200)
                throw BackendException(code, msg.ifBlank { "Server said $code" })
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
