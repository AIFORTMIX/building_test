package com.kernelai.app.ai

import com.kernelai.app.data.ProviderConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProviderApi : Closeable {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    companion object {
        fun validate(baseUrl: String, apiKey: String) {
            val url = baseUrl.trimEnd('/').toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "URL 不能包含账号、查询参数或片段" }
            require(apiKey.isBlank() || url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1")) { "携带密钥的远程供应商必须使用 HTTPS" }
        }
        fun parseModels(body: String): List<String> {
            val objectValue = Json.parseToJsonElement(body).jsonObject
            val list = objectValue["data"]?.jsonArray ?: objectValue["models"]?.jsonArray ?: error("响应缺少 data/models 数组")
            require(list.size <= 10000) { "模型列表过大" }
            return list.mapNotNull { item -> item.jsonObject.let { (it["id"] ?: it["name"])?.jsonPrimitive?.contentOrNull } }
                .filter { it.isNotBlank() && it.length <= 256 }.distinct().sorted()
        }
    }
    suspend fun models(provider: ProviderConfig): List<String> {
        validate(provider.baseUrl, provider.apiKey)
        val request = Request.Builder().url(provider.baseUrl.trimEnd('/') + "/models").apply {
            if (provider.apiKey.isNotBlank()) header("Authorization", "Bearer " + provider.apiKey)
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw IOException("获取模型失败：HTTP ${it.code}；可手动添加模型 ID")
                            val source = it.body?.source() ?: error("响应为空")
                            require(!source.request(2_097_153)) { "模型响应超过 2 MiB" }
                            parseModels(source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
    override fun close() { http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}
