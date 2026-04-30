package com.lunastratos.remotecontrol.net

import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.HttpHeader
import com.lunastratos.remotecontrol.data.HttpMethod
import com.lunastratos.remotecontrol.data.Settings
import com.lunastratos.remotecontrol.util.JsonPathUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object HttpExecutor {

    /** Sentinel value placed in `Authorization: Bearer __auto__` headers — replaced at request time
     *  with a freshly fetched token when [DeviceItem.authTokenUrl] is configured. */
    const val AUTO_TOKEN_PLACEHOLDER = "__auto__"

    private val secureClient = buildClient(insecure = false)
    private val insecureClient = buildClient(insecure = true)

    private fun client(insecure: Boolean): OkHttpClient =
        if (insecure) insecureClient else secureClient

    /** Resolves to [Settings.insecureTls] when a Settings instance is available; safe default false. */
    private var insecureTlsResolver: () -> Boolean = { false }
    fun bindSettings(settings: Settings) {
        insecureTlsResolver = { settings.insecureTls }
    }

    data class Result(val success: Boolean, val code: Int, val body: String)

    /**
     * Execute the request defined by [item]. For commands, [substitution] replaces
     * the `{{value}}` placeholder in body / URL with the user-entered int or string.
     */
    suspend fun execute(item: DeviceItem, substitution: String? = null): Result =
        withContext(Dispatchers.IO) {
            executeWithRetry(item, substitution, attempts = 3)
        }

    private suspend fun executeWithRetry(
        item: DeviceItem,
        substitution: String?,
        attempts: Int
    ): Result {
        var last: Result = Result(false, -1, "")
        var delayMs = 500L
        for (i in 0 until attempts) {
            val r = executeOnce(item, substitution)
            // Retry only on transport-level failures (-1). Non-2xx server responses are
            // user-meaningful and shouldn't trigger silent backoff.
            if (r.code != -1) return r
            last = r
            if (i < attempts - 1) {
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(4_000L)
            }
        }
        return last
    }

    private fun executeOnce(item: DeviceItem, substitution: String?): Result {
        return try {
            val finalUrl = item.url.replacePlaceholder(substitution)
            val builder = Request.Builder().url(finalUrl)

            val headers = resolveHeaders(item)
            for (h in headers) {
                if (h.key.isNotBlank()) builder.addHeader(h.key, h.value)
            }

            val needsBody = item.method != HttpMethod.GET && item.method != HttpMethod.DELETE
            val rawBody = item.bodyTemplate.replacePlaceholder(substitution)
            val body = if (needsBody) {
                val mediaType = headers
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?: "application/json"
                rawBody.toRequestBody(mediaType.toMediaType())
            } else null

            when (item.method) {
                HttpMethod.GET -> builder.get()
                HttpMethod.DELETE -> builder.delete()
                HttpMethod.POST -> builder.post(body!!)
                HttpMethod.PUT -> builder.put(body!!)
                HttpMethod.PATCH -> builder.patch(body!!)
            }

            client(insecureTlsResolver()).newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                Result(resp.isSuccessful, resp.code, text)
            }
        } catch (t: Throwable) {
            Result(false, -1, t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Returns headers to actually send: copies the configured list and, if any value
     * contains [AUTO_TOKEN_PLACEHOLDER] and [DeviceItem.authTokenUrl] is set, resolves a
     * fresh token via that endpoint and substitutes it. Failure leaves the placeholder
     * in place — the server will reject and the user sees `[401]`, which is the right signal.
     */
    private fun resolveHeaders(item: DeviceItem): List<HttpHeader> {
        if (item.authTokenUrl.isBlank()) return item.headers
        val needs = item.headers.any { it.value.contains(AUTO_TOKEN_PLACEHOLDER) }
        if (!needs) return item.headers
        val token = fetchToken(item) ?: return item.headers
        return item.headers.map {
            if (it.value.contains(AUTO_TOKEN_PLACEHOLDER)) {
                HttpHeader(it.key, it.value.replace(AUTO_TOKEN_PLACEHOLDER, token))
            } else it
        }
    }

    private fun fetchToken(item: DeviceItem): String? {
        return try {
            val rawBody = item.authBody
            val isForm = !rawBody.trim().startsWith("{")
            val mediaType = if (isForm) "application/x-www-form-urlencoded" else "application/json"
            val req = Request.Builder()
                .url(item.authTokenUrl)
                .post(rawBody.toRequestBody(mediaType.toMediaType()))
                .build()
            client(insecureTlsResolver()).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                val path = item.authTokenPath.ifBlank { "access_token" }
                JsonPathUtil.extract(text, path)?.trim('"')
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun String.replacePlaceholder(value: String?): String =
        if (value == null) this else replace("{{value}}", value)

    /** Plain GET that returns the response body as a String, or throws on network error. */
    suspend fun fetchText(url: String): Result =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).get().build()
                client(insecureTlsResolver()).newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    Result(resp.isSuccessful, resp.code, text)
                }
            } catch (t: Throwable) {
                Result(false, -1, t.message ?: t::class.java.simpleName)
            }
        }

    private fun buildClient(insecure: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
        if (insecure) {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trust), SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, trust)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }
}
