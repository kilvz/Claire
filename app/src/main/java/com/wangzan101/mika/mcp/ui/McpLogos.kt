package com.wangzan101.mika.mcp.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.wangzan101.mika.settings.ui.models.use

/**
 * A brand logo: several paths in a 24×24 viewport (`d` is the legacy single-path form), the drawing color, and the fill rule.
 * [tile] is for brand colors that are too bright: then it serves as the background color and the drawing uses a dark color; when null, the default light background is used.
 */
@Serializable
data class McpLogoData(
    val color: String,
    val tile: String? = null,
    val paths: List<String> = emptyList(),
    val d: String = "",
    val evenOdd: Boolean = false,
) {
    val allPaths: List<String> get() = (paths + d).filter { it.isNotBlank() }
}

@Serializable
private data class McpLogosDto(val formatVersion: Int, val logos: Map<String, McpLogoData>)

/**
 * Brand logo assets: parses `assets/mcp/mcp-logos.json` and lazily turns each brand's paths + official brand color into
 * a self-tinted [ImageVector] (no image loading library). `logo(id)` returns null on a miss, and the caller falls back to the category icon.
 */
class McpLogos private constructor(private val logos: Map<String, McpLogoData>) {
    private val cache = mutableMapOf<String, ImageVector?>()

    /** The background color when a brand logo exists (the brand's own light backdrop if it has one, otherwise the default light background); null when there is no brand logo. */
    fun tile(id: String): Color? {
        val data = logos[id] ?: return null
        return data.tile?.let(::parseColor) ?: DefaultTile
    }

    fun logo(id: String): ImageVector? {
        val data = logos[id] ?: return null
        return cache.getOrPut(id) { runCatching { vector(data) }.getOrNull() }
    }

    private fun vector(data: McpLogoData): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val fill = SolidColor(parseColor(data.color))
            val rule = if (data.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
            data.allPaths.forEach { d ->
                addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = fill, pathFillType = rule)
            }
        }.build()

    companion object {
        const val ASSET_PATH = "mcp/mcp-logos.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val hex = Regex("^#?[0-9A-Fa-f]{6}$")

        /** Default backdrop for brand logos: a fixed light color in both modes, so dark brand marks stay readable in dark mode (the generator computes contrast against it). */
        val DefaultTile = Color(0xFFF4F4F5)

        /** Lenient parsing: returns empty if the whole payload is corrupted; skips individual entries that have no path or an invalid color. */
        fun parse(text: String): McpLogos = try {
            val dto = json.decodeFromString<McpLogosDto>(text)
            McpLogos(dto.logos.filterValues { it.allPaths.isNotEmpty() && hex.matches(it.color) && (it.tile == null || hex.matches(it.tile)) })
        } catch (e: Exception) {
            McpLogos(emptyMap())
        }

        fun load(context: Context): McpLogos = try {
            parse(context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (e: Exception) {
            McpLogos(emptyMap())
        }

        private fun parseColor(c: String): Color = Color(0xFF000000L or c.removePrefix("#").toLong(16))
    }
}
