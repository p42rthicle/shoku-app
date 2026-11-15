package app.shoku

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections

/**
 * A tripwire under [app.shoku.data.net.Network]'s switch: while installed, every outgoing connection the
 * JVM tries (HttpURLConnection, OkHttp inside the Anthropic SDK, plain sockets) asks this ProxySelector
 * first, gets refused, and is recorded. Tests assert that [attempts] stays empty.
 */
object NoNetwork : ProxySelector() {
    val attempts: MutableList<URI> = Collections.synchronizedList(mutableListOf())
    private var previous: ProxySelector? = null

    fun install() {
        if (getDefault() !== this) previous = getDefault()
        attempts.clear()
        setDefault(this)
    }

    fun uninstall() {
        if (getDefault() === this) setDefault(previous)
    }

    override fun select(uri: URI): List<Proxy> {
        attempts += uri
        throw IllegalStateException("Blocked network call in a test: $uri")
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
}
