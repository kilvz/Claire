package com.wangzan101.mika.core.safety.gate

/** Kinds of irreversible operations (shared by confirmation cards, gate results and declarations). */
@Suppress("EnumEntryName")
enum class IrrevAction { send, pay, delete, order, submit, other }

/**
 * What a tool declares as "what to judge irreversibility by": Tap the tapped element or coordinates; FileControl the tapped
 * file control (judged like a tap, but never a point obtained from looking at an image); Enter the Enter key pressed after
 * typing; Key a key press; Swipe a sideways swipe (swipe to delete); Interact key combos, drags and dropdown selects on a web
 * page.
 */
enum class GateTarget { Tap, FileControl, Enter, Key, Swipe, Interact }
