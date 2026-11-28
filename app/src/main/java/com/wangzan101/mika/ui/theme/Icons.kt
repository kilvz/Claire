package com.wangzan101.mika.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

/**
 * Self-drawn line icons: a 24 viewport, displayed at 22dp by default, stroke width 1.7, round caps and joins. The color is supplied by Icon's tint.
 * They are all constants inside an object and are built only once; a few that must stay legible at small sizes (check, send, arrows, close) get a slightly thicker stroke.
 */
object MikaIcons {
    private fun stroke(name: String, vararg paths: String, width: Float = MikaSize.IconStroke): ImageVector =
        ImageVector.Builder(name, MikaSize.Icon, MikaSize.Icon, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun filled(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, MikaSize.Icon, MikaSize.Icon, 24f, 24f).apply {
            paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val Menu = stroke("menu", "M4 7 h16 M4 12 h16 M4 17 h10")
    /** New chat: a pen. */
    val New = stroke("new", "M12 20 h8", "M16.5 3.5 a2.1 2.1 0 0 1 3 3 L7 19 l-4 1 l1 -4 z")
    /** Rename: a slanted pen with a band behind the nib (unlike "new chat", with no line underneath). */
    val Edit = stroke("edit", "M16.5 3.5 a2.1 2.1 0 0 1 3 3 L7 19 l-4 1 l1 -4 z", "M14.5 5.5 l4 4")
    val Back = stroke("back", "M15 5 l-7 7 l7 7")
    val Forward = stroke("forward", "M9 5 l7 7 l-7 7")
    val Chevron = stroke("chevron", "M9 6 l6 6 l-6 6", width = 2f)
    val Plus = stroke("plus", "M12 5 v14 M5 12 h14")
    /** Send. */
    val Up = stroke("up", "M12 19 V5 M6 11 l6 -6 l6 6", width = 2.2f)
    /** Back to the bottom. */
    val Down = stroke("down", "M12 5 V19 M6 13 l6 6 l6 -6", width = 2.2f)
    val Check = stroke("check", "M5 12.5 l4.5 4.5 L19 7.5", width = 2.2f)
    val Close = stroke("close", "M6 6 l12 12 M18 6 L6 18", width = 2f)
    /** Multi-select: a checked checklist. */
    val ListChecks = stroke("list-checks", "M3 17 l2 2 l4 -4", "M3 7 l2 2 l4 -4", "M13 6 h8 M13 12 h8 M13 18 h8")
    /** Add to list: a plus sign to the right of a list. */
    val ListPlus = stroke("list-plus", "M11 12 H3 M16 6 H3 M16 18 H3", "M18 9 v6 M21 12 h-6")
    /** Quit completely: the power button. */
    val Power = stroke("power", "M12 3 v8", "M18.36 6.64 a8 8 0 1 1 -12.73 0")
    val Search = stroke("search", "M4 11 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0", "M20 20 l-4 -4")
    /** Refresh: a circle that almost closes, with an arrow at the top right. */
    val Refresh = stroke("refresh", "M19.5 12 a7.5 7.5 0 1 1 -2.2 -5.3", "M19.5 4.5 v4 h-4")
    val Trash = stroke("trash", "M4 7 h16 M9 7 V4.5 h6 V7", "M6.5 7 l1 13 h9 l1 -13")
    /** Speech to text: a microphone. */
    val Mic = stroke("mic", "M9 6 a3 3 0 0 1 6 0 v5 a3 3 0 0 1 -6 0 z", "M5 11 a7 7 0 0 0 14 0", "M12 18 v3")
    /** Microphone off: a microphone with a diagonal slash (call muted). */
    val MicOff = stroke("mic-off", "M9 6 a3 3 0 0 1 6 0 v5 a3 3 0 0 1 -6 0 z", "M5 11 a7 7 0 0 0 14 0", "M12 18 v3", "M4 4 l16 16")
    /** Hang up: a handset lying horizontally. */
    val HangUp = stroke(
        "hang-up",
        "M3.5 14.5 c0 -3 3.8 -5.5 8.5 -5.5 s8.5 2.5 8.5 5.5 l-0.3 1.3 a1 1 0 0 1 -1.2 0.7 l-3 -0.8 a1 1 0 0 1 -0.7 -1 v-1.6 " +
            "c-2.1 -0.7 -4.5 -0.7 -6.6 0 v1.6 a1 1 0 0 1 -0.7 1 l-3 0.8 a1 1 0 0 1 -1.2 -0.7 z",
    )
    /** Keyboard: switch to typing instead. */
    val Keyboard = stroke(
        "keyboard", "M5.5 6 h13 a2.5 2.5 0 0 1 2.5 2.5 v7 a2.5 2.5 0 0 1 -2.5 2.5 h-13 a2.5 2.5 0 0 1 -2.5 -2.5 v-7 a2.5 2.5 0 0 1 2.5 -2.5 z",
        "M7 10 h.01 M10.3 10 h.01 M13.7 10 h.01 M17 10 h.01 M8 14.5 h8",
    )
    /** Lightweight model: a leaf. */
    val Leaf = stroke("leaf", "M5 19 c0 -8 5 -13 14 -14 c0 9 -5 14 -13 14", "M5 19 l7 -7")
    val Apps = stroke(
        "apps",
        "M5.8 4 h2.9 a1.8 1.8 0 0 1 1.8 1.8 v2.9 a1.8 1.8 0 0 1 -1.8 1.8 h-2.9 a1.8 1.8 0 0 1 -1.8 -1.8 v-2.9 a1.8 1.8 0 0 1 1.8 -1.8 z",
        "M15.3 4 h2.9 a1.8 1.8 0 0 1 1.8 1.8 v2.9 a1.8 1.8 0 0 1 -1.8 1.8 h-2.9 a1.8 1.8 0 0 1 -1.8 -1.8 v-2.9 a1.8 1.8 0 0 1 1.8 -1.8 z",
        "M5.8 13.5 h2.9 a1.8 1.8 0 0 1 1.8 1.8 v2.9 a1.8 1.8 0 0 1 -1.8 1.8 h-2.9 a1.8 1.8 0 0 1 -1.8 -1.8 v-2.9 a1.8 1.8 0 0 1 1.8 -1.8 z",
        "M15.3 13.5 h2.9 a1.8 1.8 0 0 1 1.8 1.8 v2.9 a1.8 1.8 0 0 1 -1.8 1.8 h-2.9 a1.8 1.8 0 0 1 -1.8 -1.8 v-2.9 a1.8 1.8 0 0 1 1.8 -1.8 z",
    )
    val Globe = stroke(
        "globe", "M3 12 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0",
        "M3 12 h18 M12 3 c2.5 2.6 3.8 5.6 3.8 9 s-1.3 6.4 -3.8 9 c-2.5 -2.6 -3.8 -5.6 -3.8 -9 s1.3 -6.4 3.8 -9 z",
    )
    val Doc = stroke(
        "doc", "M14 3 H7 a2 2 0 0 0 -2 2 v14 a2 2 0 0 0 2 2 h10 a2 2 0 0 0 2 -2 V8 z", "M14 3 v5 h5 M9 13 h6 M9 17 h4",
    )
    /** Folder: device directory. */
    val Folder = stroke(
        "folder",
        "M3.5 6.5 a2 2 0 0 1 2 -2 h4 l2 2.5 h7 a2 2 0 0 1 2 2 v8.5 a2 2 0 0 1 -2 2 h-13 a2 2 0 0 1 -2 -2 z",
    )
    /** Bar chart: token usage. */
    val Chart = stroke("chart", "M4 20 h16", "M7 16 v-4 M12 16 V6 M17 16 v-7")
    val Clock = stroke("clock", "M3 12 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0", "M12 7 v5 l3 2")
    /** Calendar: pick a date. */
    val Calendar = stroke(
        "calendar",
        "M5 5.5 h14 a2 2 0 0 1 2 2 v12 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 v-12 a2 2 0 0 1 2 -2 z",
        "M3 10.5 h18 M8 3.5 v4 M16 3.5 v4",
    )
    val Alarm = stroke(
        "alarm", "M5 13 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0", "M12 10 v3 l2 1.5 M5 4 L2.5 6.5 M19 4 l2.5 2.5",
    )
    val Message = stroke("message", "M21 12 a8 8 0 0 1 -11.6 7.1 L4 20 l1 -4.6 A8 8 0 1 1 21 12 z")
    /** Person: following someone, doing something to someone. */
    val Person = stroke(
        "person", "M8.5 8 a3.5 3.5 0 1 0 7 0 a3.5 3.5 0 1 0 -7 0",
        "M4.5 20 a7.5 7.5 0 0 1 15 0",
    )
    val Heart = stroke(
        "heart", "M12 20.5 s-8 -4.6 -8 -10.4 A4.6 4.6 0 0 1 12 7 a4.6 4.6 0 0 1 8 3.1 c0 5.8 -8 10.4 -8 10.4 z",
    )
    val Gear = stroke(
        "gear", "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        "M19.4 15 a1.7 1.7 0 0 0 0.3 1.8 l0.1 0.1 a2 2 0 1 1 -2.8 2.8 l-0.1 -0.1 a1.7 1.7 0 0 0 -1.8 -0.3 " +
            "a1.7 1.7 0 0 0 -1 1.5 V21 a2 2 0 1 1 -4 0 v-0.1 a1.7 1.7 0 0 0 -1.1 -1.6 a1.7 1.7 0 0 0 -1.8 0.3 " +
            "l-0.1 0.1 a2 2 0 1 1 -2.8 -2.8 l0.1 -0.1 a1.7 1.7 0 0 0 0.3 -1.8 a1.7 1.7 0 0 0 -1.5 -1 H3 " +
            "a2 2 0 1 1 0 -4 h0.1 a1.7 1.7 0 0 0 1.6 -1.1 a1.7 1.7 0 0 0 -0.3 -1.8 l-0.1 -0.1 a2 2 0 1 1 2.8 -2.8 " +
            "l0.1 0.1 a1.7 1.7 0 0 0 1.8 0.3 H9 a1.7 1.7 0 0 0 1 -1.5 V3 a2 2 0 1 1 4 0 v0.1 a1.7 1.7 0 0 0 1 1.5 " +
            "a1.7 1.7 0 0 0 1.8 -0.3 l0.1 -0.1 a2 2 0 1 1 2.8 2.8 l-0.1 0.1 a1.7 1.7 0 0 0 -0.3 1.8 V9 " +
            "a1.7 1.7 0 0 0 1.5 1 H21 a2 2 0 1 1 0 4 h-0.1 a1.7 1.7 0 0 0 -1.5 1 z",
    )
    /** Sparkle: models. */
    val Spark = stroke(
        "spark", "M12 3 l1.9 5.6 L19.5 10.5 L13.9 12.4 L12 18 l-1.9 -5.6 L4.5 10.5 l5.6 -1.9 z",
        "M19 17 l0.7 2 l2 0.7 l-2 0.7 L19 22.4 l-0.7 -2 l-2 -0.7 l2 -0.7 z",
    )
    /** Camera: body plus lens (take a photo with the system camera). */
    val PhotoCamera = stroke(
        "photo-camera", "M4 8 h3 l2 -2.5 h6 l2 2.5 h3 v11 H4 z", "M8.4 13 a3.6 3.6 0 1 0 7.2 0 a3.6 3.6 0 1 0 -7.2 0",
    )
    /** Photo: a mountain and a sun inside a frame. */
    val Photo = stroke(
        "photo",
        "M6 4.5 h12 a2.5 2.5 0 0 1 2.5 2.5 v10 a2.5 2.5 0 0 1 -2.5 2.5 h-12 a2.5 2.5 0 0 1 -2.5 -2.5 v-10 a2.5 2.5 0 0 1 2.5 -2.5 z",
        "M7.4 10 a1.6 1.6 0 1 0 3.2 0 a1.6 1.6 0 1 0 -3.2 0", "M20.5 16 l-5 -5 l-8 8.5",
    )
    /** A stack of photos: the corner of a second one peeking behind (there are more photos). */
    val Photos = stroke(
        "photos",
        "M8.5 4.5 h8 a2.5 2.5 0 0 1 2.5 2.5 v8.5",
        "M7 7.5 h7.5 a2.5 2.5 0 0 1 2.5 2.5 v7 a2.5 2.5 0 0 1 -2.5 2.5 h-7.5 a2.5 2.5 0 0 1 -2.5 -2.5 v-7 a2.5 2.5 0 0 1 2.5 -2.5 z",
        "M6.8 16.3 l2.4 -2.4 l1.8 1.8 l2.2 -2.2 l1.9 1.9",
    )
    /** Screenshot: a screen with a bar at the top and a circle in the middle (captures the screen at this moment). */
    val Camera = stroke(
        "camera",
        "M6 4 h12 a3 3 0 0 1 3 3 v10 a3 3 0 0 1 -3 3 h-12 a3 3 0 0 1 -3 -3 v-10 a3 3 0 0 1 3 -3 z",
        "M3 9 h18 M8 4 v5",
        "M9.4 14.5 a2.6 2.6 0 1 0 5.2 0 a2.6 2.6 0 1 0 -5.2 0",
    )
    val Phone = stroke(
        "phone",
        "M9 2.5 h6 a2.5 2.5 0 0 1 2.5 2.5 v14 a2.5 2.5 0 0 1 -2.5 2.5 h-6 a2.5 2.5 0 0 1 -2.5 -2.5 v-14 a2.5 2.5 0 0 1 2.5 -2.5 z",
        "M11 18.5 h2",
    )
    /** Handset: start a voice call (distinct from "phone", so it reads as a call at a glance). */
    val Handset = stroke(
        "handset",
        // The handset bulges to the lower left with the earpiece at the upper left, matching the standard phone icon's orientation (the original path was rotated 180° about its center).
        "M17.61 18.83 A9.1 9.1 0 0 1 5.18 6.4 A2.5 2.5 0 0 1 7.68 10.73 A4.1 4.1 0 0 0 13.28 16.33 A2.5 2.5 0 0 1 17.61 18.83 Z",
    )
    val Moon = stroke("moon", "M20 14.5 A8 8 0 0 1 9.5 4 a8 8 0 1 0 10.5 10.5 z")
    /** A dot with two rings of waves: keeping an eye on what's happening around (proactive capability). */
    val Ripple = stroke(
        "ripple", "M11.4 12 a0.6 0.6 0 1 0 1.2 0 a0.6 0.6 0 1 0 -1.2 0",
        "M8.6 8.6 a4.8 4.8 0 0 0 0 6.8 M15.4 8.6 a4.8 4.8 0 0 1 0 6.8",
        "M5.6 5.6 a9 9 0 0 0 0 12.8 M18.4 5.6 a9 9 0 0 1 0 12.8",
    )
    val Bell = stroke("bell", "M18 16 V11 a6 6 0 1 0 -12 0 v5 l-2 2 h16 z M10 20.5 a2 2 0 0 0 4 0")
    val Key = stroke("key", "M4 15 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0", "M10.8 12.2 L20 3 M17 6 l3 3 M14.5 8.5 l2 2")
    val Terminal = stroke(
        "terminal",
        "M5.5 4 h13 a2.5 2.5 0 0 1 2.5 2.5 v11 a2.5 2.5 0 0 1 -2.5 2.5 h-13 a2.5 2.5 0 0 1 -2.5 -2.5 v-11 a2.5 2.5 0 0 1 2.5 -2.5 z",
        "M7 9 l3 3 l-3 3 M13 15 h4",
    )
    /** Route: a remembered sequence of actions. */
    val Route = stroke(
        "route", "M3.5 18 a2.5 2.5 0 1 0 5 0 a2.5 2.5 0 1 0 -5 0", "M15.5 6 a2.5 2.5 0 1 0 5 0 a2.5 2.5 0 1 0 -5 0",
        "M8.5 18 H15 a3 3 0 0 0 0 -6 H9 a3 3 0 0 1 0 -6 h6.5",
    )
    /** Wrench: restore and repair. */
    val Wrench = stroke(
        "wrench", "M14.7 6.3 a4 4 0 0 0 -5.4 5.4 L3.5 17.5 l3 3 l5.8 -5.8 a4 4 0 0 0 5.4 -5.4 l-2.6 2.6 l-2.4 -0.6 l-0.6 -2.4 z",
    )
    val Code = stroke("code", "M8 7 l-5 5 l5 5 M16 7 l5 5 l-5 5")
    /** Several screens stacked: virtual screens that are running. */
    val Screens = stroke(
        "screens",
        "M3.5 5.5 h13 a1.5 1.5 0 0 1 1.5 1.5 v7 a1.5 1.5 0 0 1 -1.5 1.5 h-13 a1.5 1.5 0 0 1 -1.5 -1.5 v-7 a1.5 1.5 0 0 1 1.5 -1.5 z",
        "M7.5 19 h9 M12 15.5 v3",
        "M19.5 8.5 h1 a1.5 1.5 0 0 1 1.5 1.5 v7 a1.5 1.5 0 0 1 -1.5 1.5 h-1",
    )
    val Info = stroke("info", "M3 12 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0", "M12 11 v5.5 M12 7.8 v0.2")

    // About page: update history, report an issue, chat (contact the author), X, documentation
    val Lines = stroke("lines", "M9 7 h11 M9 12 h11 M9 17 h11", "M4.5 7 h0.01 M4.5 12 h0.01 M4.5 17 h0.01", width = 2f)
    val Bug = stroke(
        "bug", "M8 9 h8 v6 a4 4 0 0 1 -8 0 z",
        "M9 9 a3 3 0 0 1 6 0 M4 13 h4 M16 13 h4 M5 8 l3 2 M19 8 l-3 2 M5 19 l3 -2 M19 19 l-3 -2",
    )
    val Chats = stroke(
        "chats", "M9.5 5 C5.9 5 3 7.4 3 10.4 c0 1.7 0.9 3.2 2.4 4.2 L5 17 l2.6 -1.4 c0.6 0.2 1.2 0.3 1.9 0.3",
        "M21 14.5 c0 -2.6 -2.5 -4.7 -5.6 -4.7 s-5.6 2.1 -5.6 4.7 s2.5 4.7 5.6 4.7 c0.6 0 1.2 -0.1 1.7 -0.2 L19.5 20 l-0.4 -2 c1.2 -0.9 1.9 -2.1 1.9 -3.5 z",
    )
    /** Paper plane: Telegram. */
    val Telegram = stroke(
        "telegram", "M22 2 L11 13",
        "M22 2 L15 22 L11 13 L2 9 L22 2",
        width = 2f,
    )
    val XMark = stroke("x-mark", "M4.5 4.5 l15 15 M19.5 4.5 l-6.2 6.9 M10.7 13.6 L4.5 19.5")
    val Book = stroke("book", "M5 4.5 h6 a2 2 0 0 1 2 2 V20 a1.5 1.5 0 0 0 -1.5 -1.5 H5 z", "M19 4.5 h-6 M19 4.5 v14 h-5.5")
    /** Paperclip: attachments. */
    val Paperclip = stroke(
        "paperclip", "M21 11.5 l-8.5 8.5 a5.4 5.4 0 0 1 -7.6 -7.6 L13.4 4 a3.6 3.6 0 0 1 5.1 5.1 L10 17.6 a1.8 1.8 0 0 1 -2.6 -2.6 L15.3 7",
    )
    val Eye = stroke("eye", "M2 12 s3.6 -7 10 -7 s10 7 10 7 s-3.6 7 -10 7 S2 12 2 12 z", "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0")
    /** Eye with a diagonal slash: hidden (turn the small preview off). */
    val EyeOff = stroke("eye-off", "M2 12 s3.6 -7 10 -7 s10 7 10 7 s-3.6 7 -10 7 S2 12 2 12 z", "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0", "M4 4 L20 20")
    val Link = stroke(
        "link",
        "M10 14 a4.5 4.5 0 0 0 6.4 0 l3 -3 a4.5 4.5 0 0 0 -6.4 -6.4 L12 5.6 M14 10 a4.5 4.5 0 0 0 -6.4 0 l-3 3 a4.5 4.5 0 0 0 6.4 6.4 l1 -1",
    )
    /** Share: an arrow pointing up out of a box. */
    val Share = stroke("share", "M12 15 V3.5 M7.5 8 L12 3.5 L16.5 8", "M8 11 H6 v9 h12 v-9 h-2")
    /** Download: a downward arrow dropping into a tray. */
    val Download = stroke("download", "M12 4 v11", "M7 10.5 l5 5 l5 -5", "M5 19.5 h14")
    /** Open with another app: a diagonal arrow out of a box. */
    val External = stroke("external", "M14 4 h6 v6 M20 4 l-9 9", "M18 14 v5 a1 1 0 0 1 -1 1 H5 a1 1 0 0 1 -1 -1 V7 a1 1 0 0 1 1 -1 h5")
    /** House: the browser's start page. */
    val Home = stroke("home", "M4 11 l8 -6.5 l8 6.5 M6.5 9.5 V19 h11 V9.5")
    /** Three dots: more. */
    val More = stroke(
        "more", "M4.7 12 a1.3 1.3 0 1 0 2.6 0 a1.3 1.3 0 1 0 -2.6 0", "M10.7 12 a1.3 1.3 0 1 0 2.6 0 a1.3 1.3 0 1 0 -2.6 0",
        "M16.7 12 a1.3 1.3 0 1 0 2.6 0 a1.3 1.3 0 1 0 -2.6 0",
    )
    /** Lock: a page on an encrypted connection. */
    val Lock = stroke(
        "lock", "M7.5 10.5 h9 a2 2 0 0 1 2 2 v5 a2 2 0 0 1 -2 2 h-9 a2 2 0 0 1 -2 -2 v-5 a2 2 0 0 1 2 -2 z",
        "M8.5 10.5 V8 a3.5 3.5 0 0 1 7 0 v2.5", width = 2.2f,
    )
    val Copy = stroke(
        "copy", "M10 8 h7 a2 2 0 0 1 2 2 v7 a2 2 0 0 1 -2 2 h-7 a2 2 0 0 1 -2 -2 v-7 a2 2 0 0 1 2 -2 z", "M5 15 V6 a1 1 0 0 1 1 -1 h9",
    )
    /** Quote: an arrow pointing back (replying to the item above). */
    val Quote = stroke("quote", "M9.5 6.5 L4.5 11.5 l5 5", "M4.5 11.5 h9 a5.5 5.5 0 0 1 5.5 5.5 v1")
    /** Steer the current task: a curved arrow turning in to the right. */
    val Steer = stroke("steer", "M15 4 l5 5 -5 5", "M20 9 H10 a6 6 0 0 0 -6 6 v5")
    /** Select text: a text cursor. */
    val SelectText = stroke("select-text", "M9 4.5 h6 M9 19.5 h6 M12 4.5 v15")
    /** Pin: a pinned conversation. */
    val Pin = stroke("pin", "M9 3.5 h6 M10 3.5 v5.5 l-3 3.5 h10 l-3 -3.5 V3.5", "M12 12.5 V20.5")
    /** Archive: a small box with a lid and a slot under the lid for taking things out. */
    val Archive = stroke(
        "archive", "M4 7 h16 v3 h-16 z", "M6 10 v9 a1 1 0 0 0 1 1 h10 a1 1 0 0 0 1 -1 v-9", "M10 14 h4",
    )
    /** Star: frequently used sites. */
    val Star = stroke("star", "M12 4.5 l2.3 4.7 l5.2 0.8 l-3.8 3.6 l0.9 5.1 L12 16.3 l-4.6 2.4 l0.9 -5.1 L4.5 10 l5.2 -0.8 z")
    /** Bookmark: a V-shaped notch at the bottom. */
    val Bookmark = stroke("bookmark", "M7 4.5 h10 v15 l-5 -3.5 l-5 3.5 z")
    /** Desktop monitor: desktop version of a page. */
    val Desktop = stroke("desktop", "M5 5 h14 a1.5 1.5 0 0 1 1.5 1.5 v8 a1.5 1.5 0 0 1 -1.5 1.5 H5 a1.5 1.5 0 0 1 -1.5 -1.5 v-8 A1.5 1.5 0 0 1 5 5 z", "M9 20 h6 M12 16 v4")
    val Shield = stroke("shield", "M12 3 l7.5 3 v5.5 c0 4.6 -3.2 8.4 -7.5 9.5 c-4.3 -1.1 -7.5 -4.9 -7.5 -9.5 V6 z")
    /** Hand: accessibility. */
    val Hand = stroke(
        "hand",
        "M8.4 12 V5.4 a1.44 1.44 0 0 1 2.88 0 V10.8 m0 -5.4 v-1.2 a1.44 1.44 0 0 1 2.88 0 V10.8 m0 -4.2 a1.44 1.44 0 0 1 2.88 0 " +
            "V14.4 a6 6 0 0 1 -6 6 h-0.6 a5.4 5.4 0 0 1 -4.32 -2.16 L4.2 15 a1.56 1.56 0 0 1 2.4 -1.92 L8.4 14.4",
    )
    /** Sun: keep the screen on, power saving. */
    val Sun = stroke(
        "sun", "M8.4 12 a3.6 3.6 0 1 0 7.2 0 a3.6 3.6 0 1 0 -7.2 0",
        "M12 3 v1.8 M12 19.2 v1.8 M3 12 H4.8 M19.2 12 h1.8 M5.64 5.64 l1.2 1.2 M17.16 17.16 l1.2 1.2 M5.64 18.36 l1.2 -1.2 M17.16 6.84 l1.2 -1.2",
    )
    val Mute = stroke("mute", "M4.2 9.6 h3.6 l4.8 -3.6 v12 l-4.8 -3.6 h-3.6 z", "M16.8 9.6 l4.8 4.8 M21.6 9.6 l-4.8 4.8")
    /** Volume: restore sound. */
    val Sound = stroke(
        "sound",
        "M4.2 9.6 h3.6 l4.8 -3.6 v12 l-4.8 -3.6 h-3.6 z",
        "M16.2 8.4 a5.1 5.1 0 0 1 0 7.2 M19.2 5.4 a9.2 9.2 0 0 1 0 13.2",
    )

    /** Filled: stop. */
    val Stop = filled(
        "stop",
        "M8.28 6 h7.44 a2.28 2.28 0 0 1 2.28 2.28 v7.44 a2.28 2.28 0 0 1 -2.28 2.28 H8.28 A2.28 2.28 0 0 1 6 15.72 V8.28 A2.28 2.28 0 0 1 8.28 6 z",
    )
    /** Filled: play (action replay). */
    val Play = filled("play", "M8 5 v14 l11 -7 z")
    /** Filled: exclamation mark. */
    val Exclaim = filled(
        "exclaim", "M12 4.5 a1.5 1.5 0 0 1 1.5 1.5 v7.5 a1.5 1.5 0 0 1 -3 0 V6 A1.5 1.5 0 0 1 12 4.5 z",
        "M10.2 18.72 a1.8 1.8 0 1 0 3.6 0 a1.8 1.8 0 1 0 -3.6 0",
    )
}
