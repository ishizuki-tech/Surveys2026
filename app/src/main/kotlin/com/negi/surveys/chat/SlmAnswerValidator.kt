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
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * AnswerValidator implementation backed by a streaming SLM repository.
 *
 * Strategy:
 * - Build a model-ready prompt via Repository.buildPrompt(userPrompt, phase).
 * - Execute Repository.request(prompt) which returns Flow<String>.
 * - Collect the stream into a single text buffer (bounded + timeout).
 * - Extract/parse a JSON object from the model output.
 *
 * Streaming:
 * - Emits Begin/Delta/End/Error to [streamBridge] so the ViewModel can render progress.
 *
 * Privacy:
 * - Never log raw answers (potentially PII). Only log metadata.
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
            followUpHistory = null
        )

        val modelPrompt = repository.buildPrompt(userPrompt, PromptPhase.VALIDATE_MAIN)
        log("validateMain: qid=$questionId (request start)")

        val result = collectStreamingTextSafely(
            flowProvider = { repository.request(modelPrompt) },
            timeoutMs = timeoutMs,
            maxChars = maxChars
        )

        log("validateMain: qid=$questionId (stop=${result.reason} chars=${result.text.length} err=${result.errorToken ?: "-"})")

        return parseOutcomeOrFallback(
            questionId = questionId,
            raw = result.text,
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
            followUpHistory = followUpAnswer
        )

        val modelPrompt = repository.buildPrompt(userPrompt, PromptPhase.VALIDATE_FOLLOW_UP)
        log("validateFollowUp: qid=$questionId (request start)")

        val result = collectStreamingTextSafely(
            flowProvider = { repository.request(modelPrompt) },
            timeoutMs = timeoutMs,
            maxChars = maxChars
        )

        log("validateFollowUp: qid=$questionId (stop=${result.reason} chars=${result.text.length} err=${result.errorToken ?: "-"})")

        return parseOutcomeOrFallback(
            questionId = questionId,
            raw = result.text,
            fallbackFollowUp = "Please add one short concrete detail so I can proceed."
        )
    }

    /**
     * Build a strict, delimiter-based prompt so multi-line fields are unambiguous.
     *
     * Requirements:
     * - JSON only output.
     * - Ask at most ONE follow-up question at a time.
     * - Do not echo full user answers.
     */
    private fun buildValidationUserPrompt(
        questionId: String,
        mainAnswer: String,
        followUpHistory: String?
    ): String {
        val historyBlock = followUpHistory?.trim().takeUnless { it.isNullOrEmpty() } ?: "(none)"

        return """
You are an answer validation assistant for a survey app.

TASK:
- Decide if MAIN_ANSWER sufficiently answers the question for QUESTION_ID.
- If insufficient, ask exactly ONE follow-up question to obtain missing detail.
- Return JSON ONLY (no markdown, no extra text).

OUTPUT JSON SCHEMA:
{
  "status": "ACCEPTED" | "NEED_FOLLOW_UP",
  "assistantMessage": "string",
  "followUpQuestion": "string (required when status=NEED_FOLLOW_UP)"
}

RULES:
- Ask at most ONE follow-up at a time.
- Do NOT include analysis or reasoning.
- Do NOT quote or echo user answers verbatim.
- Keep assistantMessage concise.

QUESTION_ID: $questionId

MAIN_ANSWER_BEGIN
${mainAnswer.trim()}
MAIN_ANSWER_END

FOLLOW_UP_HISTORY_BEGIN
$historyBlock
FOLLOW_UP_HISTORY_END
""".trimIndent()
    }

    /**
     * Collect a streaming flow safely while emitting chunks to UI.
     *
     * Design choice:
     * - Timeout / max chars / non-cancellation errors should NOT surface as a "Validation error" bubble.
     *   We end the stream and let the caller present a fallback assistant message.
     * - Cancellation is a real control signal and should propagate upward, and should also emit "cancelled"
     *   so the UI can suppress/cleanup transient stream bubbles deterministically.
     *
     * Timeout coverage:
     * - Measures flow creation time (flowProvider) and subtracts it from timeout budget.
     * - If budget is exhausted before collection begins, returns TIMEOUT immediately.
     */
    private suspend fun collectStreamingTextSafely(
        flowProvider: suspend () -> Flow<String>,
        timeoutMs: Long,
        maxChars: Int
    ): FlowTextResult {
        val sessionId = streamBridge.begin()

        try {
            val startedNs = System.nanoTime()

            val flow = try {
                flowProvider()
            } catch (ce: CancellationException) {
                streamBridge.error(sessionId, "cancelled")
                throw ce
            } catch (t: Throwable) {
                streamBridge.end(sessionId)
                return FlowTextResult(
                    text = "",
                    reason = FlowTextStopReason.ERROR,
                    errorToken = t.javaClass.simpleName.ifBlank { "error" }.take(32)
                )
            }

            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            val remainingMs = if (timeoutMs > 0L) (timeoutMs - elapsedMs).coerceAtLeast(0L) else timeoutMs

            // IMPORTANT:
            // FlowTextCollector treats timeoutMs<=0 as "no timeout".
            // If we exhausted the budget, we must not pass 0; treat as TIMEOUT immediately.
            if (timeoutMs > 0L && remainingMs <= 0L) {
                streamBridge.end(sessionId)
                return FlowTextResult(
                    text = "",
                    reason = FlowTextStopReason.TIMEOUT
                )
            }

            val result = try {
                flow.collectToTextSafely(
                    timeoutMs = remainingMs,
                    maxChars = maxChars
                ) { chunk, _ ->
                    // Keep UI output consistent with buffered content (collector already truncates).
                    streamBridge.emitChunk(sessionId, chunk)
                }
            } catch (ce: CancellationException) {
                streamBridge.error(sessionId, "cancelled")
                throw ce
            }

            streamBridge.end(sessionId)
            return result
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            streamBridge.end(sessionId)
            return FlowTextResult(
                text = "",
                reason = FlowTextStopReason.ERROR,
                errorToken = t.javaClass.simpleName.ifBlank { "error" }.take(32)
            )
        }
    }

    private fun parseOutcomeOrFallback(
        questionId: String,
        raw: String,
        fallbackFollowUp: String
    ): ValidationOutcome {
        val cleaned = raw.trim()
        val jsonStr = extractFirstJsonObject(cleaned)

        if (jsonStr == null) {
            log("parseOutcome: qid=$questionId JSON not found -> fallback NEED_FOLLOW_UP")
            return ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "I couldn't reliably parse the validation result. One more detail would help.",
                followUpQuestion = fallbackFollowUp
            )
        }

        return try {
            val obj = JSONObject(jsonStr)
            val statusStr = obj.optString("status", "").trim()
            val assistantMessage = obj.optString("assistantMessage", "").trim().ifEmpty { "Thanks." }
            val followUpQuestion = obj.optString("followUpQuestion", "").trim().ifEmpty { null }

            val status = when (statusStr) {
                "ACCEPTED" -> ValidationStatus.ACCEPTED
                "NEED_FOLLOW_UP" -> ValidationStatus.NEED_FOLLOW_UP
                else -> ValidationStatus.NEED_FOLLOW_UP
            }

            if (status == ValidationStatus.NEED_FOLLOW_UP && followUpQuestion.isNullOrEmpty()) {
                ValidationOutcome(
                    status = ValidationStatus.NEED_FOLLOW_UP,
                    assistantMessage = assistantMessage,
                    followUpQuestion = fallbackFollowUp
                )
            } else {
                ValidationOutcome(
                    status = status,
                    assistantMessage = assistantMessage,
                    followUpQuestion = followUpQuestion
                )
            }
        } catch (_: Throwable) {
            log("parseOutcome: qid=$questionId JSON parse error -> fallback NEED_FOLLOW_UP")
            ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "Validation output was malformed. Please add one more detail.",
                followUpQuestion = fallbackFollowUp
            )
        }
    }

    /**
     * Extract the first JSON object from a text blob.
     *
     * Robust-ish implementation:
     * - Finds the first '{'
     * - Scans until matching '}' with brace depth
     * - Skips braces inside JSON strings
     */
    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null

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
                        if (depth == 0) return text.substring(start, i + 1).trim()
                    }
                }
            }
            i++
        }
        return null
    }

    private fun log(msg: String) {
        logger?.invoke(msg)
    }
}
