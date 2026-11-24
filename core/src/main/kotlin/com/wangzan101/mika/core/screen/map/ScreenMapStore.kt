package com.wangzan101.mika.core.screen.map

/** Storage port for screen maps. A read/write failure equals "no map"; the caller only counts it and the task is not affected (design D8). */
interface ScreenMapStore {
    fun load(app: String): ScreenGraph?
    fun save(app: String, graph: ScreenGraph)
    fun apps(): List<String>
}
