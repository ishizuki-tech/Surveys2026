/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Advanced Answer Validator)
 *  ---------------------------------------------------------------------
 *  File: AdvancedAnswerValidator.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import android.os.SystemClock
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Advanced AnswerValidator implementation backed by a streaming Repository.
 *
 * Design:
 * - Uses Repository.buildPrompt(userPrompt) to keep prompt composition centralized.
 * - Uses Repository.request(prompt) which returns Flow<String>.
 * - Collects stream into bounded text (timeout + maxChars).
 * - Extracts a single JSON object and returns ValidationOutcome.
 *
 * Streaming UI:
 * - Sends Begin/Delta/End/Error into ChatStreamBridge.
 *
 * Privacy note:
 * - This class does not log raw answers, but underlying native libs may log prompts
 *   in debug builds depending on their log configuration.
 */
class AdvancedAnswerValidator(
    private val repository: Repository,
    private val streamBridge: ChatStreamBridge,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxChars: Int = 32_000,
    private val logger: ((String) -> Unit)? = null
) : AnswerValidator {

    override suspend fun validateMain(questionId: String, answer: String): ValidationOutcome {
        return withContext(Dispatchers.Default) {
            val userPrompt = buildValidationUserPrompt(
                questionId = questionId,
                mainAnswer = answer,
                followUpAnswer = null
            )
            val modelPrompt = repository.buildPrompt(userPrompt)

            log("validateMain: qid=${questionId.trim()} start promptChars=${modelPrompt.length}")

            val result = collectStreamingTextResult(
                promptChars = modelPrompt.length,
                flowProvider = { repository.request(modelPrompt) },
                timeoutMs = timeoutMs,
                maxChars = maxChars
            )

            log("validateMain: qid=${questionId.trim()} stop=${result.reason} chars=${result.text.length}")

            parseOutcomeOrFallback(
                questionId = questionId,
                raw = result.text,
                stopReason = result.reason,
                errorToken = result.errorToken,
                fallbackFollowUp = "Could you add one concrete detail or example?"
            )
        }
    }

    override suspend fun validateFollowUp(
        questionId: String,
        mainAnswer: String,
        followUpAnswer: String
    ): ValidationOutcome {
        return withContext(Dispatchers.Default) {
            val userPrompt = buildValidationUserPrompt(
                questionId = questionId,
                mainAnswer = mainAnswer,
                followUpAnswer = followUpAnswer
            )
            val modelPrompt = repository.buildPrompt(userPrompt)

            log("validateFollowUp: qid=${questionId.trim()} start promptChars=${modelPrompt.length}")

            val result = collectStreamingTextResult(
                promptChars = modelPrompt.length,
                flowProvider = { repository.request(modelPrompt) },
                timeoutMs = timeoutMs,
                maxChars = maxChars
            )

            log("validateFollowUp: qid=${questionId.trim()} stop=${result.reason} chars=${result.text.length}")

            parseOutcomeOrFallback(
                questionId = questionId,
                raw = result.text,
                stopReason = result.reason,
                errorToken = result.errorToken,
                fallbackFollowUp = "Please add one short concrete detail so I can proceed."
            )
        }
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
        val treatAsHistory = hasFollowUp && looksLikeFollowUpHistory(fuRaw)

        val followUpBlock = if (!hasFollowUp) {
            "FOLLOW_UP_ANSWER:\n"
        } else if (treatAsHistory) {
            "FOLLOW_UP_HISTORY:\n$fuRaw\n"
        } else {
            "FOLLOW_UP_ANSWER:\n$fuRaw\n"
        }

        // Keep this prompt short to reduce prefill cost on CPU.
        // Also explicitly forbid backticks to avoid ```json fences.
        return """
Return exactly ONE JSON object and nothing else.
- No markdown, no code fences, no backticks.
- Output must start with "{" and end with "}".
- Do not repeat the user's full answer.

Valid shapes:
1) {"status":"ACCEPTED","assistantMessage":"..."}
2) {"status":"NEED_FOLLOW_UP","assistantMessage":"...","followUpQuestion":"..."}

Rules:
- If MAIN_ANSWER is sufficient: ACCEPTED.
- Else: NEED_FOLLOW_UP and ask exactly ONE concise follow-up question.

QUESTION_ID: $qid
MAIN_ANSWER:
$main

$followUpBlock
""".trimIndent()
    }

    private suspend fun collectStreamingTextResult(
        promptChars: Int,
        flowProvider: suspend () -> kotlinx.coroutines.flow.Flow<String>,
        timeoutMs: Long,
        maxChars: Int
    ): FlowTextResult {
        val maxCharsSafe = maxChars.coerceAtLeast(0)
        val timeoutMsSafe = timeoutMs.coerceAtLeast(0L)

        val sessionId = streamBridge.begin()
        log("collectStreamingText: session=$sessionId promptChars=$promptChars timeoutMs=$timeoutMsSafe maxChars=$maxCharsSafe")

        val coalescer = StreamChunkCoalescer(
            minEmitChars = 64,
            maxEmitIntervalMs = 50L,
            maxBufferedChars = 2048
        )

        return try {
            // NOTE:
            // - validateMain/validateFollowUp already run on Dispatchers.Default.
            // - Avoid nested withContext(Dispatchers.Default) here to reduce dispatcher churn.
            val result = flowProvider().collectToTextSafely(
                timeoutMs = timeoutMsSafe,
                maxChars = maxCharsSafe,
                onChunk = { chunk, _ ->
                    if (chunk.isEmpty()) return@collectToTextSafely

                    val out = coalescer.onChunk(chunk)
                    if (!out.isNullOrEmpty()) {
                        streamBridge.emitChunk(sessionId, out)
                    }
                }
            )

            // Flush buffered text BEFORE emitting End/Error to avoid "missing last chunk" in UI.
            coalescer.flush(force = true)?.let { tail ->
                if (tail.isNotEmpty()) {
                    streamBridge.emitChunk(sessionId, tail)
                }
            }

            when (result.reason) {
                FlowTextStopReason.COMPLETED -> streamBridge.end(sessionId)
                FlowTextStopReason.MAX_CHARS -> streamBridge.end(sessionId)
                FlowTextStopReason.TIMEOUT -> streamBridge.error(sessionId, "timeout")
                FlowTextStopReason.ERROR -> streamBridge.error(sessionId, result.errorToken ?: "error")
            }

            result
        } catch (ce: CancellationException) {
            runCatching {
                coalescer.flush(force = true)?.let { tail ->
                    if (tail.isNotEmpty()) {
                        streamBridge.emitChunk(sessionId, tail)
                    }
                }
            }
            runCatching { streamBridge.error(sessionId, "cancelled") }
            log("collectStreamingText: session=$sessionId cancelled")
            throw ce
        } catch (t: Throwable) {
            val token = t.javaClass.simpleName.ifBlank { "error" }.take(32)

            runCatching {
                coalescer.flush(force = true)?.let { tail ->
                    if (tail.isNotEmpty()) {
                        streamBridge.emitChunk(sessionId, tail)
                    }
                }
            }
            runCatching { streamBridge.error(sessionId, token) }

            log("collectStreamingText: session=$sessionId crashed err=$token")
            FlowTextResult(text = "", reason = FlowTextStopReason.ERROR, errorToken = token)
        }
    }

    private fun parseOutcomeOrFallback(
        questionId: String,
        raw: String,
        stopReason: FlowTextStopReason,
        errorToken: String?,
        fallbackFollowUp: String
    ): ValidationOutcome {
        val cleaned = raw
            .replace("\u0000", "")
            .replace("\uFEFF", "")
            .trim()

        if (cleaned.isBlank()) {
            val msg = when {
                stopReason == FlowTextStopReason.TIMEOUT -> "Validation timed out. Please add one more detail."
                stopReason == FlowTextStopReason.ERROR && errorToken == "ModelNotReadyException" ->
                    "Model is still warming up. Please try again in a moment."
                stopReason == FlowTextStopReason.ERROR -> "Validation failed due to a runtime error. Please try again."
                else -> "I couldn't read the validation result. One more detail would help."
            }
            log("parseOutcome: qid=${questionId.trim()} empty -> stop=$stopReason token=$errorToken")
            return ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = msg,
                followUpQuestion = fallbackFollowUp
            )
        }

        val jsonStr = extractValidationJsonObject(cleaned)
        if (jsonStr == null) {
            log("parseOutcome: qid=${questionId.trim()} jsonNotFound -> fallback NEED_FOLLOW_UP")
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
        } catch (_: Throwable) {
            log("parseOutcome: qid=${questionId.trim()} jsonParseError -> fallback NEED_FOLLOW_UP")
            ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "Validation output was malformed. Please add one more detail.",
                followUpQuestion = fallbackFollowUp
            )
        }
    }

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
                        if (depth == 0) return text.substring(start, i + 1).trim()
                        if (depth < 0) return null
                    }
                }
            }
            i++
        }
        return null
    }

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

        val lower0 = t.lowercase(Locale.US)
        for (p in prefixPatterns) {
            if (lower0.startsWith(p)) {
                t = t.drop(p.length).trim()
                break
            }
        }

        val l2 = t.lowercase(Locale.US)
        val garbage = setOf(
            "none", "(none)", "n/a", "na", "null", "nil",
            "no", "nope", "no follow up", "no follow-up",
            "skip", "0", "-"
        )
        if (l2 in garbage) return null
        if (t.length < 3) return null

        return t
    }

    private fun looksLikeFollowUpHistory(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        val re = Regex("""^FOLLOW_UP_\d+_[QA]:\s*.*$""")
        return raw.lineSequence().any { line -> re.matches(line.trim()) }
    }

    /**
     * Coalesces streaming chunks to reduce UI churn.
     *
     * Rationale:
     * - Token-level updates can trigger frequent Compose recompositions + autoscroll.
     * - Coalescing makes the UI smoother, especially during CPU/XNNPack execution.
     *
     * Notes:
     * - Intended for sequential use by a single collector coroutine.
     */
    private class StreamChunkCoalescer(
        private val minEmitChars: Int,
        private val maxEmitIntervalMs: Long,
        private val maxBufferedChars: Int
    ) {
        private val buffer = StringBuilder()
        private var lastEmitMs: Long = SystemClock.uptimeMillis()

        fun onChunk(chunk: String): String? {
            buffer.append(chunk)

            // Guard: avoid producing a single massive UI update chunk.
            if (maxBufferedChars > 0 && buffer.length >= maxBufferedChars) {
                return flushInternal(SystemClock.uptimeMillis())
            }

            val now = SystemClock.uptimeMillis()
            val timeReady = (now - lastEmitMs) >= maxEmitIntervalMs
            val sizeReady = buffer.length >= minEmitChars
            val newlineReady = chunk.indexOf('\n') >= 0

            if (timeReady || sizeReady || newlineReady) {
                return flushInternal(now)
            }
            return null
        }

        fun flush(force: Boolean): String? {
            if (buffer.isEmpty()) return null

            if (!force) {
                val now = SystemClock.uptimeMillis()
                val timeReady = (now - lastEmitMs) >= maxEmitIntervalMs
                val sizeReady = buffer.length >= minEmitChars
                if (!timeReady && !sizeReady) return null
                return flushInternal(now)
            }

            return flushInternal(SystemClock.uptimeMillis())
        }

        private fun flushInternal(nowMs: Long): String? {
            if (buffer.isEmpty()) return null
            val out = buffer.toString()
            buffer.setLength(0)
            lastEmitMs = nowMs
            return out
        }
    }

    private fun log(msg: String) {
        logger?.invoke(msg)
    }

    private companion object {
        private const val DEFAULT_TIMEOUT_MS = 90_000L
    }
}