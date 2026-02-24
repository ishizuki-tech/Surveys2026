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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negi.surveys.logging.AppLog
import com.negi.surveys.ui.ReviewChatLine
import com.negi.surveys.ui.ReviewQuestionLog
import com.negi.surveys.ui.ReviewTimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet

/**
 * Session-lifetime state holder for the Nav3 prototype.
 *
 * Why:
 * - Avoid keeping important state in composable remember() maps.
 * - Make Review/Export deterministic and resilient to recomposition.
 *
 * Notes:
 * - This is intentionally simple: no persistence yet (SavedStateHandle/file) in this step.
 * - Heavy transforms (sort/export/timeline) are moved off Main via flowOn(Dispatchers.Default).
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
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Flattened timeline for Review UI.
     *
     * Ordering:
     * - Question order is derived from [logs] (natural questionId ordering).
     * - Within a question, line order is preserved as-is.
     */
    val timeline: StateFlow<List<ReviewTimelineItem>> =
        logs
            .map { buildTimelineV1(it) }
            .flowOn(Dispatchers.Default)
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
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, EXPORT_JSON_EMPTY)

    /**
     * Upsert a question log (called from ChatQuestionScreen when pressing Next).
     *
     * Root fix:
     * - Avoid "late stale updates" overwriting newer state.
     * - When concurrent producers exist (streaming, navigation, recomposition),
     *   a smaller/older log might arrive later.
     *
     * Rule:
     * - Prefer logs that look "more complete" (more lines / longer payload / longer prompt).
     * - Always allow changes in isSkipped (explicit user action).
     *
     * Concurrency note:
     * - StateFlow.updateAndGet() may retry the lambda under contention.
     * - Do NOT mutate outer variables inside the update lambda. Determine acceptance from final state.
     */
    fun upsertLog(log: ReviewQuestionLog) {
        val qid = log.questionId.trim()
        val normalized = if (qid == log.questionId) log else log.copy(questionId = qid)

        val after = _logsMap.updateAndGet { prev ->
            val existing = prev[qid]
            val should = existing == null || shouldAcceptUpdate(existing, normalized)
            if (!should) return@updateAndGet prev

            val next = prev.toMutableMap()
            next[qid] = normalized
            next.toMap()
        }

        val accepted = after[qid] === normalized

        if (accepted) {
            AppLog.d(
                TAG,
                "upsertLog: accepted qid=$qid skipped=${normalized.isSkipped} " +
                        "lines=${normalized.lines.size} payloadLen=${normalized.completionPayload.length}"
            )
        } else {
            AppLog.w(
                TAG,
                "upsertLog: dropped(stale) qid=$qid skipped=${normalized.isSkipped} " +
                        "lines=${normalized.lines.size} payloadLen=${normalized.completionPayload.length}"
            )
        }
    }

    /**
     * Optional: clear the session.
     */
    fun clear() {
        _logsMap.value = emptyMap()
        AppLog.i(TAG, "clear: session cleared")
    }

    /**
     * Export schema v1.
     *
     * Important:
     * - Use ReviewChatKind.wireName (stable) instead of enum.name (rename-unsafe).
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
            sb.append("\"payload\":\"").append(jsonEscape(q.completionPayload)).append("\",")

            sb.append("\"transcript\":[")
            val lines: List<ReviewChatLine> = q.lines
            for (j in lines.indices) {
                val line = lines[j]
                if (j > 0) sb.append(',')

                sb.append('{')
                sb.append("\"kind\":\"").append(jsonEscape(line.kind.wireName)).append("\",")
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
     * Build timeline v1:
     * - Emits a header per question, followed by its transcript lines.
     */
    private fun buildTimelineV1(sortedLogs: List<ReviewQuestionLog>): List<ReviewTimelineItem> {
        if (sortedLogs.isEmpty()) return emptyList()

        val totalLines = sortedLogs.sumOf { it.lines.size }
        val out = ArrayList<ReviewTimelineItem>(sortedLogs.size + totalLines)

        for (q in sortedLogs) {
            out.add(
                ReviewTimelineItem.QuestionHeader(
                    questionId = q.questionId,
                    prompt = q.prompt,
                    isSkipped = q.isSkipped,
                    completionPayload = q.completionPayload
                )
            )
            for (line in q.lines) {
                out.add(
                    ReviewTimelineItem.Line(
                        questionId = q.questionId,
                        prompt = q.prompt,
                        isSkipped = q.isSkipped,
                        line = line
                    )
                )
            }
        }
        return out
    }

    /**
     * Decide whether to accept an incoming update for the same questionId.
     */
    private fun shouldAcceptUpdate(existing: ReviewQuestionLog, incoming: ReviewQuestionLog): Boolean {
        if (existing.isSkipped != incoming.isSkipped) return true

        val existingLines = existing.lines.size
        val incomingLines = incoming.lines.size
        if (incomingLines != existingLines) return incomingLines > existingLines

        val existingPayload = existing.completionPayload.length
        val incomingPayload = incoming.completionPayload.length
        if (incomingPayload != existingPayload) return incomingPayload > existingPayload

        val existingPrompt = existing.prompt.length
        val incomingPrompt = incoming.prompt.length
        if (incomingPrompt != existingPrompt) return incomingPrompt > existingPrompt

        // If all heuristics are tied, accept the incoming update to allow minor edits.
        return true
    }

    /**
     * Minimal JSON string escaping with control-char handling.
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
        private const val EXPORT_JSON_EMPTY = "{\"version\":1,\"questions\":[]}"
    }
}