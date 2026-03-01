/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Advanced Answer Validator)
 *  ---------------------------------------------------------------------
 *  File: AnswerValidator.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import android.os.SystemClock
import com.negi.surveys.logging.SafeLog
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
 * - This class does not log raw answers; only metadata (length/hash).
 */
class AnswerValidator(
    private val repository: RepositoryI,
    private val streamBridge: ChatStreamBridge,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxChars: Int = 32_000,
    private val logger: ((String) -> Unit)? = null
) : AnswerValidatorI {

    override suspend fun validateMain(questionId: String, answer: String): ValidationOutcome {
        return withContext(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()

            val userPrompt = buildValidationUserPrompt(
                phase = PromptPhaseTag.VALIDATE_MAIN,
                questionId = questionId,
                mainAnswer = answer,
                followUpAnswerPayload = null
            )
            val modelPrompt = repository.buildPrompt(userPrompt)

            log("validateMain: qid=${questionId.trim()} start promptChars=${modelPrompt.length}")

            val result = collectStreamingTextResult(
                promptChars = modelPrompt.length,
                flowProvider = { repository.request(modelPrompt) },
                timeoutMs = timeoutMs,
                maxChars = maxChars
            )

            val elapsed = SystemClock.elapsedRealtime() - t0
            log("validateMain: qid=${questionId.trim()} stop=${result.reason} chars=${result.text.length} elapsedMs=$elapsed")

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
            val t0 = SystemClock.elapsedRealtime()

            val userPrompt = buildValidationUserPrompt(
                phase = PromptPhaseTag.VALIDATE_FOLLOW_UP,
                questionId = questionId,
                mainAnswer = mainAnswer,
                followUpAnswerPayload = followUpAnswer
            )
            val modelPrompt = repository.buildPrompt(userPrompt)

            log("validateFollowUp: qid=${questionId.trim()} start promptChars=${modelPrompt.length}")

            val result = collectStreamingTextResult(
                promptChars = modelPrompt.length,
                flowProvider = { repository.request(modelPrompt) },
                timeoutMs = timeoutMs,
                maxChars = maxChars
            )

            val elapsed = SystemClock.elapsedRealtime() - t0
            log("validateFollowUp: qid=${questionId.trim()} stop=${result.reason} chars=${result.text.length} elapsedMs=$elapsed")

            parseOutcomeOrFallback(
                questionId = questionId,
                raw = result.text,
                stopReason = result.reason,
                errorToken = result.errorToken,
                fallbackFollowUp = "Please add one short concrete detail so I can proceed."
            )
        }
    }

    // ---------------------------------------------------------------------
    // Prompt construction
    // ---------------------------------------------------------------------

    /**
     * Build a strict JSON-only prompt with backward-compatible marker blocks.
     *
     * Why markers:
     * - FakeSlmRepository extracts MAIN_ANSWER via MAIN_ANSWER_BEGIN/END.
     * - It can parse follow-ups via FOLLOW_UP_ANSWER_BEGIN/END or FOLLOW_UP_HISTORY_BEGIN/END.
     */
    private fun buildValidationUserPrompt(
        phase: PromptPhaseTag,
        questionId: String,
        mainAnswer: String,
        followUpAnswerPayload: String?
    ): String {
        val qid = questionId.trim()
        val main = mainAnswer.trim()

        val fuRaw = followUpAnswerPayload?.trim().orEmpty()
        val hasFollowUp = fuRaw.isNotBlank()
        val treatAsHistory = hasFollowUp && looksLikeFollowUpHistory(fuRaw)

        val extracted = if (hasFollowUp) extractLatestFollowUpTurn(fuRaw) else null
        val latestA = extracted?.answer?.trim().orEmpty()
        val latestQ = extracted?.question?.trim().orEmpty()

        if (hasFollowUp) {
            // Do NOT log raw answers. Only metadata.
            val sha8 = sha256Hex(latestA).take(8)
            log(
                "followUpDetected: phase=${phase.tag} treatAsHistory=$treatAsHistory " +
                        "latestQlen=${latestQ.length} latestAlen=${latestA.length} latestAsha8=$sha8"
            )
        }

        val followUpSection = if (!hasFollowUp) {
            ""
        } else {
            buildString {
                // Single follow-up (most recent answer). Fake repo supports this block.
                appendLine("FOLLOW_UP_ANSWER_BEGIN")
                if (latestA.isNotBlank()) appendLine(latestA)
                appendLine("FOLLOW_UP_ANSWER_END")
                appendLine()

                // Optional: include the follow-up question as extra context (not required by fake repo).
                appendLine("FOLLOW_UP_QUESTION:")
                if (latestQ.isNotBlank()) appendLine(latestQ)
                appendLine()

                // History block. Fake repo supports parsing FOLLOW_UP_n_A from this.
                if (treatAsHistory) {
                    appendLine("FOLLOW_UP_HISTORY_BEGIN")
                    appendLine(fuRaw)
                    appendLine("FOLLOW_UP_HISTORY_END")
                    appendLine()
                }
            }
        }

        // IMPORTANT:
        // Rules MUST explicitly allow use of follow-ups, and we must provide PHASE tag in user prompt
        // so FakeSlmRepository can detect it even if repository.buildPrompt(...) wrapper changes.
        return """
Return exactly ONE JSON object and nothing else.
- No markdown, no code fences, no backticks.
- Output must start with "{" and end with "}".
- Do not repeat the user's full answer.

Valid shapes:
1) {"status":"ACCEPTED","assistantMessage":"..."}
2) {"status":"NEED_FOLLOW_UP","assistantMessage":"...","followUpQuestion":"..."}

Rules:
- Evaluate sufficiency using the COMBINED information:
  MAIN_ANSWER plus FOLLOW_UP_ANSWER (and FOLLOW_UP_HISTORY if present).
- If the combined information is sufficient: ACCEPTED.
- Else: NEED_FOLLOW_UP and ask exactly ONE concise follow-up question.
- If FOLLOW_UP_ANSWER is non-empty, do NOT repeat the same follow-up question again.
  Either ACCEPTED, or ask a DIFFERENT and more specific follow-up question.

PHASE=${phase.tag}
QUESTION_ID: $qid

MAIN_ANSWER_BEGIN
$main
MAIN_ANSWER_END

$followUpSection
""".trimIndent()
    }

    private enum class PromptPhaseTag(val tag: String) {
        VALIDATE_MAIN("VALIDATE_MAIN"),
        VALIDATE_FOLLOW_UP("VALIDATE_FOLLOW_UP")
    }

    // ---------------------------------------------------------------------
    // Streaming collection
    // ---------------------------------------------------------------------

    private suspend fun collectStreamingTextResult(
        promptChars: Int,
        flowProvider: suspend () -> Flow<String>,
        timeoutMs: Long,
        maxChars: Int
    ): FlowTextResult {
        val maxCharsSafe = max(0, maxChars)
        val timeoutMsSafe = max(0L, timeoutMs)

        val sessionId = streamBridge.begin()
        log("collectStreamingText: session=$sessionId promptChars=$promptChars timeoutMs=$timeoutMsSafe maxChars=$maxCharsSafe")

        val coalescer = StreamChunkCoalescer(
            minEmitChars = 64,
            maxEmitIntervalMs = 50L,
            maxBufferedChars = 2048
        )

        return try {
            val result = flowProvider().collectToTextResult(
                timeoutMs = timeoutMsSafe,
                maxChars = maxCharsSafe,
                onChunk = onChunk@{ chunk, _ ->
                    if (chunk.isEmpty()) return@onChunk

                    val out = coalescer.onChunk(chunk)
                    if (!out.isNullOrEmpty()) {
                        streamBridge.emitChunk(sessionId, out)
                    }
                }
            )
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

    // ---------------------------------------------------------------------
    // Parsing outcome
    // ---------------------------------------------------------------------

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
            val msg = when (stopReason) {
                FlowTextStopReason.TIMEOUT ->
                    "Validation timed out. Please add one more detail."
                FlowTextStopReason.MAX_CHARS ->
                    "Validation output was too long and got clipped. Please add one more concrete detail."
                FlowTextStopReason.ERROR ->
                    when (errorToken) {
                        "ModelNotReadyException" -> "Model is still warming up. Please try again in a moment."
                        else -> "Validation failed due to a runtime error. Please try again."
                    }
                FlowTextStopReason.COMPLETED ->
                    "I couldn't read the validation result. One more detail would help."
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
            val msg = when (stopReason) {
                FlowTextStopReason.TIMEOUT ->
                    "Validation timed out before producing a full result. One more detail would help."
                FlowTextStopReason.MAX_CHARS ->
                    "Validation output got clipped before a full JSON result appeared. One more detail would help."
                FlowTextStopReason.ERROR ->
                    "Validation failed before producing a parseable result. Please add one more detail."
                FlowTextStopReason.COMPLETED ->
                    "I couldn't reliably parse the validation result. One more detail would help."
            }

            log("parseOutcome: qid=${questionId.trim()} jsonNotFound -> stop=$stopReason")
            return ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = msg,
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
            log("parseOutcome: qid=${questionId.trim()} jsonParseError -> fallback NEED_FOLLOW_UP stop=$stopReason")
            ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "Validation output was malformed. Please add one more detail.",
                followUpQuestion = fallbackFollowUp
            )
        }
    }

    /**
     * Extract a single JSON object from a blob (tolerant to prefixed garbage).
     *
     * Strategy:
     * - Scan for '{' candidates.
     * - Extract balanced JSON object using a small state machine (strings + escapes).
     * - Validate it contains "status" and is parseable via JSONObject.
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

    // ---------------------------------------------------------------------
    // Follow-up payload parsing
    // ---------------------------------------------------------------------

    /**
     * Heuristic: detect whether a text blob looks like follow-up history content.
     *
     * Supports:
     * - VM legacy lines: FOLLOW_UP_1_Q: ... / FOLLOW_UP_1_A: ...
     * - Payloads that contain FOLLOW_UP_TURNS + those lines.
     */
    private fun looksLikeFollowUpHistory(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        val re = Regex("""^FOLLOW_UP_\d+_[QA]:\s*.*$""")
        return raw.lineSequence().any { line -> re.matches(line.trim()) }
    }

    /**
     * Extract the latest follow-up turn from the payload built by the VM.
     *
     * Important:
     * - CURRENT_FOLLOW_UP_Q/A can be multi-line (user may insert newlines).
     * - Continuation lines are collected until the next marker line begins.
     */
    private fun extractLatestFollowUpTurn(payload: String): FollowUpTurnExtract? {
        val lines = payload.replace("\u0000", "").split('\n')
        if (lines.isEmpty()) return null

        val currentQRe = Regex("""^CURRENT_FOLLOW_UP_Q:\s*(.*)$""")
        val currentARe = Regex("""^CURRENT_FOLLOW_UP_A:\s*(.*)$""")

        val qRe = Regex("""^FOLLOW_UP_(\d+)_Q:\s*(.*)$""")
        val aRe = Regex("""^FOLLOW_UP_(\d+)_A:\s*(.*)$""")

        val anyMarkerRe = Regex(
            """^(CURRENT_FOLLOW_UP_[QA]|FOLLOW_UP_\d+_[QA]|FOLLOW_UP_TURNS:|FOLLOW_UP_HISTORY_BEGIN|FOLLOW_UP_HISTORY_END)\s*:?.*$"""
        )

        var currentQ: String? = null
        var currentA: String? = null

        var latestQ: String? = null
        var latestA: String? = null
        var latestIdx = -1

        fun readContinuation(startIndex: Int, head: String): Pair<String, Int> {
            val buf = StringBuilder().append(head)
            var j = startIndex + 1
            while (j < lines.size && !anyMarkerRe.matches(lines[j].trim())) {
                buf.append('\n').append(lines[j])
                j++
            }
            return buf.toString() to j
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()

            currentQRe.matchEntire(t)?.let { m ->
                val (body, nextIdx) = readContinuation(i, m.groupValues[1])
                currentQ = body
                i = nextIdx
                continue
            }

            currentARe.matchEntire(t)?.let { m ->
                val (body, nextIdx) = readContinuation(i, m.groupValues[1])
                currentA = body
                i = nextIdx
                continue
            }

            val qm = qRe.matchEntire(t)
            if (qm != null) {
                val idx = qm.groupValues[1].toIntOrNull() ?: -1
                val (body, nextIdx) = readContinuation(i, qm.groupValues[2])
                if (idx >= latestIdx) {
                    latestIdx = idx
                    latestQ = body
                }
                i = nextIdx
                continue
            }

            val am = aRe.matchEntire(t)
            if (am != null) {
                val idx = am.groupValues[1].toIntOrNull() ?: -1
                val (body, nextIdx) = readContinuation(i, am.groupValues[2])
                if (idx >= latestIdx) {
                    latestIdx = idx
                    latestA = body
                }
                i = nextIdx
                continue
            }

            i += 1
        }

        val q = (currentQ ?: latestQ).orEmpty().trim()
        val a = (currentA ?: latestA).orEmpty().trim()

        if (q.isBlank() && a.isBlank()) return null
        return FollowUpTurnExtract(question = q, answer = a)
    }

    private data class FollowUpTurnExtract(
        val question: String,
        val answer: String
    )

    // ---------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))

        val hex = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt()
            sb.append(hex[(v ushr 4) and 0xF])
            sb.append(hex[v and 0xF])
        }
        return sb.toString()
    }

    /**
     * Coalesce streaming chunks to reduce UI event pressure.
     *
     * Guarantees:
     * - Does not drop data (only batches).
     * - Flushes on buffer cap, time interval, chunk size, or newline.
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