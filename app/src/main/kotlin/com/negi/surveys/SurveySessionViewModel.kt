/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: SurveySessionViewModel.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negi.surveys.ui.ReviewChatLine
import com.negi.surveys.ui.ReviewQuestionLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Session-lifetime state holder for the Nav3 prototype.
 *
 * Why:
 * - Avoid keeping important state in composable remember() maps.
 * - Make Review/Export deterministic and resilient to recomposition.
 *
 * Notes:
 * - This is intentionally simple: no persistence yet (SavedStateHandle/file) in this step.
 * - We assume ReviewQuestionLog has a canonical "completionPayload" field.
 */
class SurveySessionViewModel : ViewModel() {

    private val _logsMap = MutableStateFlow<Map<String, ReviewQuestionLog>>(emptyMap())

    /**
     * Sorted list of logs for rendering and export.
     *
     * Notes:
     * - Uses "natural" ordering for IDs like Q1..Q10 (instead of lexical Q1,Q10,Q2).
     * - If an ID doesn't match the expected pattern, it falls back to raw string ordering.
     */
    val logs: StateFlow<List<ReviewQuestionLog>> =
        _logsMap
            .map { map ->
                map.values
                    .map { it to questionIdKey(it.questionId) }
                    .sortedWith(
                        compareBy<Pair<ReviewQuestionLog, QuestionIdKey>>(
                            { it.second.prefix },
                            { it.second.number },
                            { it.second.raw }
                        )
                    )
                    .map { it.first }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Deterministic JSON for export (v1).
     *
     * Notes:
     * - The JSON string is rebuilt whenever logs change.
     * - Keep it stable: same input -> same output.
     */
    val exportJson: StateFlow<String> =
        logs
            .map { buildExportJsonV1(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, buildExportJsonV1(emptyList()))

    /**
     * Upsert a question log (called from ChatQuestionScreen when pressing Next).
     *
     * Notes:
     * - Uses StateFlow.update to avoid lost updates under concurrent calls.
     */
    fun upsertLog(log: ReviewQuestionLog) {
        _logsMap.update { prev ->
            val next = prev.toMutableMap()
            next[log.questionId] = log
            next.toMap()
        }

        Log.d(
            TAG,
            "upsertLog: qid=${log.questionId} skipped=${log.isSkipped} lines=${log.lines.size} payloadLen=${log.completionPayload.length}"
        )
    }

    /**
     * Optional: clear the session.
     */
    fun clear() {
        _logsMap.value = emptyMap()
        Log.d(TAG, "clear: session cleared")
    }

    /**
     * Export schema v1.
     *
     * Output shape:
     * {
     *   "version": 1,
     *   "questions": [
     *     {
     *       "questionId": "...",
     *       "prompt": "...",
     *       "isSkipped": true/false,
     *       "payload": "...",
     *       "transcript": [
     *         {"kind":"USER","text":"..."},
     *         {"kind":"AI","text":"..."}
     *       ]
     *     }
     *   ]
     * }
     */
    private fun buildExportJsonV1(sortedLogs: List<ReviewQuestionLog>): String {
        val sb = StringBuilder(4096)
        sb.append('{')
        sb.append("\"version\":1,")
        sb.append("\"questions\":[")
        for (i in sortedLogs.indices) {
            val q = sortedLogs[i]
            if (i > 0) sb.append(',')

            sb.append('{')
            sb.append("\"questionId\":\"").append(jsonEscape(q.questionId)).append("\",")
            sb.append("\"prompt\":\"").append(jsonEscape(q.prompt)).append("\",")
            sb.append("\"isSkipped\":").append(q.isSkipped).append(',')
            // NOTE:
            // - Canonical name is completionPayload in the model.
            // - JSON key stays "payload" for export schema compatibility.
            sb.append("\"payload\":\"").append(jsonEscape(q.completionPayload)).append("\",")

            sb.append("\"transcript\":[")
            val lines: List<ReviewChatLine> = q.lines
            for (j in lines.indices) {
                val line = lines[j]
                if (j > 0) sb.append(',')

                sb.append('{')
                sb.append("\"kind\":\"").append(jsonEscape(line.kind.name)).append("\",")
                sb.append("\"text\":\"").append(jsonEscape(line.text)).append('\"')
                sb.append('}')
            }
            sb.append(']')

            sb.append('}')
        }
        sb.append(']')
        sb.append('}')
        return sb.toString()
    }

    /**
     * Minimal JSON string escaping with control-char handling.
     *
     * Notes:
     * - Escapes common control characters that break JSON.
     * - Escapes other ASCII control chars defensively via \\uXXXX.
     */
    private fun jsonEscape(s: String): String {
        if (s.isEmpty()) return ""
        val out = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        out.append("\\u")
                        out.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        return out.toString()
    }

    /**
     * Natural ordering key for question IDs like "Q1", "Q10", "A2".
     *
     * Rules:
     * - prefix: leading letters (e.g., "Q")
     * - number: trailing digits as Int (e.g., 1, 10)
     * - raw: original string (tie-breaker)
     *
     * If the ID doesn't match the pattern, we fall back to:
     * - prefix = id
     * - number = Int.MAX_VALUE
     */
    private fun questionIdKey(id: String): QuestionIdKey {
        val raw = id.trim()
        val m = ID_PATTERN.matchEntire(raw)
        if (m != null) {
            val prefix = m.groupValues[1]
            val number = m.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE
            return QuestionIdKey(prefix = prefix, number = number, raw = raw)
        }
        return QuestionIdKey(prefix = raw, number = Int.MAX_VALUE, raw = raw)
    }

    private data class QuestionIdKey(
        val prefix: String,
        val number: Int,
        val raw: String
    )

    private companion object {
        private const val TAG = "SurveySessionVM"
        private val ID_PATTERN = Regex("^([A-Za-z]+)(\\d+)$")
    }
}
