package com.wangzan101.mika.device

import com.wangzan101.mika.core.device.CredentialKind

/**
 * General rule: only accept input fields in SystemUI that carry a password flag; edit boxes distinguish PIN (numeric) from password (non-numeric) by input type.
 * The stock Android lock screen draws its PIN box itself (`pinEntry` / `PasswordTextView`); whether or not it declares editable, only this set of stock names is accepted as a PIN.
 * When an edit box declares no input type (TYPE_NULL, the page draws its own keyboard), look for 0–9 keys in the same window: all present means a PIN page, otherwise a password page.
 * Other non-editable controls and SIM PINs are never accepted; when unsure, nothing is typed.
 */
internal object KeyguardFields {
    fun matches(kind: CredentialKind, id: String, className: String, password: Boolean, editable: Boolean, numeric: Boolean,
        untyped: Boolean = false, digitPad: Boolean = false): Boolean {
        if (!password || !id.startsWith("com.android.systemui:id/") || "sim" in id.lowercase()) return false
        val pinView = id == "com.android.systemui:id/pinEntry" &&
            (className == "com.android.keyguard.PasswordTextView" || untyped && digitPad)
        val pinField = numeric || untyped && digitPad
        return when (kind) {
            CredentialKind.Pin -> pinView || editable && pinField
            // On some systems the stock PIN box reports editable but not a numeric type; it is still only a PIN box and must not be accepted as both.
            CredentialKind.Password -> editable && !pinField && !pinView
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
