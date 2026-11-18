package com.kernelai.app.ui.screen.disasm
import androidx.compose.runtime.Composable
import com.kernelai.app.ui.screen.memory.DeviceMemoryPanel
@Composable
fun DisasmScreen() { DeviceMemoryPanel(instructions = true) }
