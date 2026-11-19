package com.kernelai.app.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class AiFailure(val kind: String, val title: String, val detail: String, val suggestion: String,
    val status: Int? = null, val code: String? = null, val requestId: String? = null,
    val retryable: Boolean = false, val retryAfterMs: Long? = null, val partialOutput: Boolean = false,
    val attempts: Int = 1) {
    val message get() = "$title${status?.let { " · HTTP $it" }.orEmpty()}：$detail"
}

internal class ProviderFailure(val failure: AiFailure) : IOException(failure.message)

internal object AiFailures {
    fun scrub(value: String, secret: String = ""): String {
        var text = value.take(16384)
        if (secret.isNotBlank()) text = text.replace(secret, "[已隐藏]")
        return text.replace(Regex("(?i)(Bearer\\s+)[^\\s\"'<>]+"), "$1[已隐藏]")
            .replace(Regex("(?i)(sk-|sk_)[a-z0-9_-]{8,}"), "[已隐藏]")
            .replace(Regex("(?i)(api[_-]?key|authorization|token|password)([\\s\"']*[:=][\\s\"']*)[^\\s\",'<>]+"), "$1$2[已隐藏]")
            .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "").take(2000)
    }
    fun http(response: Response, secret: String = ""): AiFailure {
        val body = runCatching { response.body?.source()?.let { source -> source.request(16_385); source.readUtf8(minOf(source.buffer.size, 16_384L)) } }.getOrNull().orEmpty()
        return parse(response.code, body, response.header("x-request-id") ?: response.header("request-id"), response.header("Retry-After"), secret)
    }
    fun parse(status: Int?, body: String, requestId: String? = null, retryAfter: String? = null, secret: String = ""): AiFailure {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.get("error")
        val objectValue = error as? JsonObject ?: root
        fun text(key: String) = (objectValue?.get(key) as? JsonPrimitive)?.contentOrNull
        val code = text("code") ?: text("type")
        val supplied = text("message") ?: (error as? JsonPrimitive)?.contentOrNull
        val detail = scrub(supplied ?: if (body.trimStart().startsWith('<')) "服务商或网关返回了网页，未收到有效的 API 响应" else body.ifBlank { "服务商未提供错误详情" }, secret)
        val label = "$code $detail".lowercase()
        val kind = when {
            listOf("insufficient_quota", "billing", "credit", "balance", "spend cap", "spend limit", "余额", "配额用尽").any { it in label } || status == 402 -> "quota"
            listOf("context_length", "context window", "maximum context", "too many tokens", "上下文").any { it in label } -> "context"
            status == 401 || "invalid_api_key" in label || "authentication" in label -> "authentication"
            status == 403 -> "permission"
            "model_not_found" in label || (status == 404 && "model" in label) -> "model"
            status == 404 -> "endpoint"
            status == 413 -> "request_size"
            status == 429 || "rate_limit" in label -> "rate_limit"
            status == 408 || "timeout" in label -> "timeout"
            status != null && status >= 500 || "overloaded" in label || "server_error" in label -> "server"
            else -> "request"
        }
        val (title, hint) = when (kind) {
            "quota" -> "额度或余额不足" to "检查服务商余额、组织额度和套餐；补充额度后再继续。"
            "context" -> "上下文超过限制" to "调整模型上下文/输出额度，或新建对话后继续。"
            "authentication" -> "API 身份验证失败" to "检查供应商密钥是否正确、有效，及 Base URL 是否属于同一供应商。"
            "permission" -> "没有调用权限" to "检查密钥的模型权限、组织权限和服务地区。"
            "model" -> "模型不存在或不可用" to "重新获取模型列表，核对模型 ID 和权限。"
            "endpoint" -> "API 地址不存在" to "核对 Base URL 的接口前缀，不要重复填写 /chat/completions。"
            "request_size" -> "请求内容过大" to "减少附件和对话长度。"
            "rate_limit" -> "请求受到限流" to "等待服务商限流窗口恢复；自动重试可随时停止。"
            "timeout" -> "服务商响应超时" to "稍后继续本轮请求，或检查供应商网络。"
            "server" -> "服务商暂时不可用" to "稍后重试；持续失败时提供请求 ID 联系服务商。"
            else -> "服务商拒绝了请求" to "根据错误详情检查模型配置和请求参数。"
        }
        val wait = retryAfter?.toLongOrNull()?.coerceAtLeast(0)?.let { if (it > 86400) 86_400_000L else it * 1000 }
            ?: retryAfter?.let { runCatching { (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) }.getOrNull() }
        return AiFailure(kind, title, detail, hint, status, code?.let { scrub(it, secret).take(120) }, requestId?.let { scrub(it, secret).take(160) },
            kind in setOf("rate_limit", "timeout", "server") && (wait == null || wait <= 120_000), wait)
    }
    fun network(error: Throwable, secret: String = "") = AiFailure("network", "网络连接失败", scrub(error.message ?: "连接中断", secret),
        "检查网络、代理和供应商地址；已收到的正文或工具参数不会自动重放。",
        retryable = error !is javax.net.ssl.SSLException && error !is java.net.UnknownServiceException)
}
