package com.wangzan101.mika.device

import com.wangzan101.mika.core.device.CredentialKind

/** Lock screen detection diagnostics keep only technical attributes of system controls; resource and class names are restricted in characters and length too, and on-screen text is never accepted. */
internal object KeyguardDiagnostics {
    fun name(value: CharSequence?): String = value?.toString().orEmpty().take(160).map { c ->
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_.\$:/-") c else '_'
    }.joinToString("").ifEmpty { "unknown" }

    fun window(
        index: Int,
        focused: Boolean,
        passwordNodes: Int,
        digits: Int,
        padKeys: Int,
        pinLayout: Boolean,
    ): String = "window[index=$index,focused=$focused,password_nodes=$passwordNodes,digits=$digits," +
        "pad_keys=$padKeys,pin_layout=$pinLayout]"

    /** Which list the credential page was read from, and the size of each list: distinguishes "the whole window list is empty", "wrong display id" and "active window is not SystemUI". */
    fun source(source: String, displayIds: List<Int>, legacyWindows: Int, activeRootPackage: CharSequence?): String =
        "keyguard_source[source=$source,display_ids=${displayIds.joinToString("/").ifEmpty { "none" }}," +
            "legacy_windows=$legacyWindows,active_root=${activeRootPackage?.let(::name) ?: "none"}]"

    fun windowInfo(
        index: Int,
        type: Int,
        packageName: CharSequence?,
        focused: Boolean,
        active: Boolean,
    ): String = "window_info[index=$index,type=$type,package=${name(packageName)},focused=$focused,active=$active]"

    fun field(
        id: CharSequence?,
        className: CharSequence?,
        editable: Boolean,
        numeric: Boolean,
        untyped: Boolean,
        digitPad: Boolean,
        focused: Boolean,
        match: CredentialKind?,
    ): String = "field[id=${name(id)},class=${name(className)},editable=$editable,numeric=$numeric," +
        "untyped=$untyped,digit_pad=$digitPad,focused=$focused,match=${match?.name?.lowercase() ?: "none"}]"
}
