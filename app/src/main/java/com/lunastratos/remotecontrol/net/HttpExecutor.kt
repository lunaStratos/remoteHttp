package com.lunastratos.remotecontrol.net

import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object HttpExecutor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Result(val success: Boolean, val code: Int, val body: String)

    /**
     * Execute the request defined by [item]. For commands, [substitution] replaces
     * the `{{value}}` placeholder in body / URL with the user-entered int or string.
     */
    suspend fun execute(item: DeviceItem, substitution: String? = null): Result =
        withContext(Dispatchers.IO) {
            try {
                val finalUrl = item.url.replacePlaceholder(substitution)
                val builder = Request.Builder().url(finalUrl)

                for (h in item.headers) {
                    if (h.key.isNotBlank()) builder.addHeader(h.key, h.value)
                }

                val needsBody = item.method != HttpMethod.GET && item.method != HttpMethod.DELETE
                val rawBody = item.bodyTemplate.replacePlaceholder(substitution)
                val body = if (needsBody) {
                    val mediaType = item.headers
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

                client.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    Result(resp.isSuccessful, resp.code, text)
                }
            } catch (t: Throwable) {
                Result(false, -1, t.message ?: t::class.java.simpleName)
            }
        }

    private fun String.replacePlaceholder(value: String?): String =
        if (value == null) this else replace("{{value}}", value)

    /** Plain GET that returns the response body as a String, or throws on network error. */
    suspend fun fetchText(url: String): Result =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    Result(resp.isSuccessful, resp.code, text)
                }
            } catch (t: Throwable) {
                Result(false, -1, t.message ?: t::class.java.simpleName)
            }
        }
}
