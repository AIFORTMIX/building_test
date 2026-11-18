package com.kernelai.app.ai

import com.kernelai.app.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProviderApiTest {
    @Test fun modelsEndpointImportsIdsWithoutGuessingCapabilities() = runBlocking {
        val server = MockWebServer().apply { start() }
        val api = ProviderApi()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"vision-model"},{"id":"text-model"},{"id":"vision-model"}]}"""))
            val provider = ProviderConfig(name = "Local", baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "test-only-key")
            assertEquals(listOf("text-model", "vision-model"), api.models(provider))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/models", request.path); assertEquals("Bearer test-only-key", request.getHeader("Authorization"))
            assertFalse(ModelConfig("vision-model").supportsImages)
        } finally { api.close(); server.shutdown() }
    }
    @Test fun catalogSelectsCorrectProviderAndRejectsInvalidBudgets() {
        val p1 = ProviderConfig(name = "One", baseUrl = "https://one.example/v1", models = listOf(ModelConfig("shared")))
        val p2 = ProviderConfig(name = "Two", baseUrl = "https://two.example/v1", models = listOf(ModelConfig("shared", supportsImages = true)))
        val catalog = ProviderCatalog(listOf(p1, p2), p2.id, "shared")
        catalog.validate(); assertEquals(p2.baseUrl, catalog.activeConfig()!!.baseUrl); assertTrue(catalog.activeConfig()!!.supportsImages)
        assertThrows(IllegalArgumentException::class.java) { catalog.copy(providers = listOf(p1.copy(models = listOf(ModelConfig("bad", contextWindow = 1024, maxOutputTokens = 4096))))).validate() }
        assertThrows(IllegalArgumentException::class.java) { ProviderApi.validate("http://remote.example/v1", "secret") }
    }
}
