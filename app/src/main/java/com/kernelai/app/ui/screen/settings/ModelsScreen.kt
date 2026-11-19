package com.kernelai.app.ui.screen.settings

import com.kernelai.app.ui.components.GlassCard as Card

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernelai.app.data.*
import com.kernelai.app.ui.components.Checkbox
import com.kernelai.app.ui.components.TextButton
import com.kernelai.app.ui.viewmodel.ModelsViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kernelai.app.ui.screen.chat.GlassDialog as WindowDialog

@Composable
fun ModelsScreen(viewModel: ModelsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var editModel by remember { mutableStateOf<Pair<String, ModelConfig>?>(null) }
    var deleteProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var manageProvider by remember { mutableStateOf<String?>(null) }
    var modelQuery by remember { mutableStateOf("") }
    var discovery by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("模型与供应商", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { editProvider = ProviderConfig(name = "", baseUrl = "https://") }, enabled = !state.loading) { Text("添加") }
        }
        Text("OpenAI 兼容接口 · 密钥在本机加密保存", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        state.error?.let { Text(it, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            items(state.catalog.providers, key = { it.id }) { provider ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(provider.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(provider.baseUrl, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Row {
                            TextButton(onClick = { editProvider = provider }, enabled = !state.loading) { Text("编辑") }
                            TextButton(onClick = { discovery = provider.id; selected = emptySet(); viewModel.fetch(provider.id) }, enabled = !state.loading) { Text("获取模型") }
                            TextButton(onClick = { editModel = provider.id to ModelConfig("") }, enabled = !state.loading) { Text("手动添加") }
                            TextButton(onClick = { deleteProvider = provider }, enabled = !state.loading) { Text("删除") }
                        }
                        if (provider.models.isEmpty()) Text("尚未添加模型。获取列表或输入准确模型 ID。", fontSize = 13.sp)
                        provider.models.take(6).forEach { model ->
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { editModel = provider.id to model }.padding(vertical = 10.dp)) {
                                    Text(model.name, fontWeight = FontWeight.Medium)
                                    Text("${model.contextWindow} 上下文 · ${model.maxOutputTokens} 输出 · ${if (model.supportsImages) "图像" else "文本"}", fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                TextButton(onClick = { viewModel.select(provider.id, model.id) }, enabled = !state.loading) {
                                    Text(if (state.catalog.selectedProvider == provider.id && state.catalog.selectedModel == model.id) "默认" else "设为默认")
                                }
                            }
                        }
                        if (provider.models.size > 6) TextButton(onClick = { modelQuery = ""; manageProvider = provider.id }) { Text("查看全部 ${provider.models.size} 个模型") }
                    }
                }
            }
        }
    }
    manageProvider?.let { id ->
        val provider = state.catalog.providers.firstOrNull { it.id == id }
        WindowDialog(show = true, title = "管理模型", onDismissRequest = { manageProvider = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(modelQuery, { modelQuery = it }, label = "搜索模型", singleLine = true)
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(provider?.models.orEmpty().filter { it.id.contains(modelQuery, true) || it.name.contains(modelQuery, true) }, key = { it.id }) { model ->
                        Column(Modifier.fillMaxWidth().clickable { editModel = id to model }.padding(vertical = 14.dp)) {
                            Text(model.name); Text(model.id, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                TextButton(onClick = { manageProvider = null }) { Text("关闭") }
            }
        }
    }
    editProvider?.let { provider -> ProviderEditor(provider, { editProvider = null }) { next, fetch ->
        viewModel.saveProvider(next, fetch); editProvider = null
        if (fetch) { discovery = next.id; selected = emptySet() }
    } }
    editModel?.let { (id, model) -> ModelEditor(model, { editModel = null }, { updated ->
        viewModel.saveModel(id, updated); editModel = null
    }, if (model.id.isNotEmpty()) ({ viewModel.deleteModel(id, model.id); editModel = null }) else null) }
    deleteProvider?.let { provider -> WindowDialog(show = true, title = "删除 ${provider.name}？", summary = "会移除供应商配置及其模型，已有对话内容会保留。", onDismissRequest = { deleteProvider = null }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { deleteProvider = null }) { Text("取消") }
            TextButton(onClick = { viewModel.deleteProvider(provider.id); deleteProvider = null }) { Text("删除") }
        }
    } }
    discovery?.let { id ->
        val ids = state.discovered[id].orEmpty()
        WindowDialog(show = true, title = "选择要添加的模型", onDismissRequest = { discovery = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.loading) CircularProgressIndicator()
                state.error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                Text("能力参数需按供应商说明配置，获取列表不会推断图像能力。", fontSize = 12.sp)
                if (!state.loading && ids.isEmpty()) Text("没有获取到模型，可返回手动添加。")
                Row { TextButton(onClick = { selected = ids.take(1000).toSet() }) { Text("全选（最多 1000）") }; TextButton(onClick = { selected = emptySet() }) { Text("清空") } }
                LazyColumn(Modifier.heightIn(max = 320.dp)) { items(ids, key = { it }) { model ->
                    Row(Modifier.fillMaxWidth().clickable { selected = if (model in selected) selected - model else selected + model }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(model in selected, { selected = if (it) selected + model else selected - model })
                        Text(model, modifier = Modifier.padding(start = 12.dp), fontSize = 14.sp)
                    }
                } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { discovery = null }) { Text("取消") }
                    TextButton(onClick = { viewModel.addModels(id, selected); discovery = null }, enabled = selected.isNotEmpty() && !state.loading) { Text("添加 ${selected.size} 个") }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditor(provider: ProviderConfig, onClose: () -> Unit, onSave: (ProviderConfig, Boolean) -> Unit) {
    var name by remember { mutableStateOf(provider.name) }
    var url by remember { mutableStateOf(provider.baseUrl) }
    var secret by remember { mutableStateOf(provider.apiKey) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(fetch: Boolean) {
        runCatching { require(name.isNotBlank()) { "请输入供应商名称" }; com.kernelai.app.ai.ProviderApi.validate(url.trim(), secret.trim()) }
            .onSuccess { onSave(provider.copy(name = name.trim(), baseUrl = url.trim().trimEnd('/'), apiKey = secret.trim()), fetch) }
            .onFailure { error = it.message }
    }
    WindowDialog(show = true, title = "供应商配置", onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(name, { name = it }, label = "名称", singleLine = true)
            TextField(url, { url = it }, label = "Base URL（含 /v1 等接口前缀）", singleLine = true)
            TextField(secret, { secret = it }, label = "API Key（本地服务可留空）", singleLine = true, visualTransformation = PasswordVisualTransformation())
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            Row { TextButton(onClick = onClose) { Text("取消") }; TextButton(onClick = { save(false) }) { Text("保存") }; TextButton(onClick = { save(true) }) { Text("保存并获取") } }
        }
    }
}

@Composable
private fun ModelEditor(model: ModelConfig, onClose: () -> Unit, onSave: (ModelConfig) -> Unit, onDelete: (() -> Unit)?) {
    var id by remember { mutableStateOf(model.id) }; var name by remember { mutableStateOf(model.name) }
    var context by remember { mutableStateOf(model.contextWindow.toString()) }; var output by remember { mutableStateOf(model.maxOutputTokens.toString()) }
    var temperature by remember { mutableStateOf(model.temperature.toString()) }; var retries by remember { mutableStateOf(model.maxRetries.toString()) }
    var image by remember { mutableStateOf(model.supportsImages) }; var usage by remember { mutableStateOf(model.streamUsage) }
    var error by remember { mutableStateOf<String?>(null) }
    WindowDialog(show = true, title = "模型配置", onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(id, { id = it }, label = "模型 ID", singleLine = true, readOnly = model.id.isNotEmpty())
            TextField(name, { name = it }, label = "显示名称", singleLine = true)
            TextField(context, { context = it }, label = "上下文窗口（tokens）", singleLine = true)
            TextField(output, { output = it }, label = "最大输出 tokens", singleLine = true)
            TextField(temperature, { temperature = it }, label = "Temperature（0–2）", singleLine = true)
            TextField(retries, { retries = it }, label = "失败重试次数（0–5，默认 5）", singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("图像输入", Modifier.weight(1f)); Switch(image, { image = it }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("请求流式 usage", Modifier.weight(1f)); Switch(usage, { usage = it }) }
            Text("不兼容 stream_options 的接口可关闭 usage；上下文会改用估算。", fontSize = 12.sp)
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("删除") } }
                TextButton(onClick = onClose) { Text("取消") }
                TextButton(onClick = {
                    runCatching {
                        val value = ModelConfig(id.trim(), name.trim().ifBlank { id.trim() }, context.toInt(), output.toInt(), image, temperature.toDouble(), retries.toInt(), usage)
                        ProviderCatalog(listOf(ProviderConfig(name = "validation", baseUrl = "", models = listOf(value)))).validate()
                        value
                    }.onSuccess(onSave).onFailure { error = it.message ?: "配置无效" }
                }) { Text("保存") }
            }
        }
    }
}
