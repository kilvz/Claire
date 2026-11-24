package com.wangzan101.mika.device

import com.wangzan101.mika.core.device.CredentialKind

/**
 * General rule: only input fields carrying the password flag inside SystemUI are accepted; for editable fields, PIN (numeric) and password (non-numeric) are told apart by the input type.
 * The stock Android lock screen draws its own PIN field (`pinEntry` / `PasswordTextView`), which does not declare editable, so it is let through only by this set of stock names.
 * When an editable field declares no input type (TYPE_NULL, the page draws the keyboard itself), look for a complete 0–9 numeric keypad in the same window: if there is one it is a PIN page, otherwise it counts as a password page.
 * All other non-editable controls and SIM PIN are never accepted; when it is not certain, no input is made.
 */
internal object KeyguardFields {
    fun matches(kind: CredentialKind, id: String, className: String, password: Boolean, editable: Boolean, numeric: Boolean,
        untyped: Boolean = false, digitPad: Boolean = false): Boolean {
        if (!password || !id.startsWith("com.android.systemui:id/") || "sim" in id.lowercase()) return false
        val pinView = id == "com.android.systemui:id/pinEntry" && className == "com.android.keyguard.PasswordTextView"
        val pinField = numeric || untyped && digitPad
        return when (kind) {
            CredentialKind.Pin -> pinView || editable && pinField
            CredentialKind.Password -> editable && !pinField
        }
    }

    /** When the control's text or description is a single digit (optionally with letter hints, e.g. "2 ABC"), returns that digit; used to recognize keypad keys. */
    fun digitKey(text: String?, description: String?): Char? =
        listOfNotNull(text, description).firstNotNullOfOrNull { s ->
            s.trim().takeIf { it.isNotEmpty() && it[0] in '0'..'9' && (it.length == 1 || !it[1].isLetterOrDigit()) }?.get(0)
        }

    /** Only counts as a numeric keypad when all of 0–9 have keys. */
    fun isDigitPad(keys: Set<Char>): Boolean = ('0'..'9').all { it in keys }
}
