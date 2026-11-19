package com.kernelai.app.data

import com.kernelai.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class ModelConfig(val id: String, val name: String = id, val contextWindow: Int = 32768,
    val maxOutputTokens: Int = 4096, val supportsImages: Boolean = false, val temperature: Double = .7,
    val maxRetries: Int = 5, val streamUsage: Boolean = true)

@Serializable
data class ProviderConfig(val id: String = UUID.randomUUID().toString(), val name: String,
    val baseUrl: String, val apiKey: String = "", val models: List<ModelConfig> = emptyList())

@Serializable
data class ProviderCatalog(val providers: List<ProviderConfig> = emptyList(), val selectedProvider: String = "", val selectedModel: String = "") {
    fun activeConfig(): AiConfig? {
        val provider = providers.firstOrNull { it.id == selectedProvider } ?: return null
        val model = provider.models.firstOrNull { it.id == selectedModel } ?: return null
        return AiConfig(provider.baseUrl, provider.apiKey, model.id, model.maxOutputTokens, model.temperature,
            model.contextWindow, model.supportsImages, provider.id, model.maxRetries, model.streamUsage)
    }
    fun validate() {
        require(providers.map { it.id }.distinct().size == providers.size && providers.size <= 50)
        providers.forEach { provider ->
            require(provider.name.isNotBlank() && provider.models.map { it.id }.distinct().size == provider.models.size && provider.models.size <= 1000)
            provider.models.forEach { model ->
                require(model.id.isNotBlank() && model.contextWindow in 1024..4_194_304)
                require(model.maxOutputTokens in 1 until model.contextWindow && model.temperature in 0.0..2.0 && model.maxRetries in 0..5)
            }
        }
    }
}

@Serializable
data class SavedChat(val id: String, val title: String, val createdAt: Long, val history: List<ApiChatMessage>,
    val messages: List<ChatMessage>, val draft: String = "", val attachments: List<Attachment> = emptyList(),
    val providerId: String = "", val modelId: String = "", val usage: ContextUsage? = null, val queued: List<ApiChatMessage> = emptyList(),
    val usageTotals: UsageTotals = UsageTotals(), val workspaceId: String = "default")
@Serializable
data class ChatArchive(val version: Int = 1, val activeId: String = "", val sessions: List<SavedChat> = emptyList())

internal class AtomicJson(private val file: File) {
    private val lock = Mutex()
    suspend fun read(): String? = withContext(Dispatchers.IO) { lock.withLock {
        if (!file.exists()) null else { require(file.length() <= 64L * 1024 * 1024); file.readText() }
    } }
    suspend fun write(text: String) = withContext(Dispatchers.IO) { lock.withLock { writeAtomic(file, text) } }
}
