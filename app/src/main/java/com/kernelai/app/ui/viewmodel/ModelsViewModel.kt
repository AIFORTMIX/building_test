package com.kernelai.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.ai.ProviderApi
import com.kernelai.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelsState(val catalog: ProviderCatalog = ProviderCatalog(), val loading: Boolean = true,
    val error: String? = null, val discovered: Map<String, List<String>> = emptyMap())

class ModelsViewModel : ViewModel() {
    private val store = KernelAiApplication.instance.providerStore
    private val api = ProviderApi()
    private var storeHealthy = true
    private val _state = MutableStateFlow(ModelsState())
    val state = _state.asStateFlow()
    init { viewModelScope.launch {
        try {
            val stored = store.load()
            val catalog = stored ?: run {
                val prefs = KernelAiApplication.instance.getSharedPreferences("kernel_ai_settings", Context.MODE_PRIVATE)
                val model = prefs.getString("ai_model", "qwen2.5:7b") ?: "qwen2.5:7b"
                val provider = ProviderConfig(name = "已有配置", baseUrl = prefs.getString("ai_base_url", "http://localhost:11434/v1")!!,
                    apiKey = prefs.getString("ai_api_key", "")!!, models = listOf(ModelConfig(model)))
                ProviderCatalog(listOf(provider), provider.id, model).also { store.save(it); prefs.edit().remove("ai_api_key").apply() }
            }
            _state.value = ModelsState(catalog, false)
        } catch (e: Exception) { if (e is CancellationException) throw e; storeHealthy = false; _state.value = ModelsState(loading = false, error = "供应商读取失败，原文件保留：${e.message}") }
    } }
    fun saveProvider(provider: ProviderConfig, fetch: Boolean = false) = change({ old ->
        ProviderApi.validate(provider.baseUrl, provider.apiKey)
        old.copy(providers = old.providers.filterNot { it.id == provider.id } + provider)
    }) { if (fetch) fetch(provider.id) }
    fun saveModel(providerId: String, model: ModelConfig) = change({ old ->
        old.copy(providers = old.providers.map { p -> if (p.id == providerId) p.copy(models = p.models.filterNot { it.id == model.id } + model) else p })
    })
    fun addModels(providerId: String, ids: Set<String>) = change({ old -> old.copy(providers = old.providers.map { p ->
        if (p.id != providerId) p else p.copy(models = p.models + ids.filterNot { id -> p.models.any { it.id == id } }.map { ModelConfig(it) })
    }) })
    fun deleteProvider(id: String) = change({ it.copy(providers = it.providers.filterNot { p -> p.id == id }) })
    fun deleteModel(providerId: String, modelId: String) = change({ old -> old.copy(providers = old.providers.map { p ->
        if (p.id == providerId) p.copy(models = p.models.filterNot { it.id == modelId }) else p
    }) })
    fun select(providerId: String, modelId: String) = change({ it.copy(selectedProvider = providerId, selectedModel = modelId) })
    private fun change(transform: (ProviderCatalog) -> ProviderCatalog, after: () -> Unit = {}) {
        if (_state.value.loading || !storeHealthy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                var catalog = transform(_state.value.catalog)
                if (catalog.activeConfig() == null) {
                    val p = catalog.providers.firstOrNull { it.models.isNotEmpty() }
                    catalog = catalog.copy(selectedProvider = p?.id.orEmpty(), selectedModel = p?.models?.firstOrNull()?.id.orEmpty())
                }
                store.save(catalog)
                _state.value = _state.value.copy(catalog = catalog, loading = false)
                after()
            } catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(loading = false, error = "保存失败：${e.message}") }
        }
    }
    fun fetch(id: String) {
        val provider = _state.value.catalog.providers.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try { val ids = api.models(provider); _state.value = _state.value.copy(loading = false, discovered = _state.value.discovered + (id to ids)) }
            catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(loading = false, error = e.message) }
        }
    }
    override fun onCleared() { api.close(); super.onCleared() }
}
