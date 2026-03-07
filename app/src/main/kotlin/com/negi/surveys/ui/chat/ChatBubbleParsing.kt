/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Bubble Parsing)
 *  ---------------------------------------------------------------------
 *  File: ChatBubbleParsing.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui.chat

import org.json.JSONObject

/**
 * Best-effort parsing helpers for chat bubble UI.
 *
 * Important:
 * - UI-only parsing. Never log raw user/model text here.
 * - Prefer structured fields from ChatMessage when available.
 * - These parsers are fallback helpers for mixed/raw blobs.
 */
internal object ChatBubbleParsing {

    data class EvalResult(
        val status: String?,
        val score: Int,
    )

    data class VerdictResult(
        val status: String?,
        val assistantMessage: String?,
        val followUpQuestion: String?,
    )

    private const val EVAL_RESULT_PREFIX: String = "[EVAL_RESULT]"

    // Example: "[EVAL_RESULT] status=ACCEPTED score=92"
    private val EVAL_RESULT_REGEX: Regex =
        Regex("""\[\s*EVAL_RESULT\s*]\s*status=([A-Z_?]+)\s+score=(-?\d+)\b""")

    /**
     * Best-effort extraction of the latest eval result from a mixed text blob.
     *
     * Supports:
     * - "[EVAL_RESULT] status=... score=..."
     * - JSON objects containing "score" (0..100)
     */
    fun extractEvalResultBestEffort(text: String?): EvalResult? {
        val s = text?.takeIf { it.isNotBlank() } ?: return null

        runCatching {
            val matches = EVAL_RESULT_REGEX.findAll(s).toList()
            val m = matches.lastOrNull()
            if (m != null) {
                val status = m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                val score = m.groupValues.getOrNull(2)?.toIntOrNull()
                if (score != null) return EvalResult(status = status, score = score)
            }
        }

        val jsonObjects = extractJsonObjectsBestEffort(s)
        var best: EvalResult? = null
        for (j in jsonObjects) {
            val parsed = parseEvalJsonBestEffort(j)
            if (parsed != null) best = parsed
        }
        return best
    }

    private fun parseEvalJsonBestEffort(json: String): EvalResult? {
        if (json.isBlank()) return null

        return runCatching {
            val obj = JSONObject(json)
            if (!obj.has("score")) return null

            val score = when (val raw = obj.opt("score")) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull() ?: return null
                else -> return null
            }

            if (score !in 0..100) return null

            val status = optNonBlank(obj, "status")
            EvalResult(status = status, score = score)
        }.getOrNull()
    }

    /**
     * Removes lines containing "[EVAL_RESULT]" so the bubble raw/details remain clean.
     */
    fun stripEvalResultLines(text: String): String {
        if (text.isBlank()) return text
        if (!text.contains(EVAL_RESULT_PREFIX)) return text

        val lines = text.split('\n')
        return buildString {
            for (line in lines) {
                if (line.contains(EVAL_RESULT_PREFIX)) continue
                if (isNotEmpty()) append('\n')
                append(line)
            }
        }
    }

    /**
     * Extracts the latest assistant/verdict-like JSON object.
     *
     * Supported keys:
     * - assistantMessage
     * - assistant_message
     * - followUpQuestion
     * - followupQuestion
     * - follow_up_question
     *
     * Notes:
     * - This intentionally ignores score-only eval JSON.
     * - It returns the latest object that carries assistant-facing structured content.
     */
    fun extractLatestVerdictBestEffort(raw: String?): VerdictResult? {
        val s = raw?.takeIf { it.isNotBlank() } ?: return null

        val jsonObjects = extractJsonObjectsBestEffort(s)
        var best: VerdictResult? = null
        for (j in jsonObjects) {
            val parsed = parseVerdictJsonBestEffort(j)
            if (parsed != null) best = parsed
        }
        return best
    }

    private fun parseVerdictJsonBestEffort(json: String): VerdictResult? {
        if (json.isBlank()) return null

        return runCatching {
            val obj = JSONObject(json)

            val assistantMessage =
                optNonBlank(obj, "assistantMessage")
                    ?: optNonBlank(obj, "assistant_message")

            val followUpQuestion =
                optNonBlank(obj, "followUpQuestion")
                    ?: optNonBlank(obj, "followupQuestion")
                    ?: optNonBlank(obj, "follow_up_question")

            val status = optNonBlank(obj, "status")

            // Ignore eval-only JSON that does not contain assistant-facing content.
            if (assistantMessage == null && followUpQuestion == null) return null

            VerdictResult(
                status = status,
                assistantMessage = assistantMessage,
                followUpQuestion = followUpQuestion,
            )
        }.getOrNull()
    }

    /**
     * Extracts complete JSON objects from a mixed text blob.
     *
     * Notes:
     * - Brace depth tracking with string/escape awareness.
     * - Returns objects in encounter order.
     */
    private fun extractJsonObjectsBestEffort(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val out = ArrayList<String>(4)

        var start = -1
        var depth = 0
        var inString = false
        var escape = false

        for (i in text.indices) {
            val c = text[i]

            if (start < 0) {
                if (c == '{') {
                    start = i
                    depth = 1
                    inString = false
                    escape = false
                }
                continue
            }

            if (escape) {
                escape = false
                continue
            }

            if (c == '\\' && inString) {
                escape = true
                continue
            }

            if (c == '"') {
                inString = !inString
                continue
            }

            if (inString) continue

            when (c) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        val obj = text.substring(start, i + 1).trim()
                        out.add(obj)
                        start = -1
                    }
                }
            }
        }

        return out
    }

    /**
     * Reads a JSON string field and returns null when blank.
     */
    private fun optNonBlank(obj: JSONObject, key: String): String? {
        if (!obj.has(key)) return null
        val value = obj.optString(key, "").trim()
        return value.takeIf { it.isNotBlank() }
    }
}