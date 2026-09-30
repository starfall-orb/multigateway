package org.starfall.multigateway.ui.navigation

/** Stable route names are persisted by Navigation across Activity/process recreation. */
enum class AppDestination(val route: String) {
    CHAT("chat"),
    PROVIDERS("providers"),
    MCP("mcp"),
    SPEECH("speech"),
    SETTINGS("settings"),
    SYSTEM_TOOLS("system_tools"),
    STORAGE("storage"),
    MENU("menu"),
}
