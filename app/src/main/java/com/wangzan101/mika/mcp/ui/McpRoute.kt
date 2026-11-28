package com.wangzan101.mika.mcp.ui

/** Layers of the MCP servers page: [List] is the page home, the rest are the service catalog, guided connection, service management and custom servers. */
internal enum class McpRoute { List, Catalog, Connect, Manage, Custom }

/**
 * Returns to the previous layer. The top bar's back button and the system back key both follow this table; [List] returns null, meaning back leaves this page.
 * [customFrom] records which layer (list or catalog) opened "Custom server": back returns there without skipping a layer.
 */
internal fun McpRoute.up(customFrom: McpRoute): McpRoute? = when (this) {
    McpRoute.List -> null
    McpRoute.Catalog -> McpRoute.List
    McpRoute.Connect -> McpRoute.Catalog
    McpRoute.Manage -> McpRoute.List
    McpRoute.Custom -> customFrom
}
