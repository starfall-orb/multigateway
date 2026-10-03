package org.starfall.multigateway.ui.tools

import androidx.compose.runtime.staticCompositionLocalOf
import org.starfall.multigateway.data.model.*

data class ToolControls(
    val servers: List<McpInfo> = emptyList(),
    val settings: ToolSettings = ToolSettings(),
    val providers: List<LlmProviderInfo> = emptyList(),
    val setSystem: (String,SystemToolConfig)->Unit = {_,_->},
    val setMcp: (String,Boolean)->Unit = {_,_->}
)
val LocalToolControls = staticCompositionLocalOf { ToolControls() }
