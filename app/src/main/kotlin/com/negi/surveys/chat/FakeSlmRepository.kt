/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Fake SLM Repository)
 *  ---------------------------------------------------------------------
 *  File: FakeSlmRepository.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Prompt phases used by [Repository.buildPrompt] for validation flows.
 *
 * NOTE:
 * - Keep this enum consistent across the validator/repository stack.
 * - If you already have this enum in another file, remove the duplicate from here.
 */
enum class PromptPhase {
    VALIDATE_MAIN,
    VALIDATE_FOLLOW_UP
}

/**
 * Repository interface used by [SlmAnswerValidator].
 *
 * NOTE:
 * - If you already have this interface in another file, remove the duplicate from here.
 */
interface Repository {
    /** Execute a single streaming inference for the given [prompt]. */
    suspend fun request(prompt: String): Flow<String>

    /** Build the full model-ready prompt string from a user-level [userPrompt]. */
    fun buildPrompt(userPrompt: String): String

    /** Phase-aware prompt building. */
    fun buildPrompt(userPrompt: String, phase: PromptPhase): String = buildPrompt(userPrompt)
}

/**
 * A minimal fake "SLM" repository that returns streaming chunks as Flow<String>.
 *
 * Goals:
 * - Behave like a streaming LLM backend (chunk stream).
 * - Produce deterministic JSON-only outputs compatible with SlmAnswerValidator.
 * - Support BOTH follow-up formats used across SmallSteps:
 *   (A) Single follow-up:
 *       FOLLOW_UP_ANSWER_BEGIN ... FOLLOW_UP_ANSWER_END
 *   (B) Multi follow-up history:
 *       FOLLOW_UP_HISTORY_BEGIN ... FOLLOW_UP_HISTORY_END
 *       with lines like "FOLLOW_UP_1_A: ..."
 *
 * Test hooks:
 * - requestSetupDelayMs: simulate slow request preparation (before Flow is returned).
 * - throwOnRequestSetup / throwAfterEmits: inject failures to exercise ERROR paths.
 *
 * Privacy:
 * - Do NOT log raw prompts/answers. If you pass [logger], keep it metadata-only.
 */
