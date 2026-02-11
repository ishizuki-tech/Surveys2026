/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Fake Validator)
 *  ---------------------------------------------------------------------
 *  File: FakeAnswerValidator.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Fake validator to prove the UI + state machine works.
 *
 * Goals:
 * - Provide deterministic, easy-to-reason rules that simulate an SLM validator.
 * - Support multi follow-up cycles using VM-style follow-up history format.
 * - Optionally emit streaming model output via [ChatStreamBridge] to exercise UI embedding:
 *   MODEL bubble -> End -> embedded into ASSISTANT as collapsible "Model output".
 *
 * Privacy:
 * - Never logs raw user content. Logs must remain metadata-only.
 */
class FakeAnswerValidator(
    /**
     * Minimum number of non-whitespace characters required to accept the main answer immediately.
     */
    minMainChars: Int = 12,

    /**
     * Minimum number of non-whitespace characters required for a follow-up answer to count as "useful".
     */
    minFollowUpChars: Int = 12,

    /**
     * Number of useful follow-ups required when the main answer is too short.
     *
     * Notes:
     * - This exists to validate the multi follow-up state machine end-to-end.
     */
    requiredFollowUps: Int = 3,

    /**
     * Simulated validator latency (e.g., network/compute delay).
     *
     * Notes:
     * - This is a baseline delay, independent from streaming simulation delays.
     */
    simulatedLatencyMs: Long = 250L,

    /**
     * Optional streaming bridge to simulate model output.
     *
     * Notes:
     * - When provided, this validator will emit Begin/Delta/End events with a JSON-like payload.
     * - This helps test the "MODEL -> ASSISTANT embed" pipeline without a real repository.
     */
    private val streamBridge: ChatStreamBridge? = null,

    /**
     * Streaming chunk size (characters) when [streamBridge] is enabled.
     */
    streamChunkSizeChars: Int = 2,

    /**
     * Streaming chunk delay (ms) when [streamBridge] is enabled.
     */
    streamChunkDelayMs: Long = 40L,

    /**
     * Optional debug logger. If provided, receives PII-safe messages.
     *
     * Example injection:
     *   FakeAnswerValidator(logger = { Log.d("FakeAnswerValidator", it) })
     */
    private val logger: ((String) -> Unit)? = null
) : AnswerValidator {

    private val minMainCharsSafe: Int = minMainChars.coerceAtLeast(0)
    private val minFollowUpCharsSafe: Int = minFollowUpChars.coerceAtLeast(0)
    private val requiredFollowUpsSafe: Int = requiredFollowUps.coerceAtLeast(1)
    private val simulatedLatencyMsSafe: Long = simulatedLatencyMs.coerceAtLeast(0L)

    private val streamChunkSizeSafe: Int = streamChunkSizeChars.coerceAtLeast(1)
    private val streamChunkDelayMsSafe: Long = streamChunkDelayMs.coerceAtLeast(0L)

    override suspend fun validateMain(questionId: String, answer: String): ValidationOutcome {
        delay(simulatedLatencyMsSafe)

        // Contract says inputs are normalized, but keep this stable and defensive.
        val qid = questionId.trim()
        val trimmed = answer.trim()
        val nonWsLen = nonWhitespaceLength(trimmed)

        log("validateMain: qid=$qid nonWsLen=$nonWsLen threshold=$minMainCharsSafe")

        val needFollowUp = (nonWsLen < minMainCharsSafe) || looksLikeNonAnswer(trimmed)

        return if (needFollowUp) {
            val followUp = nextFollowUpQuestion(index = 1)

            val out = ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "Thanks. I need more detail to validate your answer.",
                followUpQuestion = followUp
            )

            emitModelStreamIfEnabled(out, phase = "VALIDATE_MAIN")
            log("validateMain: NEED_FOLLOW_UP qid=$qid nonWsLen=$nonWsLen")
            out
        } else {
            val out = ValidationOutcome(
                status = ValidationStatus.ACCEPTED,
                assistantMessage = "Looks good. Thanks!",
                followUpQuestion = null
            )

            emitModelStreamIfEnabled(out, phase = "VALIDATE_MAIN")
            log("validateMain: ACCEPTED qid=$qid nonWsLen=$nonWsLen")
            out
        }
    }

    override suspend fun validateFollowUp(
        questionId: String,
        mainAnswer: String,
        followUpAnswer: String
    ): ValidationOutcome {
        delay(simulatedLatencyMsSafe)

        val qid = questionId.trim()
        val mainTrimmed = mainAnswer.trim()
        val mainNonWsLen = nonWhitespaceLength(mainTrimmed)

        val followUpAnswers = parseFollowUpAnswers(followUpAnswer)
        val useful = followUpAnswers.filter { ans ->
            val t = ans.trim()
            t.isNotBlank() &&
                    nonWhitespaceLength(t) >= minFollowUpCharsSafe &&
                    !looksLikeNonAnswer(t)
        }

        log(
            "validateFollowUp: qid=$qid mainNonWsLen=$mainNonWsLen " +
                    "followUps=${followUpAnswers.size} useful=${useful.size} " +
                    "minFU=$minFollowUpCharsSafe required=$requiredFollowUpsSafe"
        )

        // If main answer is already long enough, accept immediately.
        if (mainNonWsLen >= minMainCharsSafe && !looksLikeNonAnswer(mainTrimmed)) {
            val out = ValidationOutcome(
                status = ValidationStatus.ACCEPTED,
                assistantMessage = "Looks good. Thanks!",
                followUpQuestion = null
            )
            emitModelStreamIfEnabled(out, phase = "VALIDATE_FOLLOW_UP")
            log("validateFollowUp: ACCEPTED (main already sufficient) qid=$qid")
            return out
        }

        // If any follow-up exists but is too short or placeholder, ask to fix that slot.
        val firstBadIndex = followUpAnswers.indexOfFirst { ans ->
            val t = ans.trim()
            t.isNotEmpty() && (nonWhitespaceLength(t) < minFollowUpCharsSafe || looksLikeNonAnswer(t))
        }
        if (firstBadIndex >= 0) {
            val out = ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "That detail is still a bit vague.",
                followUpQuestion = "Could you make follow-up #${firstBadIndex + 1} more concrete (number, place, or example)?"
            )
            emitModelStreamIfEnabled(out, phase = "VALIDATE_FOLLOW_UP")
            log("validateFollowUp: NEED_FOLLOW_UP (bad slot) qid=$qid idx=${firstBadIndex + 1}")
            return out
        }

        // Require N useful follow-ups when main answer is short.
        if (useful.size < requiredFollowUpsSafe) {
            val nextIndex = useful.size + 1
            val out = ValidationOutcome(
                status = ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "Good. One more concrete detail would make this answer complete.",
                followUpQuestion = nextFollowUpQuestion(index = nextIndex)
            )
            emitModelStreamIfEnabled(out, phase = "VALIDATE_FOLLOW_UP")
            log("validateFollowUp: NEED_FOLLOW_UP qid=$qid useful=${useful.size} required=$requiredFollowUpsSafe")
            return out
        }

        val out = ValidationOutcome(
            status = ValidationStatus.ACCEPTED,
            assistantMessage = "Great. Now your answer is complete.",
            followUpQuestion = null
        )
        emitModelStreamIfEnabled(out, phase = "VALIDATE_FOLLOW_UP")
        log("validateFollowUp: ACCEPTED qid=$qid useful=${useful.size}")
        return out
    }

    /**
     * Heuristic: detect low-information placeholders that should trigger follow-up.
     *
     * Keep this conservative: we only flag obvious placeholders.
     */
    private fun looksLikeNonAnswer(s: String): Boolean {
        if (s.isBlank()) return true

        // Locale-stable normalization for simple placeholder matching.
        val t = s.trim().lowercase(Locale.ROOT)

        // Common "no answer" placeholders.
        return t == "n/a" ||
                t == "na" ||
                t == "none" ||
                t == "unknown" ||
                t == "idk" ||
                t == "-" ||
                t == "--" ||
                t == "?" ||
                t == "??"
    }

    /**
     * Counts non-whitespace characters to match the documented "non-whitespace" contract.
     */
    private fun nonWhitespaceLength(s: String): Int {
        if (s.isEmpty()) return 0
        var c = 0
        for (ch in s) {
            if (!ch.isWhitespace()) c++
        }
        return c
    }

    /**
     * Parse follow-up answers from either:
     * - VM-style history block: FOLLOW_UP_#_A: ...
     * - Single follow-up text (fallback)
     */
    private fun parseFollowUpAnswers(text: String): List<String> {
        val raw = text.trim()
        if (raw.isEmpty() || raw == "(none)") return emptyList()

        val history = extractBlock(raw, "FOLLOW_UP_HISTORY_BEGIN", "FOLLOW_UP_HISTORY_END") ?: raw

        val out = mutableListOf<String>()
        history.lineSequence().forEach { line ->
            val t = line.trim()
            if (!t.startsWith("FOLLOW_UP_")) return@forEach
            val idx = t.indexOf("_A:")
            if (idx > 0 && idx + 3 < t.length) {
                val ans = t.substring(idx + 3).trim()
                if (ans.isNotBlank()) out += ans
            }
        }

        // If it didn't look like history, treat it as a single answer.
        if (out.isEmpty()) {
            return listOf(raw).filter { it.isNotBlank() }
        }

        return out
    }

    /**
     * Extract a multi-line block between markers.
     */
    private fun extractBlock(text: String, begin: String, end: String): String? {
        val b = text.indexOf(begin)
        if (b < 0) return null
        val start = b + begin.length
        val e = text.indexOf(end, startIndex = start)
        if (e < 0) return null
        return text.substring(start, e).trim()
    }

    /**
     * Deterministic follow-up question generator.
     *
     * Notes:
     * - Keep these short and concrete to match your UI constraints.
     */
    private fun nextFollowUpQuestion(index: Int): String {
        return when (index) {
            1 -> "Add one concrete example (what/where/when)."
            2 -> "Add one measurable detail (number, duration, frequency, or cost)."
            3 -> "Clarify one constraint or exception case."
            4 -> "State one cause-and-effect detail (why it happened / why it matters)."
            else -> "Add one more concrete detail to remove ambiguity."
        }
    }

    /**
     * Emit PII-safe debug logs if a logger was provided.
     */
    private fun log(msg: String) {
        logger?.invoke(msg)
    }

    // ---------------------------------------------------------------------
    // Streaming simulation (optional)
    // ---------------------------------------------------------------------

    /**
     * Emits a JSON-like model output stream to exercise the streaming UI pipeline.
     *
     * Notes:
     * - Does NOT include user-provided content.
     * - Safe for logs and UI diagnostics.
     */
    private suspend fun emitModelStreamIfEnabled(out: ValidationOutcome, phase: String) {
        val bridge = streamBridge ?: return

        val sessionId = bridge.begin()

        val json = buildString {
            append("{")
            append("\"phase\":").append(phase.jsonQuote()).append(',')
            append("\"status\":").append(out.status.name.jsonQuote()).append(',')
            append("\"assistantMessage\":").append(out.assistantMessage.jsonQuote())
            val fu = out.followUpQuestion
            if (!fu.isNullOrBlank()) {
                append(',')
                append("\"followUpQuestion\":").append(fu.jsonQuote())
            }
            append("}")
        }

        try {
            var i = 0
            while (i < json.length) {
                val end = (i + streamChunkSizeSafe).coerceAtMost(json.length)
                bridge.emitChunk(sessionId, json.substring(i, end))
                i = end
                if (streamChunkDelayMsSafe > 0L) delay(streamChunkDelayMsSafe)
            }
            bridge.end(sessionId)
        } catch (ce: CancellationException) {
            bridge.error(sessionId, "cancelled")
            throw ce
        } catch (_: Throwable) {
            bridge.error(sessionId, "error")
        }
    }

    /**
     * JSON-string-escape and wrap with quotes.
     */
    private fun String.jsonQuote(): String {
        val s = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$s\""
    }
}
