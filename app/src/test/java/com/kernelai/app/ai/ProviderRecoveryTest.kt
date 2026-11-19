package com.kernelai.app.ai

import com.kernelai.app.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ProviderRecoveryTest {
    private fun sse(delta: String, finish: String? = null) = "data: {\"choices\":[{\"delta\":$delta,\"finish_reason\":${finish?.let { "\"$it\"" } ?: "null"}}]}\n\n"
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private fun config(api: MockWebServer) = AiConfig(api.url("/v1").toString().trimEnd('/'), "fixture-private-secret", "fixture-model")
    @Test fun quotaAuthAndContextErrorsAreActionableAndNeverRetried() {
        for ((status, body, kind) in listOf(Triple(429, """{"error":{"code":"insufficient_quota","message":"余额不足"}}""", "quota"), Triple(401, "invalid_api_key", "authentication"), Triple(400, "maximum context length exceeded", "context"))) {
            val failure = AiFailures.parse(status, body)
            assertEquals(kind, failure.kind); assertFalse(failure.retryable); assertTrue(failure.suggestion.isNotBlank())
        }
        val scrubbed = AiFailures.parse(401, "bad fixture-private-secret Bearer visible-key", requestId = "fixture-private-secret", secret = "fixture-private-secret")
        assertFalse(scrubbed.toString().contains("fixture-private-secret")); assertFalse(scrubbed.detail.contains("visible-key"))
    }
    @Test fun retryAfterAcceptsDatesAndDoesNotShortenLongServerWaits() {
        assertEquals(7000L, AiFailures.parse(429, "rate limit", retryAfter = "7").retryAfterMs)
        val future = ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        assertTrue(AiFailures.parse(429, "rate limit", retryAfter = future).retryAfterMs!! in 58000..61000)
        assertFalse(AiFailures.parse(429, "rate limit", retryAfter = "300").retryable)
    }
    @Test fun temporaryErrorsRetryAtMostFiveTimesWithServerDelays() = runBlocking {
        val api = MockWebServer(); api.start(); val waits = mutableListOf<Long>()
        val client = OpenAiClient(config(api)) { waits.add(it) }
        try {
            repeat(6) { api.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "2").setBody("overloaded")) }
            val events = client.streamChatCompletion(listOf(ApiChatMessage("user", "test"))).toList()
            assertEquals(6, api.requestCount); assertEquals(List(5) { 2000L }, waits)
            assertEquals(listOf(1, 2, 3, 4, 5), events.filterIsInstance<StreamEvent.Retrying>().map { it.attempt })
            assertEquals(6, events.filterIsInstance<StreamEvent.Error>().single().failure!!.attempts)
        } finally { client.close(); api.shutdown() }
    }
    @Test fun reasoningIsSeparatePreservedAndEchoedForTheNextToolRequest() = runBlocking {
        val api = MockWebServer(); api.start(); val client = OpenAiClient(config(api))
        try {
            api.enqueue(response(sse("""{"reasoning_content":"核实目标身份"}""") + sse("""{"content":"我先检查"}""", "stop") + "data: [DONE]\n\n"))
            val events = client.streamChatCompletion(listOf(ApiChatMessage("user", "test"))).toList()
            val accumulator = OpenAiClient.StreamAccumulator(); events.forEach(accumulator::process)
            val message = accumulator.buildMessage()
            assertEquals("核实目标身份", message.reasoningContent); assertEquals("我先检查", message.content)
            api.takeRequest()
            api.enqueue(response(sse("""{"content":"完成"}""", "stop")))
            client.streamChatCompletion(listOf(message)).toList()
            val sent = Json.parseToJsonElement(api.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray.single().jsonObject
            assertEquals(JsonPrimitive("核实目标身份"), sent["reasoning_content"])
        } finally { client.close(); api.shutdown() }
    }
    @Test fun sseErrorAfterReasoningPreservesPartialOutputAndSuppressesReplay() = runBlocking {
        val api = MockWebServer(); api.start(); val client = OpenAiClient(config(api)) { error("Must not retry partial output") }
        try {
            api.enqueue(response(sse("""{"reasoning":"实际思考内容"}""") + "data: {\"error\":{\"type\":\"server_error\",\"message\":\"temporary outage\"}}\n\n"))
            val events = client.streamChatCompletion(listOf(ApiChatMessage("user", "test"))).toList()
            assertEquals("实际思考内容", events.filterIsInstance<StreamEvent.ReasoningDelta>().single().delta)
            assertTrue(events.filterIsInstance<StreamEvent.Error>().single().failure!!.partialOutput)
            assertEquals(1, api.requestCount)
        } finally { client.close(); api.shutdown() }
    }
    @Test fun jsonFallbackEmitsReasoningAndReportsLengthWithoutDiscardingBody() = runBlocking {
        val api = MockWebServer(); api.start(); val client = OpenAiClient(config(api))
        try {
            api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"role":"assistant","reasoning_content":"思考","content":"未完成正文"},"finish_reason":"length"}],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":80},"completion_tokens_details":{"reasoning_tokens":5}}}"""))
            val events = client.streamChatCompletion(listOf(ApiChatMessage("user", "test"))).toList()
            assertEquals("思考", events.filterIsInstance<StreamEvent.ReasoningDelta>().single().delta)
            assertEquals("未完成正文", events.filterIsInstance<StreamEvent.ContentDelta>().single().delta)
            assertTrue(events.filterIsInstance<StreamEvent.Error>().single().failure!!.partialOutput)
            assertEquals(80, events.filterIsInstance<StreamEvent.Usage>().single().usage.cachedTokens)
            assertEquals(5, events.filterIsInstance<StreamEvent.Usage>().single().usage.reasoningTokens)
        } finally { client.close(); api.shutdown() }
    }
    @Test fun totalsCountOnlyReportedUsageAndCacheRatioUsesReportedRequests() {
        val missing = UsageTotals().plus(ContextUsage(100, 20, estimated = false))
        assertFalse(missing.cacheReported); assertEquals(1, missing.requests)
        val totals = missing.plus(ContextUsage(100, 10, estimated = false, cachedTokens = 80)).plus(ContextUsage(500))
        assertEquals(200L, totals.input); assertEquals(30L, totals.output); assertEquals(80L, totals.cached); assertEquals(100L, totals.cacheInput); assertEquals(2, totals.requests)
    }
}
