package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** The search engines available in the browser: the currently selected one and the ones the user added. */
data class SearchEngineChoices(val selected: SearchEngineSpec, val custom: List<CustomSearchEngine>) {
    /** Everything in the choice list: built-in engines first, custom ones after. */
    val all: List<SearchEngineSpec> get() = SearchEngine.entries + custom
}

/**
 * The search engine the user selected on the start page and the custom search engines; it stays this way next time.
 * Stored in a JSON file in the app's private directory; when the file is missing, corrupted or unrecognizable, [SearchEngine.Bing] is used with no custom engines.
 */
class SearchEngineStore(dir: File) {
    private val file = File(dir, FILE)
    private val _choices = MutableStateFlow(load())

    val choices: StateFlow<SearchEngineChoices> = _choices.asStateFlow()

    @Synchronized
    fun set(engine: SearchEngineSpec) {
        val c = _choices.value
        if (c.selected.id == engine.id) return
        _choices.value = c.copy(selected = engine)
        save(_choices.value)
    }

    /** Adds a custom search engine and switches to it; does nothing when the same id already exists. */
    @Synchronized
    fun addCustom(engine: CustomSearchEngine) {
        val c = _choices.value
        if (c.custom.any { it.id == engine.id }) return
        _choices.value = SearchEngineChoices(engine, c.custom + engine)
        save(_choices.value)
    }

    /** Deletes a custom search engine; when the deleted one is the currently selected one, switches back to Bing. */
    @Synchronized
    fun removeCustom(id: String) {
        val c = _choices.value
        val rest = c.custom.filterNot { it.id == id }
        if (rest.size == c.custom.size) return
        val selected = if (c.selected.id == id) SearchEngine.Bing else c.selected
        _choices.value = SearchEngineChoices(selected, rest)
        save(_choices.value)
    }

    private fun load(): SearchEngineChoices {
        if (!file.isFile) return SearchEngineChoices(SearchEngine.Bing, emptyList())
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return@runCatching null
            val custom = (root["custom"] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val template = (o["template"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                CustomSearchEngine.create(id, name, template)
            }.orEmpty().distinctBy { it.id }
            val selectedId = (root["selected"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            SearchEngineChoices(custom.firstOrNull { it.id == selectedId } ?: SearchEngine.fromId(selectedId) ?: SearchEngine.Bing, custom)
        }.getOrNull() ?: SearchEngineChoices(SearchEngine.Bing, emptyList())
    }

    private fun save(choices: SearchEngineChoices) {
        runCatching {
            file.parentFile?.mkdirs()
            val json = buildJsonObject {
                put("selected", choices.selected.id)
                put(
                    "custom",
                    JsonArray(
                        choices.custom.map { e ->
                            buildJsonObject {
                                put("id", e.id)
                                put("name", e.displayName)
                                put("template", e.template)
                            }
                        },
                    ),
                )
            }.toString()
            // Write a temporary file first and then rename it, so being killed halfway cannot leave half a file
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE = "browser_search_engines.json"
    }
}
