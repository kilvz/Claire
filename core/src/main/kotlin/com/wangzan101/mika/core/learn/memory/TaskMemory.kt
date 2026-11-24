package com.wangzan101.mika.core.learn.memory

import kotlinx.serialization.Serializable

/** One step on a single task's timeline: when it happened, what was done (one human-readable sentence), and the raw text read during that step (fetched separately on demand). */
@Serializable
data class TaskMemoryStep(
    val atMs: Long,
    val label: String,
    val detail: String,
)

/**
 * A cross-session summary of one task: the goal, a one-sentence conclusion, the apps touched (package names) and
 * websites (domains), and the steps on the timeline.
 * Written automatically when the task ends and persisted encrypted; retrieval is served in three layers: first the
 * index (compact entries of goal / conclusion / apps / sites), then the timeline (the steps' label), and finally the
 * raw text of a single step (detail). Apps are stored as package names so they can be filtered and traced reliably per
 * app.
 */
@Serializable
data class TaskMemory(
    val id: String,
    val goal: String,
    val conclusion: String,
    val apps: List<String>,
    val sites: List<String>,
    val createdAt: Long,
    val steps: List<TaskMemoryStep>,
)
