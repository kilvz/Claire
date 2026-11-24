package com.wangzan101.mika.core.learn.memory

private var fixtureSeq = 0

internal fun mem(
    content: String,
    kind: MemoryKind = MemoryKind.fact,
    keywords: List<String> = emptyList(),
    status: MemoryStatus = MemoryStatus.active,
    pinned: Boolean = false,
    id: String = "m${++fixtureSeq}",
) = MemoryItem(
    id = id, kind = kind, content = content, keywords = keywords,
    source = MemorySource(type = "manual", atMs = 0L), status = status, pinned = pinned,
    createdAt = 0L, updatedAt = 0L,
)

/** Unrelated filler entries so the retrieval IDF is close to a realistic scale. */
internal fun distractors(n: Int) = (1..n).map { mem("无关事实第${it}条:用户周${it % 7}去健身房锻炼身体") }
