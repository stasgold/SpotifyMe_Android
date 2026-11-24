package com.spotifyme.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.min
import kotlin.math.pow

sealed class ApiException(message: String) : IOException(message) {
    class Http(val status: Int, val body: String) : ApiException("Request failed ($status): ${body.take(200)}")
    class Unauthorized : ApiException("Your Spotify session expired. Please sign in again.")
    class InvalidResponse : ApiException("Unexpected response from the server.")
}

/** One HTTP request. `body` is sent as-is with `contentType`. */
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
)

/** Raw response: status, headers (lower-cased names), body. */
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)

/** Performs a single request. Replaceable in tests. */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** Default transport built on HttpURLConnection (available on both the JVM and Android). */
object UrlConnectionTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body != null) {
                connection.doOutput = true
                request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val headers = connection.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull().orEmpty() }
            HttpResponse(status, headers, body)
        } finally {
            connection.disconnect()
        }
    }
}

/** Small async HTTP helper with retry on 429 (rate limiting) and 5xx. */
class HttpClient(
    private val transport: HttpTransport = UrlConnectionTransport,
    private val maxRetries: Int = 3,
) {
    suspend fun send(request: HttpRequest): String {
        var attempt = 0
        while (true) {
            val response = transport.execute(request)
            val status = response.status
            when {
                status in 200..299 -> return response.body
                status == 401 -> throw ApiException.Unauthorized()
                (status == 429 || status >= 500) && attempt < maxRetries -> {
                    attempt++
                    val retryAfter = response.headers["retry-after"]?.toDoubleOrNull() ?: 2.0.pow(attempt)
                    delay((min(retryAfter, 30.0) * 1000).toLong())
                }
                else -> throw ApiException.Http(status, response.body)
            }
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        send(HttpRequest(url, headers = headers))

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        /** Builds a URL from a base string and query items (sorted, percent-encoded). */
        fun url(base: String, query: Map<String, String> = emptyMap()): String {
            if (query.isEmpty()) return base
            return base + "?" + query.toSortedMap().entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        }

        /** RFC 3986 percent-encoding (spaces as %20, not "+"). */
        fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")

        /** application/x-www-form-urlencoded body. */
        fun formBody(form: Map<String, String>): String =
            form.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
    }
}

internal suspend inline fun <reified T> HttpClient.getJson(url: String, headers: Map<String, String> = emptyMap()): T =
    HttpClient.json.decodeFromString(get(url, headers))
