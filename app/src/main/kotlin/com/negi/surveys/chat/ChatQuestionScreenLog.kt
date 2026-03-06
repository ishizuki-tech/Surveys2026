/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Question UI)
 *  ---------------------------------------------------------------------
 *  File: ChatQuestionScreenLog.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys.ui.chat

import java.security.MessageDigest

/**
 * Log sanitization helpers for ChatQuestionScreen.
 *
 * IMPORTANT:
 * - Never return or log raw text.
 * - Only return length + hash (metadata) to avoid PII leaks.
 */
internal object ChatQuestionScreenLog {

    const val TAG: String = "ChatQuestionScreen"

    fun safeLogSummary(raw: String?): String {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return "(empty)"
        val len = t.length
        val sha12 = sha256HexPrefix(t, 12)
        return "len=$len sha256=$sha12"
    }

    private fun sha256HexPrefix(text: String, hexChars: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(hexChars)
        val neededBytes = (hexChars + 1) / 2
        for (i in 0 until neededBytes.coerceAtMost(bytes.size)) {
            val b = bytes[i].toInt() and 0xFF
            sb.append("0123456789abcdef"[b ushr 4])
            sb.append("0123456789abcdef"[b and 0x0F])
        }
        return if (sb.length > hexChars) sb.substring(0, hexChars) else sb.toString()
    }
}