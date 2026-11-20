package com.kernelai.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import android.content.res.Configuration
import com.kernelai.app.ui.navigation.LocalAppNavigator
import com.kernelai.app.ui.navigation.AppNavigator
import com.kernelai.app.ui.navigation.Route
import com.kernelai.app.ui.navigation.rememberAppNavigator
import com.kernelai.app.ui.screen.breakpoint.BreakpointScreen
import com.kernelai.app.ui.screen.chat.ChatScreen
import com.kernelai.app.ui.screen.disasm.DisasmScreen
import com.kernelai.app.ui.screen.log.LogScreen
import com.kernelai.app.ui.screen.memory.MemoryScreen
import com.kernelai.app.ui.screen.process.ProcessScreen
import com.kernelai.app.ui.screen.settings.McpAuditScreen
import com.kernelai.app.ui.screen.settings.ModelsScreen
import com.kernelai.app.ui.viewmodel.ModelsViewModel
import com.kernelai.app.ui.screen.settings.SettingsScreen
import com.kernelai.app.ui.theme.KernelAiTheme
import com.kernelai.app.ui.theme.KernelAiThemeController
import com.kernelai.app.ui.theme.KernelAiAppSettings
import com.kernelai.app.ui.viewmodel.DriverViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 轻微 MCP 主 Activity - 基于 KernelSU-Style-UI-Kit 模板结构
 * 使用 Miuix 主题、菜单与自适应导航
 */
class MainActivity : ComponentActivity() {
    private val requestedChat = kotlinx.coroutines.flow.MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(com.kernelai.app.ai.AgentTaskService.OPEN_CHAT, false)) requestedChat.value++

        setContent {
            val modelsViewModel = viewModel<ModelsViewModel>()
            val driverViewModel = viewModel<DriverViewModel>()
            val connectionStatus by driverViewModel.connectionStatus.collectAsStateWithLifecycle()
            val appSettings by driverViewModel.appSettings.collectAsStateWithLifecycle()
            val logEntries by driverViewModel.logEntries.collectAsStateWithLifecycle()

            val uiMode = UiMode.Miuix

            val darkMode = when (appSettings.themeMode) {
                com.kernelai.app.data.model.ThemeMode.DARK -> true
                com.kernelai.app.data.model.ThemeMode.LIGHT -> false
                com.kernelai.app.data.model.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // 初始化驱动连接
            LaunchedEffect(Unit) {
                driverViewModel.initialize(this@MainActivity)
            }

            // 设置系统栏样式
            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose { }
            }

            val navigator = rememberAppNavigator(Route.Main)
            val pagerState = rememberPagerState { MainPagerConfig.PAGE_COUNT }
            val settingsScrollState = rememberScrollState()
            val chatRequest by requestedChat.collectAsStateWithLifecycle()
            LaunchedEffect(chatRequest) {
                if (chatRequest > 0) {
                    while (navigator.backStack.size > 1) navigator.pop()
                    pagerState.scrollToPage(MainPagerConfig.PAGE_CHAT)
                }
            }
            val themeSettings = KernelAiAppSettings(
                colorMode = com.kernelai.app.ui.theme.KernelAiColorMode.fromThemeMode(appSettings.themeMode)
            )

            CompositionLocalProvider(
                LocalAppNavigator provides navigator,
                LocalUiMode provides uiMode,
            ) {
                KernelAiTheme(appSettings = themeSettings, uiMode = uiMode) {
                    val mainScreenEntry = @Composable {
                        MainScreen(
                            pagerState = pagerState,
                            settingsScrollState = settingsScrollState,
                            connectionStatus = connectionStatus,
                            appSettings = appSettings,
                            logEntries = logEntries,
                            driverViewModel = driverViewModel, modelsViewModel = modelsViewModel,
                        )
                    }

                    val navDisplay = @Composable {
                        NavDisplay(
                            backStack = navigator.backStack,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator()
                            ),
                            onBack = { navigator.pop() },
                            entryProvider = entryProvider {
                                entry<Route.McpAudit> { StandalonePage { McpAuditScreen { navigator.pop() } } }
                                entry<Route.Models> { StandalonePage { ModelsScreen(modelsViewModel) { navigator.pop() } } }
                                entry<Route.Workspace> { StandalonePage { com.kernelai.app.ui.screen.settings.WorkspaceScreen { navigator.pop() } } }
                                entry<Route.Main> { mainScreenEntry() }
                                entry<Route.Chat> { mainScreenEntry() }
                                entry<Route.Settings> { mainScreenEntry() }
                                entry<Route.ProcessBrowser> { mainScreenEntry() }
                                entry<Route.BreakpointManager> { mainScreenEntry() }
                                entry<Route.MemoryViewer> { StandalonePage { MemoryScreen() } }
                                entry<Route.DisasmViewer> { StandalonePage { DisasmScreen() } }
                                entry<Route.DebuggerViewer> { StandalonePage { com.kernelai.app.ui.screen.debugger.DebuggerScreen() } }
                                entry<Route.LogViewer> {
                                    StandalonePage {
                                    LogScreen(
                                        logEntries = logEntries,
                                        onClear = { driverViewModel.clearLogs() }
                                    )
                                    }
                                }
                            }
                        )
                    }

                    Box(Modifier.fillMaxSize()) { navDisplay() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(com.kernelai.app.ai.AgentTaskService.OPEN_CHAT, false)) requestedChat.value++
    }
}

