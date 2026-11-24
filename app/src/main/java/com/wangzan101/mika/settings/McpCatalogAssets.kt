package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpCatalogParser

/**
 * MCP server catalog bundled with the app: reads `assets/mcp/catalog.json`, parsed leniently (a single broken entry is skipped and does not block the remaining entries).
 * The catalog contains only declarative, secret-free presets; secrets are entered by the user only when connecting and delivered via encrypted storage.
 */
object McpCatalogAssets {
    const val ASSET_PATH = "mcp/catalog.json"

    /** UI languages the catalog text must cover; server-specific names are not translated. */
    val REQUIRED_LOCALES: List<String> = listOf("en", "zh", "zh-Hant", "ar", "fa", "ja", "ko", "es", "pt", "ru", "id", "tr")

    fun load(context: Context): McpCatalog = parse(
        context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) },
    )

    /** Lenient parsing; individual broken presets are skipped, while catalog-level errors are still thrown. */
    fun parse(text: String): McpCatalog = McpCatalogParser.parseLenient(text)
}
