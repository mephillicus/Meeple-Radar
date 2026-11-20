package com.meepleradar.data.remote

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): String

    suspend fun post(url: String, headers: Map<String, String>, body: String): String

    suspend fun patch(url: String, headers: Map<String, String>, body: String): String = post(url, headers, body)
}

internal fun bodyOrThrow(response: Response): String = response.use { res ->
    val responseBody = res.body.string()
    if (!res.isSuccessful) {
        val detail = responseBody.take(MAX_ERROR_BODY_LENGTH)
        throw IOException(
            if (detail.isBlank()) "HTTP ${res.code}" else "HTTP ${res.code}: $detail",
        )
    }
    responseBody
}

private const val MAX_ERROR_BODY_LENGTH = 1000

class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): String =
        execute("GET", url, headers, null)

    override suspend fun post(url: String, headers: Map<String, String>, body: String): String =
        execute("POST", url, headers, body)

    override suspend fun patch(url: String, headers: Map<String, String>, body: String): String =
        execute("PATCH", url, headers, body)

    private suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        when (method) {
            "POST" -> builder.post((body ?: "").toRequestBody(JSON_MEDIA_TYPE))
            "PATCH" -> builder.patch((body ?: "").toRequestBody(JSON_MEDIA_TYPE))
        }
        bodyOrThrow(client.newCall(builder.build()).execute())
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
