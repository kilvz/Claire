package com.wangzan101.mika.core.learn.knowledge

enum class Surface { Android, Web, Desktop }

/** One piece of knowledge for an app (APP.md, [Surface.Android]) or a site (SITE.md, [Surface.Web]). */
data class KnowledgeEntry(
    val name: String,
    val display: String,
    val surface: Surface,
    val packages: List<String>,
    val domains: List<String>,
    val aliases: List<String>,
    val open: String?,
    val description: String,
    val body: String?,
    /** When the same app has multiple package names, the mapping from each alias to its package (e.g. “Meituan Waimai” → the delivery service's standalone package). */
    val packageAliases: Map<String, String> = emptyMap(),
    /** Pages verified on a real device and reachable directly with open_link; the header writes `links: ["name | uri", "name | uri | package", …]`. */
    val links: List<Link> = emptyList(),
    /** The name of the same service's entry on its other surface: an app entry names its site, a site entry names its app. */
    val related: List<String> = emptyList(),
    /** Declarations of site commands (sites only); the script body is fetched by [GuideCatalog.siteCommands]. */
    val commands: List<SiteCommandDecl> = emptyList(),
    /** The body carries a trial draft of app knowledge self-learned by Claire that has not been verified yet. */
    val trial: Boolean = false,
) {
    /** The name shared by an app and a site of the same service: when the site lists an app, the app's name is taken. */
    val service: String get() = if (surface == Surface.Web) related.firstOrNull() ?: name else name
}

/** A directly reachable link; a web link must carry [packageName] (which app opens it). */
data class Link(val name: String, val uri: String, val packageName: String? = null)
