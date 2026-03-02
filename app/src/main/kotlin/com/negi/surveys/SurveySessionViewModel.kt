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
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.ui.ReviewChatLine
import com.negi.surveys.ui.ReviewQuestionLog
import com.negi.surveys.ui.ReviewTimelineItem
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    /**
     * In-memory store of per-question logs keyed by questionId.
     *
     * Important:
     * - Keep the value immutable (Map + data classes) so StateFlow emissions are predictable.
     * - Always create a new Map instance on accepted updates.
     */
    private val _logsMap = MutableStateFlow<Map<String, ReviewQuestionLog>>(emptyMap())

    /**
     * Sorted list of logs for rendering and export.
     *
     * Ordering:
     * - Natural ordering for IDs like Q1..Q10 (instead of lexical Q1,Q10,Q2).
     * - Case-insensitive prefix ordering using Locale.ROOT for determinism.
     * - Fallback to raw string ordering when parsing fails.
     */
    val logs: StateFlow<List<ReviewQuestionLog>> =
        _logsMap
            .map { map ->
                if (map.isEmpty()) return@map emptyList()

                val items = ArrayList<SortItem>(map.size)
                for (log in map.values) {
                    items.add(SortItem(log = log, key = questionIdKey(log.questionId)))
                }

                items.sortWith(
                    compareBy<SortItem>(
                        { it.key.prefixSort },
                        { it.key.number },
                        { it.key.raw }
                    )
                )

                val out = ArrayList<ReviewQuestionLog>(items.size)
                for (it in items) out.add(it.log)
                out
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
     * - Prefer logs that look "more complete".
     *   Priority: transcript line count -> transcript char count -> payload length -> prompt length.
     * - Always allow changes in isSkipped (explicit user action).
     *
     * Concurrency note:
     * - StateFlow.updateAndGet() may retry the lambda under contention.
     * - Do NOT mutate outer variables inside the update lambda.
     */
    fun upsertLog(log: ReviewQuestionLog) {
        val key = log.questionId.trim()
        if (key.isEmpty()) {
            SafeLog.w(TAG, "upsertLog: ignored (blank questionId)")
            return
        }

        // Snapshot transcript lines to avoid external mutation after insertion.
        val incoming = normalizeIncomingLog(log = log, normalizedKey = key)

        val after = _logsMap.updateAndGet { prev ->
            val existing = prev[key]
            val accept = existing == null || shouldAcceptUpdate(existing = existing, incoming = incoming)
            if (!accept) return@updateAndGet prev

            // Make a new Map instance (immutable snapshot).
            val next = prev.toMutableMap()
            next[key] = incoming
            next.toMap()
        }

        // Reference check is intentional: we want to know if *this exact incoming instance* won.
        val accepted = after[key] === incoming

        val lineChars = transcriptCharCount(incoming.lines)
        val payloadLen = incoming.completionPayload.length

        if (accepted) {
            SafeLog.d(
                TAG,
                "upsertLog: accepted qid=$key skipped=${incoming.isSkipped} " +
                        "lines=${incoming.lines.size} lineChars=$lineChars payloadLen=$payloadLen"
            )
        } else {
            val existing = after[key]
            if (existing != null) {
                val inScore = completenessScore(incoming)
                val exScore = completenessScore(existing)
                val tie = inScore == exScore && existing.isSkipped == incoming.isSkipped
                val suffix = if (tie) " tieScore=$inScore" else " inScore=$inScore exScore=$exScore"
                SafeLog.w(
                    TAG,
                    "upsertLog: dropped(stale) qid=$key skipped=${incoming.isSkipped} " +
                            "lines=${incoming.lines.size} lineChars=$lineChars payloadLen=$payloadLen$suffix"
                )
            } else {
                SafeLog.w(
                    TAG,
                    "upsertLog: dropped(stale) qid=$key skipped=${incoming.isSkipped} " +
                            "lines=${incoming.lines.size} lineChars=$lineChars payloadLen=$payloadLen"
                )
            }
        }
    }

    /**
     * Optional: clear the session.
     */
    fun clear() {
        _logsMap.update { emptyMap() }
        SafeLog.i(TAG, "clear: session cleared")
    }

    // ---------------------------------------------------------------------
    // Export / Timeline builders
    // ---------------------------------------------------------------------

    /**
     * Export schema v1.
     *
     * Important:
     * - Use ReviewChatKind.wireName (stable) instead of enum.name (rename-unsafe).
     */
    private fun buildExportJsonV1(sortedLogs: List<ReviewQuestionLog>): String {
        if (sortedLogs.isEmpty()) return EXPORT_JSON_EMPTY

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

    // ---------------------------------------------------------------------
    // Update policy
    // ---------------------------------------------------------------------

    /**
     * Decide whether to accept an incoming update for the same questionId.
     */
    private fun shouldAcceptUpdate(existing: ReviewQuestionLog, incoming: ReviewQuestionLog): Boolean {
        // Always accept explicit user action changes.
        if (existing.isSkipped != incoming.isSkipped) return true

        // Prefer "more complete" logs.
        val existingScore = completenessScore(existing)
        val incomingScore = completenessScore(incoming)

        if (incomingScore != existingScore) {
            return incomingScore > existingScore
        }

        // Tie-breaker policy:
        // - Prefer keeping existing to avoid late stale overwrites when concurrent producers exist.
        return false
    }

    /**
     * Compute a monotonic score for "completeness".
     *
     * Priority (dominance):
     * - Transcript line count (heaviest)
     * - Transcript total character count
     * - Payload length
     * - Prompt length
     */
    private fun completenessScore(log: ReviewQuestionLog): Long {
        val lineCount = log.lines.size.coerceAtLeast(0).toLong()
        val lineChars = transcriptCharCount(log.lines).coerceAtLeast(0).toLong()
        val payload = log.completionPayload.length.coerceAtLeast(0).toLong()
        val prompt = log.prompt.length.coerceAtLeast(0).toLong()

        // Weighting preserves priority while staying far from Long overflow for realistic sessions.
        return (lineCount * 1_000_000_000_000L) +
                (lineChars * 1_000_000L) +
                (payload * 1_000L) +
                prompt
    }

    /**
     * Computes the total character count across transcript lines.
     */
    private fun transcriptCharCount(lines: List<ReviewChatLine>): Int {
        var sum = 0
        for (i in lines.indices) {
            sum += lines[i].text.length
        }
        return sum
    }

    // ---------------------------------------------------------------------
    // Normalization / Sorting keys
    // ---------------------------------------------------------------------

    /**
     * Normalize and snapshot an incoming log.
     *
     * Why:
     * - Prevent external mutation of transcript lists after insertion.
     * - Ensure key normalization is applied consistently.
     *
     * Policy:
     * - Always snapshot lines via toList() (defensive copy).
     * - Always write questionId as the normalized key.
     */
    private fun normalizeIncomingLog(log: ReviewQuestionLog, normalizedKey: String): ReviewQuestionLog {
        val safeLines = log.lines.toList()
        return if (log.questionId == normalizedKey && log.lines === safeLines) {
            log
        } else {
            log.copy(questionId = normalizedKey, lines = safeLines)
        }
    }

    private data class SortItem(
        val log: ReviewQuestionLog,
        val key: QuestionIdKey
    )

    private data class QuestionIdKey(
        val prefixSort: String,
        val number: Int,
        val raw: String
    )

    /**
     * Natural ordering key for question IDs like "Q1", "Q10", "A2".
     *
     * Parsing:
     * - prefix: consecutive letters at start
     * - number: consecutive digits after prefix
     * - otherwise fallback to raw
     */
    private fun questionIdKey(id: String): QuestionIdKey {
        val raw = id.trim()
        if (raw.isEmpty()) {
            return QuestionIdKey(prefixSort = "", number = Int.MAX_VALUE, raw = "")
        }

        var i = 0
        val n = raw.length

        while (i < n && raw[i].isLetter()) i++
        if (i == 0 || i == n) {
            val p = raw.lowercase(Locale.ROOT)
            return QuestionIdKey(prefixSort = p, number = Int.MAX_VALUE, raw = raw)
        }

        var j = i
        while (j < n && raw[j].isDigit()) j++
        if (j != n) {
            val p = raw.lowercase(Locale.ROOT)
            return QuestionIdKey(prefixSort = p, number = Int.MAX_VALUE, raw = raw)
        }

        val prefix = raw.substring(0, i)
        val numStr = raw.substring(i)
        val number = numStr.toIntOrNull() ?: Int.MAX_VALUE

        return QuestionIdKey(
            prefixSort = prefix.lowercase(Locale.ROOT),
            number = number,
            raw = raw
        )
    }

    // ---------------------------------------------------------------------
    // JSON escaping
    // ---------------------------------------------------------------------

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

    private companion object {
        private const val TAG = "SurveySessionVM"
        private const val EXPORT_JSON_EMPTY = "{\"version\":1,\"questions\":[]}"
    }
}