/** Detail routes do not use the main pager's inset-aware scaffold. */
@Composable
private fun StandalonePage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
}

/**
 * 底部导航页面配置
 */
object MainPagerConfig {
    const val PAGE_COUNT = 4
    const val PAGE_CHAT = 0
    const val PAGE_PROCESS = 1
    const val PAGE_BREAKPOINT = 2
    const val PAGE_SETTINGS = 3
}

/**
 * 底部导航目标
 */
enum class BottomNavDestination(
    val label: String,
    val icon: ImageVector
) {
    Chat("聊天", Icons.Rounded.Chat),
    Process("进程", Icons.Rounded.Memory),
    Breakpoint("断点", Icons.Rounded.BugReport),
    Settings("设置", Icons.Rounded.Settings)
}

/**
 * 侧边导航目标
 */
enum class SideNavDestination(
    val label: String,
    val icon: ImageVector,
    val route: Route
) {
    Memory("内存查看", Icons.Rounded.ListAlt, Route.MemoryViewer),
    Disasm("反汇编", Icons.Rounded.Code, Route.DisasmViewer),
    Debugger("动态调试", Icons.Rounded.Code, Route.DebuggerViewer),
    Log("日志", Icons.Rounded.ListAlt, Route.LogViewer)
}

/**
 * 主屏幕 - 包含底部导航和侧边导航
 */
@Composable
private fun MainScreen(
    pagerState: PagerState,
    settingsScrollState: ScrollState,
    connectionStatus: com.kernelai.app.data.model.ConnectionStatus,
    appSettings: com.kernelai.app.data.model.AppSettings,
    logEntries: List<DriverViewModel.LogEntry>,
    driverViewModel: DriverViewModel,
    modelsViewModel: ModelsViewModel,
) {
    val navigator = LocalAppNavigator.current
    val uiMode = LocalUiMode.current
    val modelState by modelsViewModel.state.collectAsStateWithLifecycle()
    val selectedPage = pagerState.currentPage
    val scope = rememberCoroutineScope()
    fun selectPage(index: Int) {
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val density = androidx.compose.ui.platform.LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val bottomReserve = if (keyboardVisible) 0.dp else navigationBottom + if (isLandscape) 8.dp else 86.dp
    val background = MiuixTheme.colorScheme.background
    // Each page records its own content; a retained off-screen page cannot replace the chat backdrop.
    val backdrops = List(MainPagerConfig.PAGE_COUNT) {
        top.yukonga.miuix.kmp.blur.rememberLayerBackdrop { drawRect(background); drawContent() }
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState,
            modifier = Modifier.fillMaxSize().padding(start = if (isLandscape) 96.dp else 0.dp),
            beyondViewportPageCount = 1, userScrollEnabled = false) { page ->
            if (page == MainPagerConfig.PAGE_CHAT) {
                ChatScreen(connectionStatus = connectionStatus, aiConfig = appSettings.aiConfig,
                    modelCatalog = modelState.catalog, requireModel = true,
                    onManageModels = { navigator.push(Route.Models) }, onOpenPage = ::selectPage,
                    onOpenUtility = { name -> if (name == "工作区") navigator.push(Route.Workspace) else SideNavDestination.entries.firstOrNull { it.label == name }?.let { navigator.push(it.route) } },
                    glassBackdrop = backdrops[page], navigationInset = bottomReserve, modelLoading = modelState.loading)
            } else Box(Modifier.fillMaxSize().layerBackdrop(backdrops[page])
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(bottom = bottomReserve)) {
                when (page) {
                    MainPagerConfig.PAGE_PROCESS -> ProcessScreen()
                    MainPagerConfig.PAGE_BREAKPOINT -> BreakpointScreen()
                    MainPagerConfig.PAGE_SETTINGS -> SettingsScreen(scrollState = settingsScrollState,
                        connectionStatus = connectionStatus, appSettings = appSettings,
                        onManageModels = { navigator.push(Route.Models) },
                        onOpenAudit = { navigator.push(Route.McpAudit) },
                        onOpenWorkspace = { navigator.push(Route.Workspace) },
                        onThemeModeChange = driverViewModel::updateThemeMode,
                        onReconnectDriver = driverViewModel::reconnectDriver,
                        onToggleMcp = driverViewModel::toggleMcpServer)
                }
            }
        }
        if (!keyboardVisible) {
            if (isLandscape) com.kernelai.app.ui.components.GlassNavigation(backdrops[selectedPage], selectedPage, ::selectPage,
                Modifier.align(androidx.compose.ui.Alignment.CenterStart).padding(start = 10.dp), vertical = true)
            else com.kernelai.app.ui.components.GlassNavigation(backdrops[selectedPage], selectedPage, ::selectPage,
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(horizontal = 20.dp)
                    .padding(bottom = navigationBottom + 10.dp).widthIn(max = 440.dp).fillMaxWidth())
        }
    }
}
