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
 * Privacy note:
 * - This class does not log raw answers; only metadata (length/hash).
 *
 * Threading note:
 * - ChatStreamBridge producer APIs are thread-safe; do NOT force Main thread hopping here.
 */
class AnswerValidator(
    private val repository: ChatValidation.RepositoryI,
    private val streamBridge: ChatStreamBridge,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxChars: Int = 32_000,
    private val logger: ((String) -> Unit)? = null,
) : ChatValidation.AnswerValidatorI {

    override suspend fun validateMain(
        questionId: String,
        answer: String,
    ): ChatModels.ValidationOutcome {
        return withContext(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            val phase = ChatValidation.PromptPhase.VALIDATE_MAIN

            val userPrompt = buildValidationUserPrompt(
                phase = phase,
                questionId = questionId,
                mainAnswer = answer,
                followUpAnswerPayload = null,
            )
            val modelPrompt = repository.buildPrompt(userPrompt, phase)

            log("validateMain: qid=${questionId.trim()} start promptChars=${modelPrompt.length}")

            val result = collectStreamingTextResult(
                promptChars = modelPrompt.length,
                flowProvider = { repository.request(modelPrompt) },
                timeoutMs = timeoutMs,
                maxChars = maxChars,
            )

            val elapsed = SystemClock.elapsedRealtime() - t0
            log(
                "validateMain: qid=${questionId.trim()} stop=${result.reason} " +
                        "chars=${result.text.length} elapsedMs=$elapsed",
            )

            parseOutcomeOrFallback(
                questionId = questionId,
                raw = result.text,
                stopReason = result.reason,
                errorToken = result.errorToken,
                fallbackFollowUp = "Could you add one concrete detail or example?",
            )
        }
    }

    override suspend fun validateFollowUp(
        questionId: String,
        mainAnswer: String,
        followUpAnswer: String,
    ): ChatModels.ValidationOutcome {
        return withContext(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            val phase = ChatValidation.PromptPhase.VALIDATE_FOLLOW_UP

            val userPrompt = buildValidationUserPrompt(
                phase = phase,
                questionId = questionId,
                mainAnswer = mainAnswer,
                followUpAnswerPayload = followUpAnswer,
            )
            val modelPrompt = repository.buildPrompt(userPrompt, phase)

            log("validateFollowUp: qid=${questionId.trim()} start promptChars=${modelPrompt.length}")

            val result = collectStreamingTextResult(
                promptChars = modelPrompt.length,
                flowProvider = { repository.request(modelPrompt) },
                timeoutMs = timeoutMs,
                maxChars = maxChars,
            )

            val elapsed = SystemClock.elapsedRealtime() - t0
            log(
                "validateFollowUp: qid=${questionId.trim()} stop=${result.reason} " +
                        "chars=${result.text.length} elapsedMs=$elapsed",
            )

            parseOutcomeOrFallback(
                questionId = questionId,
                raw = result.text,
                stopReason = result.reason,
                errorToken = result.errorToken,
                fallbackFollowUp = "Please add one short concrete detail so I can proceed.",
            )
        }
    }

    // ---------------------------------------------------------------------
    // Prompt construction
    // ---------------------------------------------------------------------

    /**
     * Build a strict JSON-only prompt with backward-compatible marker blocks.
     *
     * Important:
     * - Phase is provided via Repository.buildPrompt(userPrompt, phase).
     * - Do NOT embed "PHASE=..." lines here to avoid contract drift.
     */
    private fun buildValidationUserPrompt(
        phase: ChatValidation.PromptPhase,
        questionId: String,
        mainAnswer: String,
        followUpAnswerPayload: String?,
    ): String {
        val qid = questionId.trim()
        val main = mainAnswer.trim()

        val fuRaw = followUpAnswerPayload?.trim().orEmpty()
        val hasFollowUp = fuRaw.isNotBlank()

        val treatAsHistory = hasFollowUp && looksLikeFollowUpHistory(fuRaw)

        val extracted: FollowUpTurnExtract? = if (hasFollowUp && treatAsHistory) {
            extractLatestFollowUpTurn(fuRaw)
        } else {
            null
        }

        val followUpAnswerText: String = when {
            !hasFollowUp -> ""
            extracted != null && extracted.answer.isNotBlank() -> extracted.answer.trim()
            !treatAsHistory -> fuRaw
            else -> fuRaw
        }

        val followUpQuestionText: String = when {
            extracted != null && extracted.question.isNotBlank() -> extracted.question.trim()
            else -> ""
        }

        if (hasFollowUp) {
            val sha8 = sha256Hex(followUpAnswerText).take(8)
            log(
                "followUpDetected: phase=${phase.name} treatAsHistory=$treatAsHistory " +
                        "latestQlen=${followUpQuestionText.length} latestAlen=${followUpAnswerText.length} latestAsha8=$sha8",
            )
        }

        val followUpSection = if (!hasFollowUp) {
            ""
        } else {
            val clippedA = followUpAnswerText.safeTrimAndClip(MAX_FOLLOW_UP_ANSWER_CHARS)
            val clippedQ = followUpQuestionText.safeTrimAndClip(MAX_FOLLOW_UP_QUESTION_CHARS)
            val clippedHistory = if (treatAsHistory) fuRaw.safeTrimAndClip(MAX_FOLLOW_UP_HISTORY_CHARS) else ""

            buildString {
                appendLine("FOLLOW_UP_ANSWER_BEGIN")
                if (clippedA.isNotBlank()) appendLine(clippedA)
                appendLine("FOLLOW_UP_ANSWER_END")
                appendLine()

                appendLine("FOLLOW_UP_QUESTION:")
                if (clippedQ.isNotBlank()) appendLine(clippedQ)
                appendLine()

                if (treatAsHistory) {
                    appendLine("FOLLOW_UP_HISTORY_BEGIN")
                    if (clippedHistory.isNotBlank()) appendLine(clippedHistory)
                    appendLine("FOLLOW_UP_HISTORY_END")
                    appendLine()
                }
            }
        }

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

QUESTION_ID: $qid

MAIN_ANSWER_BEGIN
$main
MAIN_ANSWER_END

$followUpSection
""".trimIndent()
    }

    // ---------------------------------------------------------------------
    // Streaming collection
    // ---------------------------------------------------------------------

    /**
     * Collect streaming text with bounded output.
     *
     * Threading:
     * - ChatStreamBridge producer APIs are thread-safe; do not force Main thread hops here.
     */
    private suspend fun collectStreamingTextResult(
        promptChars: Int,
        flowProvider: suspend () -> Flow<String>,
        timeoutMs: Long,
        maxChars: Int,
    ): FlowTextCollector.Result {
        val maxCharsSafe = max(0, maxChars)
        val timeoutMsSafe = max(0L, timeoutMs)

        val sessionId = streamBridge.begin()
        log("collectStreamingText: session=$sessionId promptChars=$promptChars timeoutMs=$timeoutMsSafe maxChars=$maxCharsSafe")

        val coalescer = StreamChunkCoalescer(
            minEmitChars = 64,
            maxEmitIntervalMs = 50L,
            maxBufferedChars = 2048,
        )

        return try {
            val result = FlowTextCollector.run {
                flowProvider().collectToTextResult(
                    timeoutMs = timeoutMsSafe,
                    maxChars = maxCharsSafe,
                    onChunk = { chunk, _ ->
                        if (chunk.isEmpty()) return@collectToTextResult

                        val out = coalescer.onChunk(chunk)
                        if (!out.isNullOrEmpty()) {
                            runCatching { streamBridge.emitChunk(sessionId, out) }
                        }
                    },
                )
            }

            coalescer.flush(force = true)?.let { tail ->
                if (tail.isNotEmpty()) {
                    runCatching { streamBridge.emitChunk(sessionId, tail) }
                }
            }

            when (result.reason) {
                FlowTextCollector.StopReason.COMPLETED -> streamBridge.end(sessionId)
                FlowTextCollector.StopReason.MAX_CHARS -> streamBridge.end(sessionId)
                FlowTextCollector.StopReason.TIMEOUT -> streamBridge.error(sessionId, ChatStreamEvent.Codes.TIMEOUT)
                FlowTextCollector.StopReason.ERROR -> streamBridge.error(
                    sessionId,
                    result.errorToken ?: ChatStreamEvent.Codes.ERROR,
                )
            }

            result
        } catch (ce: CancellationException) {
            runCatching {
                coalescer.flush(force = true)?.let { tail ->
                    if (tail.isNotEmpty()) streamBridge.emitChunk(sessionId, tail)
                }
            }
            runCatching { streamBridge.error(sessionId, ChatStreamEvent.Codes.CANCELLED) }
            log("collectStreamingText: session=$sessionId cancelled")
            throw ce
        } catch (t: Throwable) {
            val token = t.javaClass.simpleName.ifBlank { ChatStreamEvent.Codes.ERROR }.take(32)

            runCatching {
                coalescer.flush(force = true)?.let { tail ->
                    if (tail.isNotEmpty()) streamBridge.emitChunk(sessionId, tail)
                }
            }
            runCatching { streamBridge.error(sessionId, token) }

            log("collectStreamingText: session=$sessionId crashed err=$token")
            FlowTextCollector.Result(
                text = "",
                reason = FlowTextCollector.StopReason.ERROR,
                errorToken = token,
            )
        }
    }

    // ---------------------------------------------------------------------
    // Parsing outcome
    // ---------------------------------------------------------------------

    private fun parseOutcomeOrFallback(
        questionId: String,
        raw: String,
        stopReason: FlowTextCollector.StopReason,
        errorToken: String?,
        fallbackFollowUp: String,
    ): ChatModels.ValidationOutcome {
        val cleaned = raw
            .replace("\u0000", "")
            .replace("\uFEFF", "")
            .trim()

        if (cleaned.isBlank()) {
            val msg = when (stopReason) {
                FlowTextCollector.StopReason.TIMEOUT ->
                    "Validation timed out. Please add one more detail."
                FlowTextCollector.StopReason.MAX_CHARS ->
                    "Validation output was too long and got clipped. Please add one more concrete detail."
                FlowTextCollector.StopReason.ERROR ->
                    when (errorToken) {
                        "ModelNotReadyException" -> "Model is still warming up. Please try again in a moment."
                        else -> "Validation failed due to a runtime error. Please try again."
                    }
                FlowTextCollector.StopReason.COMPLETED ->
                    "I couldn't read the validation result. One more detail would help."
            }

            log("parseOutcome: qid=${questionId.trim()} empty -> stop=$stopReason token=$errorToken")
            return ChatModels.ValidationOutcome(
                status = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "",
                followUpQuestion = fallbackFollowUp,
                evalStatus = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                evalScore = null,
                evalReason = msg,
            )
        }

        val jsonObjects = extractJsonObjectsBestEffort(cleaned)
        val eval = jsonObjects.mapNotNull { parseEvalJsonBestEffort(it) }.lastOrNull()
        val verdict = jsonObjects.mapNotNull { parseVerdictJsonBestEffort(it) }.lastOrNull()

        if (eval == null && verdict == null) {
            val msg = when (stopReason) {
                FlowTextCollector.StopReason.TIMEOUT ->
                    "Validation timed out before producing a full result. One more detail would help."
                FlowTextCollector.StopReason.MAX_CHARS ->
                    "Validation output got clipped before a full JSON result appeared. One more detail would help."
                FlowTextCollector.StopReason.ERROR ->
                    "Validation failed before producing a parseable result. Please add one more detail."
                FlowTextCollector.StopReason.COMPLETED ->
                    "I couldn't reliably parse the validation result. One more detail would help."
            }

            log("parseOutcome: qid=${questionId.trim()} jsonNotFound -> stop=$stopReason")
            return ChatModels.ValidationOutcome(
                status = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "",
                followUpQuestion = fallbackFollowUp,
                evalStatus = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                evalScore = null,
                evalReason = msg,
            )
        }

        val finalStatus =
            verdict?.status
                ?: eval?.status
                ?: ChatModels.ValidationStatus.NEED_FOLLOW_UP

        val finalAssistantMessage =
            verdict?.assistantMessage
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                .orEmpty()

        val finalFollowUp = when (finalStatus) {
            ChatModels.ValidationStatus.ACCEPTED -> null
            ChatModels.ValidationStatus.NEED_FOLLOW_UP ->
                normalizeFollowUpQuestion(verdict?.followUpQuestion) ?: fallbackFollowUp
        }

        val finalEvalStatus =
            eval?.status
                ?: verdict?.status
                ?: finalStatus

        return ChatModels.ValidationOutcome(
            status = finalStatus,
            assistantMessage = finalAssistantMessage,
            followUpQuestion = finalFollowUp,
            evalStatus = finalEvalStatus,
            evalScore = eval?.score,
            evalReason = eval?.reason,
        )
    }

    private data class EvalJson(
        val status: ChatModels.ValidationStatus?,
        val score: Int?,
        val reason: String?,
    )

    private data class VerdictJson(
        val status: ChatModels.ValidationStatus?,
        val assistantMessage: String?,
        val followUpQuestion: String?,
    )

    private fun parseEvalJsonBestEffort(json: String): EvalJson? {
        if (json.isBlank()) return null

        return runCatching {
            val obj = JSONObject(json)
            if (!obj.has("score")) return null

            val status = parseStatus(obj.optString("status", "").trim())
            val score = parseOptionalScore(obj)
            val reason = obj.optString("reason", "").replace("\u0000", "").trim().ifBlank { null }

            if (status == null && score == null && reason == null) return null
            EvalJson(
                status = status,
                score = score,
                reason = reason,
            )
        }.getOrNull()
    }

    private fun parseVerdictJsonBestEffort(json: String): VerdictJson? {
        if (json.isBlank()) return null

        return runCatching {
            val obj = JSONObject(json)

            val assistantMessage =
                obj.optString("assistantMessage", "")
                    .replace("\u0000", "")
                    .trim()
                    .ifBlank { null }

            val followUpQuestion =
                obj.optString("followUpQuestion", "")
                    .replace("\u0000", "")
                    .trim()
                    .ifBlank { null }

            val status = parseStatus(obj.optString("status", "").trim())

            if (assistantMessage == null && followUpQuestion == null && status == null) return null

            VerdictJson(
                status = status,
                assistantMessage = assistantMessage,
                followUpQuestion = followUpQuestion,
            )
        }.getOrNull()
    }

    private fun parseStatus(statusStr: String): ChatModels.ValidationStatus? {
        return when (statusStr) {
            "ACCEPTED" -> ChatModels.ValidationStatus.ACCEPTED
            "NEED_FOLLOW_UP" -> ChatModels.ValidationStatus.NEED_FOLLOW_UP
            else -> null
        }
    }

    private fun parseOptionalScore(obj: JSONObject): Int? {
        if (!obj.has("score")) return null

        return runCatching {
            when (val raw = obj.get("score")) {
                is Number -> raw.toInt()
                is String -> raw.trim().toInt()
                else -> null
            }
        }.getOrNull()?.coerceIn(0, 100)
    }

    /**
     * Extract complete JSON objects from a mixed text blob.
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
                        out.add(text.substring(start, i + 1).trim())
                        start = -1
                    }
                }
            }
        }

        return out
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
            "next question:",
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
            "skip", "0", "-",
        )
        if (l2 in garbage) return null
        if (t.length < 3) return null

        return t
    }

    // ---------------------------------------------------------------------
    // Follow-up payload parsing
    // ---------------------------------------------------------------------

    private fun looksLikeFollowUpHistory(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        return raw.lineSequence().any { line -> FOLLOW_UP_HISTORY_LINE_RE.matches(line.trim()) }
    }

    private fun extractLatestFollowUpTurn(payload: String): FollowUpTurnExtract? {
        val normalized = payload.replace("\u0000", "").replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n')
        if (lines.isEmpty()) return null

        var currentQ: String? = null
        var currentA: String? = null

        var latestQ: String? = null
        var latestA: String? = null
        var latestIdx = -1

        fun readContinuation(startIndex: Int, head: String): Pair<String, Int> {
            val buf = StringBuilder().append(head)
            var j = startIndex + 1
            while (j < lines.size && !ANY_MARKER_RE.matches(lines[j].trim())) {
                buf.append('\n').append(lines[j])
                j++
            }
            return buf.toString() to j
        }

        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()

            CURRENT_Q_RE.matchEntire(t)?.let { m ->
                val (body, nextIdx) = readContinuation(i, m.groupValues[1])
                currentQ = body
                i = nextIdx
                continue
            }

            CURRENT_A_RE.matchEntire(t)?.let { m ->
                val (body, nextIdx) = readContinuation(i, m.groupValues[1])
                currentA = body
                i = nextIdx
                continue
            }

            val qm = TURN_Q_RE.matchEntire(t)
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

            val am = TURN_A_RE.matchEntire(t)
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

            i++
        }

        val q = (currentQ ?: latestQ).orEmpty().trim()
        val a = (currentA ?: latestA).orEmpty().trim()

        if (q.isBlank() && a.isBlank()) return null
        return FollowUpTurnExtract(
            question = q,
            answer = a,
        )
    }

    private data class FollowUpTurnExtract(
        val question: String,
        val answer: String,
    )

    // ---------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))

        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt()
            sb.append(HEX[(v ushr 4) and 0xF])
            sb.append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    /**
     * Coalesce streaming chunks to reduce UI event pressure.
     *
     * Guarantees:
     * - Does not drop data.
     * - Flushes on buffer cap, time interval, chunk size, or newline.
     */
    private class StreamChunkCoalescer(
        private val minEmitChars: Int,
        private val maxEmitIntervalMs: Long,
        private val maxBufferedChars: Int,
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
        logger?.invoke(msg) ?: SafeLog.d(TAG, msg)
    }

    /**
     * Trim + clip to [limit] UTF-16 code units while preserving surrogate pairs.
     */
    private fun String.safeTrimAndClip(limit: Int): String {
        val t = trim()
        val n = limit.coerceAtLeast(0)
        if (n == 0) return ""
        if (t.length <= n) return t

        var end = n
        if (end > 0) {
            val last = t[end - 1]
            val next = t[end]
            if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) {
                end -= 1
            }
        }
        if (end <= 0) return ""
        return t.substring(0, end)
    }

    private companion object {
        private const val TAG = "AnswerValidator"
        private const val DEFAULT_TIMEOUT_MS = 90_000L

        private const val MAX_FOLLOW_UP_ANSWER_CHARS = 8_000
        private const val MAX_FOLLOW_UP_QUESTION_CHARS = 2_000
        private const val MAX_FOLLOW_UP_HISTORY_CHARS = 16_000

        private const val HEX = "0123456789abcdef"

        private val FOLLOW_UP_HISTORY_LINE_RE = Regex("""^FOLLOW_UP_\d+_[QA]:\s*.*$""")
        private val CURRENT_Q_RE = Regex("""^CURRENT_FOLLOW_UP_Q:\s*(.*)$""")
        private val CURRENT_A_RE = Regex("""^CURRENT_FOLLOW_UP_A:\s*(.*)$""")
        private val TURN_Q_RE = Regex("""^FOLLOW_UP_(\d+)_Q:\s*(.*)$""")
        private val TURN_A_RE = Regex("""^FOLLOW_UP_(\d+)_A:\s*(.*)$""")

        private val ANY_MARKER_RE = Regex(
            """^(CURRENT_FOLLOW_UP_[QA]|FOLLOW_UP_\d+_[QA]|FOLLOW_UP_TURNS:|FOLLOW_UP_HISTORY_BEGIN|FOLLOW_UP_HISTORY_END)\s*:?.*$""",
        )
    }
}