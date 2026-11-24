package com.wangzan101.mika.core.screen.map

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where one action took the map: changed screens / same screen with a state change / unchanged / jumped to another app / unknown. */
enum class TransitionEffect { NAVIGATED, STATE_CHANGED, NONE, LEFT_APP, UNKNOWN }

/**
 * One click navigation edge: tapping [action] on some screen (target element [target]) → arriving at screen [to].
 * [count] is how many times it worked through, [offTrack] how many times it did not reach the expected screen. The same edge is
 * deduplicated by [key].
 */
@Serializable
data class Transition(
    val action: String,
    val target: String?,
    val effect: TransitionEffect,
    val to: String?,
    val count: Int = 0,
    val offTrack: Int = 0,
) {
    /** Dedup key: the same (action, target, effect, destination screen) is one edge; counts and off-tracks are tracked separately. */
    fun key(): String = "$action\u0000${target ?: ""}\u0000${effect.name}\u0000${to ?: ""}"
}

/**
 * One structural page (a screen). Its identity is described by its skeleton anchors [anchors]; the name/level fields are left
 * empty in this version, for labeling later.
 * Keys of [transitions] are [Transition.key] and values are the edge.
 */
@Serializable
data class ScreenNode(
    val id: String,
    val app: String,
    val name: String = "",
    val anchors: Map<String, Int> = emptyMap(),
    val levels: Map<String, Int> = emptyMap(),
    val transitions: Map<String, Transition> = emptyMap(),
    val visits: Int = 0,
    val status: String = "provisional",
)

/** Deterministic screen id: the same (app, skeleton signature) always gives the same id, so a full rebuild is byte-identical. */
object ScreenId {
    fun of(app: String, signature: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((app + "\u0000" + signature).toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Skeleton anchor signature: stable skeleton texts deduplicated, sorted and joined. Map building and self-checks share the same definition. */
    fun signature(texts: List<String>): String = texts.distinct().sorted().joinToString("\u0000")
}

object ScreenNodeCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(n: ScreenNode): String = json.encodeToString(ScreenNode.serializer(), n)
    fun decode(s: String): ScreenNode = json.decodeFromString(ScreenNode.serializer(), s)
}
