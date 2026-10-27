package dev.phoenix.server

import org.phoenix.core.network.netbird.NetBirdHttpTransport
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

/** Fixed-origin HTTPS, no redirects and no mutation replay after an uncertain response. */
class UrlConnectionNetBirdTransport : NetBirdHttpTransport {
    override fun execute(request: NetBirdHttpTransport.Request): NetBirdHttpTransport.Response {
        require(request.uri().scheme == "https" && request.uri().host == "api.netbird.io" && request.uri().port == -1)
        val connection = request.uri().toURL().openConnection() as HttpURLConnection
        var bytes: ByteArray? = null
        try {
            connection.requestMethod = request.method()
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            request.headers().forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body().isNotEmpty()) {
                bytes = request.body().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            val body = input?.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 1024 * 1024) { "NetBird response exceeds size bound" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                } finally { buffer.fill(0) }
            }.orEmpty()
            val retry = connection.getHeaderField("Retry-After")
            return NetBirdHttpTransport.Response(status, body, if (retry == null) emptyMap() else mapOf("Retry-After" to retry))
        } finally { bytes?.fill(0); connection.disconnect() }
    }
}
