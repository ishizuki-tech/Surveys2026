/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (SLM Validator via Repository)
 *  ---------------------------------------------------------------------
 *  File: SlmAnswerValidator.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AnswerValidator implementation backed by a streaming SLM repository.
 *
 * Strategy:
 * - Build a model-ready prompt via Repository.buildPrompt(userPrompt, phase).
 * - Execute Repository.request(prompt) which returns Flow<String>.
 * - Collect the stream into a single text (bounded + timeout) on a background dispatcher.
 * - Extract/parse a JSON object from the model output.
 *
 * Streaming:
 * - Emits Begin/Delta/End/Error to [streamBridge] so the ViewModel can render progress.
 *
 * Privacy:
 * - Never logs raw user answers. Logger must remain metadata-only.
 */
class SlmAnswerValidator(
    private val repository: Repository,
    private val streamBridge: ChatStreamBridge,
    private val timeoutMs: Long = 30_000L,
    private val maxChars: Int = 32_000,
    private val logger: ((String) -> Unit)? = null
) : AnswerValidator {

    override suspend fun validateMain(questionId: String, answer: String): ValidationOutcome {
        val userPrompt = buildValidationUserPrompt(
            questionId = questionId,
            mainAnswer = answer,
            followUpAnswer = null
        )
        val modelPrompt = repository.buildPrompt(userPrompt, PromptPhase.VALIDATE_MAIN)

        log("validateMain: qid=${questionId.trim()} (request start)")

        val raw = collectStreamingText(
            promptChars = modelPrompt.length,
            flowProvider = { repository.request(modelPrompt) },
            timeoutMs = timeoutMs,
            maxChars = maxChars
        )

        log("validateMain: qid=${questionId.trim()} (response received chars=${raw.length})")

        return parseOutcomeOrFallback(
            questionId = questionId,
            raw = raw,
            fallbackFollowUp = "Could you add one concrete detail or example?"
        )
    }

    override suspend fun validateFollowUp(
        questionId: String,
        mainAnswer: String,
        followUpAnswer: String
    ): ValidationOutcome {
        val userPrompt = buildValidationUserPrompt(
            questionId = questionId,
            mainAnswer = mainAnswer,
            followUpAnswer = followUpAnswer
        )
        val modelPrompt = repository.buildPrompt(userPrompt, PromptPhase.VALIDATE_FOLLOW_UP)

        log("validateFollowUp: qid=${questionId.trim()} (request start)")

        val raw = collectStreamingText(
            promptChars = modelPrompt.length,
            flowProvider = { repository.request(modelPrompt) },
            timeoutMs = timeoutMs,
            maxChars = maxChars
        )

        log("validateFollowUp: qid=${questionId.trim()} (response received chars=${raw.length})")

        return parseOutcomeOrFallback(
            questionId = questionId,
            raw = raw,
            fallbackFollowUp = "Please add one short concrete detail so I can proceed."
        )
    }

    private fun buildValidationUserPrompt(
        questionId: String,
        mainAnswer: String,
        followUpAnswer: String?
    ): String {
        val qid = questionId.trim()
        val main = mainAnswer.trim()

        val fuRaw = followUpAnswer?.trim().orEmpty()
        val hasFollowUp = fuRaw.isNotBlank()

        // Heuristic:
        // - VM follow-up history format looks like:
        //   FOLLOW_UP_1_Q: ...
        //   FOLLOW_UP_1_A: ...
        // - If it matches, wrap as HISTORY so FakeSlmRepository (and real models) can parse it cleanly.
        val treatAsHistory = hasFollowUp && looksLikeFollowUpHistory(fuRaw)

        val followUpBlock = if (!hasFollowUp) {
            """
FOLLOW_UP_ANSWER_BEGIN

FOLLOW_UP_ANSWER_END
""".trimIndent()
        } else if (treatAsHistory) {
            """
FOLLOW_UP_HISTORY_BEGIN
$fuRaw
FOLLOW_UP_HISTORY_END
""".trimIndent()
        } else {
            """
FOLLOW_UP_ANSWER_BEGIN
$fuRaw
FOLLOW_UP_ANSWER_END
""".trimIndent()
        }

        // NOTE:
        // - Use explicit BEGIN/END markers to make extraction robust against multi-line answers.
        // - Require JSON-only output (no markdown, no analysis).
        return """
You are a strict JSON generator for a survey answer validation system.

OUTPUT RULES:
- Output MUST be exactly ONE JSON object.
- Do NOT output markdown, code fences, or additional text.
- Do NOT include analysis or reasoning.
- Do NOT repeat the full user answers.

JSON SCHEMA:
{
  "status": "ACCEPTED" | "NEED_FOLLOW_UP",
  "assistantMessage": "string",
  "followUpQuestion": "string (required when status=NEED_FOLLOW_UP)"
}

VALIDATION GOAL:
- If MAIN_ANSWER is sufficient, return status=ACCEPTED.
- If insufficient, return status=NEED_FOLLOW_UP and ask exactly ONE concise follow-up question.

INPUT:
QUESTION_ID: $qid

MAIN_ANSWER_BEGIN
$main
MAIN_ANSWER_END

$followUpBlock
""".trimIndent()
    }

    /**
     * Collect a streaming Flow<String> into a single buffer with:
     * - timeout
     * - max character cap
     * - Begin/Delta/End/Error events for UI
     *
     * Critical:
     * - Runs the streaming collection on Dispatchers.Default so UI can render deltas.
     *
     * Semantics:
     * - timeoutMs <= 0 => no timeout (collect until completion or maxChars).
     */
    private suspend fun collectStreamingText(
        promptChars: Int,
        flowProvider: suspend () -> kotlinx.coroutines.flow.Flow<String>,
        timeoutMs: Long,
        maxChars: Int
    ): String {
        val maxCharsSafe = maxChars.coerceAtLeast(0)
        val timeoutMsSafe = timeoutMs.coerceAtLeast(0L)

        val sessionId = streamBridge.begin()
        log("collectStreamingText: session=$sessionId promptChars=$promptChars timeoutMs=$timeoutMsSafe maxChars=$maxCharsSafe")

        return try {
            val result = withContext(Dispatchers.Default) {
                flowProvider().collectToTextSafely(
                    timeoutMs = timeoutMsSafe,
                    maxChars = maxCharsSafe,
                    onChunk = { chunk, _ ->
                        // Bridge: UI can show incremental output.
                        if (chunk.isNotEmpty()) {
                            streamBridge.emitChunk(sessionId, chunk)
                        }
                    }
                )
            }

            when (result.reason) {
                FlowTextStopReason.COMPLETED,
                FlowTextStopReason.MAX_CHARS -> {
                    streamBridge.end(sessionId)
                }

                FlowTextStopReason.TIMEOUT -> {
                    streamBridge.error(sessionId, "timeout")
                }

                FlowTextStopReason.ERROR -> {
                    streamBridge.error(sessionId, result.errorToken ?: "error")
                }
            }

            log("collectStreamingText: session=$sessionId stop=${result.reason} buffered=${result.text.length}")
            result.text
        } catch (ce: CancellationException) {
            // Respect structured concurrency: notify UI + rethrow.
            streamBridge.error(sessionId, "cancelled")
            log("collectStreamingText: session=$sessionId cancelled")
            throw ce
        } catch (t: Throwable) {
            // Best-effort: notify UI + return whatever we have (none here because exception escaped collector).
            streamBridge.error(sessionId, t.javaClass.simpleName.ifBlank { "error" }.take(32))
            log("collectStreamingText: session=$sessionId crashed err=${t.javaClass.simpleName}")
            ""
        }
    }

    private fun parseOutcomeOrFallback(
        questionId: String,
        raw: String,
        fallbackFollowUp: String
    ): ValidationOutcome {
        val cleaned = raw
            .replace("\u0000", "")
            .trim()

        val jsonStr = extractValidationJsonObject(cleaned)

        if (jsonStr == null) {
            log("parseOutcome: qid=${questionId.trim()} JSON not found -> fallback NEED_FOLLOW_UP")
            return ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "I couldn't reliably parse the validation result. One more detail would help.",
                followUpQuestion = fallbackFollowUp
            )
        }

        return try {
            val obj = JSONObject(jsonStr)

            val statusStr = obj.optString("status", "").trim()
            val assistantMessage = obj
                .optString("assistantMessage", "")
                .replace("\u0000", "")
                .trim()
                .ifEmpty { "Thanks." }

            val status = when (statusStr) {
                "ACCEPTED" -> ValidationStatus.ACCEPTED
                "NEED_FOLLOW_UP" -> ValidationStatus.NEED_FOLLOW_UP
                else -> ValidationStatus.NEED_FOLLOW_UP
            }

            val followUpNormalized = normalizeFollowUpQuestion(obj.optString("followUpQuestion", ""))

            if (status == ValidationStatus.ACCEPTED) {
                ValidationOutcome(
                    status = ValidationStatus.ACCEPTED,
                    assistantMessage = assistantMessage,
                    followUpQuestion = null
                )
            } else {
                ValidationOutcome(
                    status = ValidationStatus.NEED_FOLLOW_UP,
                    assistantMessage = assistantMessage,
                    followUpQuestion = followUpNormalized ?: fallbackFollowUp
                )
            }
        } catch (t: Throwable) {
            log("parseOutcome: qid=${questionId.trim()} JSON parse error -> fallback NEED_FOLLOW_UP")
            ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "Validation output was malformed. Please add one more detail.",
                followUpQuestion = fallbackFollowUp
            )
        }
    }

    /**
     * Extracts a JSON object intended for validation results.
     *
     * Policy:
     * - Scan for candidate '{...}' substrings.
     * - Prefer the first substring that parses as JSONObject and contains a non-empty "status".
     *
     * Rationale:
     * - Some models prepend non-JSON text or include braces in explanations.
     * - This reduces false positives compared to "first '{' wins".
     */
    private fun extractValidationJsonObject(text: String): String? {
        if (text.isBlank()) return null

        var cursor = 0
        while (cursor < text.length) {
            val start = text.indexOf('{', cursor)
            if (start < 0) return null

            val candidate = extractJsonObjectAt(text, start)
            if (candidate == null) {
                cursor = start + 1
                continue
            }

            // Fast filter to avoid parsing every brace block.
            if (!candidate.contains("\"status\"")) {
                cursor = start + 1
                continue
            }

            val ok = runCatching {
                val obj = JSONObject(candidate)
                obj.optString("status", "").trim().isNotEmpty()
            }.getOrDefault(false)

            if (ok) return candidate

            cursor = start + 1
        }

        return null
    }

    /**
     * Extract a JSON object substring beginning at [start] using brace depth scanning.
     *
     * Notes:
     * - Skips braces inside JSON strings.
     * - Returns the shortest balanced "{...}" starting at [start], or null if not balanced.
     */
    private fun extractJsonObjectAt(text: String, start: Int): String? {
        if (start < 0 || start >= text.length || text[start] != '{') return null

        var i = start
        var depth = 0
        var inString = false
        var escape = false

        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escape) {
                    escape = false
                } else {
                    when (c) {
                        '\\' -> escape = true
                        '"' -> inString = false
                    }
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(start, i + 1).trim()
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Normalizes a follow-up question and removes garbage values.
     *
     * Examples removed:
     * - "none", "null", "n/a", "-", "0"
     * Examples stripped prefixes:
     * - "follow-up:", "follow up:", "question:", "next question:"
     */
    private fun normalizeFollowUpQuestion(text: String?): String? {
        val raw = text ?: return null
        var t = raw.replace("\u0000", "").trim()
        if (t.isBlank()) return null

        val prefixPatterns = listOf(
            "follow-up question:",
            "follow up question:",
            "follow-up:",
            "follow up:",
            "followup:",
            "question:",
            "next question:"
        )
        val lower = t.lowercase()
        for (p in prefixPatterns) {
            if (lower.startsWith(p)) {
                t = t.drop(p.length).trim()
                break
            }
        }

        val l2 = t.lowercase()
        val garbage = setOf(
            "none", "(none)", "n/a", "na", "null", "nil",
            "no", "nope", "no follow up", "no follow-up",
            "skip", "0", "-"
        )
        if (l2 in garbage) return null
        if (t.length < 3) return null

        return t
    }

    /**
     * Detects the VM-style follow-up history format.
     *
     * Expected examples:
     * - FOLLOW_UP_1_Q: ...
     * - FOLLOW_UP_1_A: ...
     */
    private fun looksLikeFollowUpHistory(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return t.contains("FOLLOW_UP_1_A:") || t.lineSequence().any { it.trim().startsWith("FOLLOW_UP_") }
    }

    private fun log(msg: String) {
        logger?.invoke(msg)
    }
}