class FakeSlmRepository(
    private val config: Config = Config()
) : Repository {

    data class Config(
        /** Delay before returning the Flow to simulate request preparation latency. */
        val requestSetupDelayMs: Long = 0L,

        /** If true, throw during request setup (before Flow is returned). */
        val throwOnRequestSetup: Boolean = false,

        /** If >= 0, throw after emitting this many chunks (0 => throw before first emit). */
        val throwAfterEmits: Int = -1,

        /** Delay per chunk to simulate streaming latency. */
        val chunkDelayMs: Long = 80L,

        /** Chunk size in characters emitted per Flow emission. Must be >= 1. */
        val chunkSizeChars: Int = 2,

        /**
         * If true, sometimes prefix output with non-JSON text to test validator robustness.
         *
         * Implementation detail:
         * - We decide "sometimes" deterministically using a prompt fingerprint,
         *   so tests are reproducible across runs.
         */
        val occasionallyPrependNonJson: Boolean = false,

        /** Add a trailing newline after JSON. */
        val appendTrailingNewline: Boolean = true,

        /** Minimum chars for main answer to avoid needing follow-ups. */
        val minMainAnswerChars: Int = 12,

        /** Base required follow-ups when main answer is short. */
        val requiredFollowUps: Int = 3,

        /** Minimum chars per follow-up answer to be considered "useful". */
        val minFollowUpAnswerChars: Int = 12,

        /** Optional metadata logger (do not log raw prompts/answers). */
        val logger: ((String) -> Unit)? = null
    ) {
        /** Normalize config to safe operational values. */
        fun normalized(): Config {
            return copy(
                requestSetupDelayMs = requestSetupDelayMs.coerceAtLeast(0L),
                throwAfterEmits = throwAfterEmits,
                chunkDelayMs = chunkDelayMs.coerceAtLeast(0L),
                chunkSizeChars = chunkSizeChars.coerceAtLeast(1),
                minMainAnswerChars = minMainAnswerChars.coerceAtLeast(1),
                requiredFollowUps = requiredFollowUps.coerceAtLeast(1),
                minFollowUpAnswerChars = minFollowUpAnswerChars.coerceAtLeast(1)
            )
        }
    }

    private val cfg: Config = config.normalized()

    override fun buildPrompt(userPrompt: String): String {
        // NOTE:
        // - This wrapper simulates a "system + user" envelope.
        // - Keep it strict: JSON-only, single object, no analysis.
        return """
SYSTEM:
You are a JSON-only assistant.
Output MUST be exactly one JSON object.
No markdown. No extra text. No analysis.

USER:
$userPrompt
""".trimIndent()
    }

    override fun buildPrompt(userPrompt: String, phase: PromptPhase): String {
        val phaseTag = when (phase) {
            PromptPhase.VALIDATE_MAIN -> "VALIDATE_MAIN"
            PromptPhase.VALIDATE_FOLLOW_UP -> "VALIDATE_FOLLOW_UP"
        }
        return """
SYSTEM:
You are a JSON-only assistant.
Output MUST be exactly one JSON object.
No markdown. No extra text. No analysis.
PHASE=$phaseTag

USER:
$userPrompt
""".trimIndent()
    }

    override suspend fun request(prompt: String): Flow<String> {
        val promptLen = prompt.length
        val setupDelay = cfg.requestSetupDelayMs

        cfg.logger?.invoke("FakeSlmRepository.request: setup (chars=$promptLen delayMs=$setupDelay)")

        if (cfg.throwOnRequestSetup) {
            throw IllegalStateException("fake_request_setup_error")
        }

        if (setupDelay > 0L) {
            delay(setupDelay)
        }

        val safeChunkSize = cfg.chunkSizeChars
        val safeChunkDelay = cfg.chunkDelayMs
        val throwAfter = cfg.throwAfterEmits

        return flow {
            cfg.logger?.invoke("FakeSlmRepository.request: stream start")

            val phase = detectPhase(prompt)

            // Extract the main answer from delimiter blocks (multi-line safe).
            val mainAnswer = extractBlockAny(
                text = prompt,
                candidates = listOf(
                    MarkerPair("MAIN_ANSWER_BEGIN", "MAIN_ANSWER_END")
                )
            ).orEmpty()

            // Extract follow-ups from either:
            // (A) history block or (B) single follow-up block.
            val followUpHistory = extractBlockAny(
                text = prompt,
                candidates = listOf(
                    MarkerPair("FOLLOW_UP_HISTORY_BEGIN", "FOLLOW_UP_HISTORY_END")
                )
            )

            val singleFollowUp = extractBlockAny(
                text = prompt,
                candidates = listOf(
                    MarkerPair("FOLLOW_UP_ANSWER_BEGIN", "FOLLOW_UP_ANSWER_END")
                )
            )

            val followUpAnswers = when {
                !followUpHistory.isNullOrBlank() -> parseFollowUpAnswersFromHistory(followUpHistory)

                !singleFollowUp.isNullOrBlank() -> {
                    val s = singleFollowUp.trim()
                    // If the single follow-up block actually contains a VM-generated history
                    // like "FOLLOW_UP_1_A: ...", split it into multiple answers.
                    if (looksLikeFollowUpHistory(s)) {
                        parseFollowUpAnswersFromHistory(s)
                    } else {
                        listOf(s).filter { it.isNotBlank() }
                    }
                }

                else -> emptyList()
            }

            // Decide whether to prepend non-JSON for robustness testing.
            val prependNonJson = shouldPrependNonJson(prompt)

            // Apply fake "validation" logic.
            val json = when (phase) {
                PromptPhase.VALIDATE_MAIN -> validateMain(mainAnswer, followUpAnswers)
                PromptPhase.VALIDATE_FOLLOW_UP -> validateFollowUp(mainAnswer, followUpAnswers)
            }

            val finalText = buildString {
                if (prependNonJson) {
                    // Intentionally invalid output for robustness testing.
                    append("analysis: (fake) streaming output begins\n")
                }
                append(json)
                if (cfg.appendTrailingNewline) append("\n")
            }

            cfg.logger?.invoke(
                "FakeSlmRepository.request: meta phase=$phase mainLen=${mainAnswer.trim().length} " +
                        "followUps=${followUpAnswers.size} outChars=${finalText.length} prependNonJson=$prependNonJson"
            )

            var emitCount = 0
            var i = 0

            // Stream as chunks.
            while (i < finalText.length) {
                if (throwAfter >= 0 && emitCount >= throwAfter) {
                    throw RuntimeException("fake_stream_error")
                }

                val end = min(i + safeChunkSize, finalText.length)
                emit(finalText.substring(i, end))
                emitCount++

                i = end
                if (safeChunkDelay > 0L) delay(safeChunkDelay)
            }

            cfg.logger?.invoke("FakeSlmRepository.request: stream done (chunks=$emitCount)")
        }
    }

    private fun detectPhase(prompt: String): PromptPhase {
        return when {
            prompt.contains("PHASE=VALIDATE_FOLLOW_UP") -> PromptPhase.VALIDATE_FOLLOW_UP
            prompt.contains("PHASE=VALIDATE_MAIN") -> PromptPhase.VALIDATE_MAIN
            else -> PromptPhase.VALIDATE_MAIN
        }
    }

    /**
     * Fake validation logic for main answer.
     *
     * Behavior:
     * - If main answer is long enough, ACCEPTED.
     * - Otherwise, ask follow-up #1.
     */
    private fun validateMain(mainAnswer: String, followUpAnswers: List<String>): String {
        val a = mainAnswer.trim()

        return if (a.length >= cfg.minMainAnswerChars) {
            jsonAccepted("Looks good. Thanks!")
        } else {
            val q = nextFollowUpQuestion(index = 1)
            jsonNeedFollowUp(
                assistantMessage = "Thanks. I need more detail to validate your answer.",
                followUpQuestion = q
            )
        }
    }

    /**
     * Fake validation logic for follow-up cycles.
     *
     * Behavior:
     * - If main answer is already long enough, ACCEPTED immediately.
     * - Otherwise, require N follow-ups (N may scale slightly based on main length).
     * - Each follow-up must be "useful" (min length) to count.
     *
     * Strategy (deterministic):
     * - If any provided follow-up is non-empty but too short, request clarification of that slot first.
     * - Otherwise, if not enough useful follow-ups, ask the next follow-up question.
     */
    private fun validateFollowUp(mainAnswer: String, followUpAnswers: List<String>): String {
        val a = mainAnswer.trim()
        val requiresFollowUp = a.length < cfg.minMainAnswerChars

        if (!requiresFollowUp) {
            return jsonAccepted("Looks good. Thanks!")
        }

        val required = requiredFollowUpsForMain(a)

        // First, if any answer is present but too short, ask to clarify it (more stable than adding new questions).
        val shortIdx = followUpAnswers.indexOfFirst {
            val t = it.trim()
            t.isNotEmpty() && t.length < cfg.minFollowUpAnswerChars
        }
        if (shortIdx >= 0) {
            val q = "Could you make follow-up #${shortIdx + 1} more concrete (numbers, place, or example)?"
            return jsonNeedFollowUp(
                assistantMessage = "That detail is still a bit vague.",
                followUpQuestion = q
            )
        }

        val useful = followUpAnswers.filter { it.trim().length >= cfg.minFollowUpAnswerChars }

        if (useful.size < required) {
            val q = nextFollowUpQuestion(index = useful.size + 1)
            return jsonNeedFollowUp(
                assistantMessage = "Good. One more concrete detail would make this answer complete.",
                followUpQuestion = q
            )
        }

        return jsonAccepted("Great. Now your answer is complete.")
    }

    /**
     * Compute required follow-ups based on how short the main answer is.
     *
     * Rationale:
     * - For extremely short answers, require slightly more detail to simulate stricter validation.
     * - Keep this deterministic and simple for SmallStep testing.
     */
    private fun requiredFollowUpsForMain(main: String): Int {
        val base = cfg.requiredFollowUps.coerceAtLeast(1)
        val veryShortThreshold = (cfg.minMainAnswerChars / 2).coerceAtLeast(1)
        return if (main.length < veryShortThreshold) base + 1 else base
    }

    private fun nextFollowUpQuestion(index: Int): String {
        return when (index) {
            1 -> "Add one concrete example (what/where/when)."
            2 -> "Add one measurable detail (number, duration, frequency, or cost)."
            3 -> "Clarify one constraint or exception case."
            4 -> "State one cause-and-effect detail (why it happened / why it matters)."
            else -> "Add one more concrete detail to remove ambiguity."
        }
    }

    private fun jsonAccepted(msg: String): String {
        return """
{
  "status": "ACCEPTED",
  "assistantMessage": ${msg.jsonQuote()}
}
""".trimIndent()
    }

    private fun jsonNeedFollowUp(assistantMessage: String, followUpQuestion: String): String {
        return """
{
  "status": "NEED_FOLLOW_UP",
  "assistantMessage": ${assistantMessage.jsonQuote()},
  "followUpQuestion": ${followUpQuestion.jsonQuote()}
}
""".trimIndent()
    }

    // ---------------------------------------------------------------------
    // Block extraction helpers
    // ---------------------------------------------------------------------

    private data class MarkerPair(val begin: String, val end: String)

    /**
     * Extract a multi-line block between markers (line-bounded).
     *
     * Why line-bounded:
     * - Prevent accidental matches if a user answer contains marker substrings.
     * - Treat markers as standalone lines only.
     */
    private fun extractBlock(text: String, begin: String, end: String): String? {
        var inside = false
        val out = StringBuilder()

        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (!inside) {
                if (t == begin) inside = true
                return@forEach
            }

            if (t == end) {
                return out.toString().trim()
            }

            out.append(line).append('\n')
        }

        return null
    }

    /**
     * Try multiple marker pairs and return the first match.
     *
     * Why:
     * - Different SmallSteps may evolve prompt markers.
     * - The fake repository should be tolerant so integration doesn't break.
     */
    private fun extractBlockAny(text: String, candidates: List<MarkerPair>): String? {
        for (p in candidates) {
            val v = extractBlock(text, p.begin, p.end)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Follow-up parsing
    // ---------------------------------------------------------------------

    /**
     * Parse follow-up answers from the history string produced by the VM/validator.
     *
     * Expected format examples:
     *   FOLLOW_UP_1_Q: ...
     *   FOLLOW_UP_1_A: ...
     *   FOLLOW_UP_2_Q: ...
     *   FOLLOW_UP_2_A: ...
     *
     * Notes:
     * - We only collect "_A:" blocks.
     * - Supports multi-line answers: continuation lines are included until the next "FOLLOW_UP_" marker.
     * - Keeps behavior deterministic and simple for SmallStep testing.
     */
    private fun parseFollowUpAnswersFromHistory(history: String): List<String> {
        val h = history.trim()
        if (h.isEmpty() || h == "(none)") return emptyList()

        val out = mutableListOf<String>()
        val current = StringBuilder()

        fun flushCurrent() {
            val s = current.toString().trim()
            if (s.isNotBlank()) out += s
            current.setLength(0)
        }

        h.lineSequence().forEach { line ->
            val t = line.trim()

            // Start of a new follow-up marker.
            if (t.startsWith("FOLLOW_UP_")) {
                // If we were collecting an answer, flush it when a new marker begins.
                if (current.isNotEmpty()) flushCurrent()

                val idx = t.indexOf("_A:")
                if (idx > 0 && idx + 3 <= t.length) {
                    val ans = t.substring(idx + 3).trim()
                    if (ans.isNotBlank()) {
                        current.append(ans)
                    }
                }
                return@forEach
            }

            // Continuation line (multi-line answer body).
            if (current.isNotEmpty()) {
                current.append('\n').append(line)
            }
        }

        if (current.isNotEmpty()) flushCurrent()
        return out
    }

    /**
     * Heuristic: detect whether a text blob looks like VM follow-up history content.
     */
    private fun looksLikeFollowUpHistory(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return t.contains("FOLLOW_UP_") && t.contains("_A:")
    }

    // ---------------------------------------------------------------------
    // Robustness testing behavior
    // ---------------------------------------------------------------------

    /**
     * Decide whether to prepend non-JSON text deterministically.
     *
     * Rationale:
     * - Random behavior is annoying in tests.
     * - Deterministic "pseudo-random" based on prompt fingerprint is reproducible.
     */
    private fun shouldPrependNonJson(prompt: String): Boolean {
        if (!cfg.occasionallyPrependNonJson) return false
        val fp = promptFingerprint(prompt)
        // Roughly 1 out of 5 requests will prepend non-JSON text.
        return (fp % 5) == 0
    }

    /**
     * A tiny, deterministic fingerprint for a prompt string.
     *
     * NOTE:
     * - This is NOT cryptographic. It is only used to drive reproducible fake behaviors.
     */
    private fun promptFingerprint(prompt: String): Int {
        var h = 17
        val step = (prompt.length / 64).coerceAtLeast(1)
        var i = 0
        while (i < prompt.length) {
            h = 31 * h + prompt[i].code
            i += step
        }
        return h
    }

    // ---------------------------------------------------------------------
    // JSON escaping
    // ---------------------------------------------------------------------

    /**
     * JSON-string-escape and wrap with quotes.
     *
     * Notes:
     * - Escapes common control chars.
     * - Escapes any other control character (< 0x20) as \\uXXXX.
     * - Keeps output safe for org.json parsing in the validator.
     */
    private fun String.jsonQuote(): String {
        val sb = StringBuilder(this.length + 2)
        sb.append('"')
        for (ch in this) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}