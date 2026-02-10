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
        val cleaned = raw.trim()
        val jsonStr = extractFirstJsonObject(cleaned)

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
            val assistantMessage = obj.optString("assistantMessage", "").trim().ifEmpty { "Thanks." }

            val followUpQuestionRaw = obj.optString("followUpQuestion", "").trim()
            val followUpQuestion: String? = followUpQuestionRaw.takeIf { it.isNotEmpty() }

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
