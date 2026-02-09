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
 */
enum class PromptPhase {
    VALIDATE_MAIN,
    VALIDATE_FOLLOW_UP
}

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
 * Key improvements:
 * - Understands delimiter-based multi-line blocks:
 *   MAIN_ANSWER_BEGIN/END and FOLLOW_UP_HISTORY_BEGIN/END
 * - Can ask multiple follow-ups:
 *   returns NEED_FOLLOW_UP repeatedly until requirements are met.
 *
 * Test hooks:
 * - requestSetupDelayMs: simulate slow request preparation (before Flow is returned).
 * - throwOnRequestSetup / throwAfterEmits: inject failures to exercise ERROR paths.
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

        /** If true, prefix output with non-JSON text (tests validator robustness). */
        val occasionallyPrependNonJson: Boolean = false,

        /** Add a trailing newline after JSON. */
        val appendTrailingNewline: Boolean = true,

        /** Minimum chars for main answer to avoid needing follow-ups. */
        val minMainAnswerChars: Int = 12,

        /** Required number of follow-ups when main answer is short. */
        val requiredFollowUps: Int = 3,

        /** Minimum chars per follow-up answer to be considered "useful". */
        val minFollowUpAnswerChars: Int = 12,

        /** Optional metadata logger (do not log raw prompts/answers). */
        val logger: ((String) -> Unit)? = null
    )

    override fun buildPrompt(userPrompt: String): String {
        return """
SYSTEM:
You are a JSON-only assistant. Output must be a single JSON object.

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
You are a JSON-only assistant. Output must be a single JSON object. PHASE=$phaseTag

USER:
$userPrompt
""".trimIndent()
    }

    override suspend fun request(prompt: String): Flow<String> {
        val promptLen = prompt.length
        val setupDelay = config.requestSetupDelayMs.coerceAtLeast(0L)

        config.logger?.invoke("FakeSlmRepository.request: setup (chars=$promptLen delayMs=$setupDelay)")

        if (config.throwOnRequestSetup) {
            throw IllegalStateException("fake_request_setup_error")
        }

        if (setupDelay > 0L) {
            delay(setupDelay)
        }

        val safeChunkSize = config.chunkSizeChars.coerceAtLeast(1)
        val safeChunkDelay = config.chunkDelayMs.coerceAtLeast(0L)
        val throwAfter = config.throwAfterEmits

        return flow {
            config.logger?.invoke("FakeSlmRepository.request: stream start")

            val phase = detectPhase(prompt)

            // Extract multi-line blocks.
            val mainAnswer = extractBlock(prompt, "MAIN_ANSWER_BEGIN", "MAIN_ANSWER_END") ?: ""
            val history = extractBlock(prompt, "FOLLOW_UP_HISTORY_BEGIN", "FOLLOW_UP_HISTORY_END") ?: "(none)"

            val followUpAnswers = parseFollowUpAnswers(history)

            val json = when (phase) {
                PromptPhase.VALIDATE_MAIN -> validateMain(mainAnswer, followUpAnswers)
                PromptPhase.VALIDATE_FOLLOW_UP -> validateFollowUp(mainAnswer, followUpAnswers)
            }

            val finalText = buildString {
                if (config.occasionallyPrependNonJson) {
                    append("analysis: (fake) streaming output begins\n")
                }
                append(json)
                if (config.appendTrailingNewline) append("\n")
            }

            var emitCount = 0

            // Stream as chunks.
            var i = 0
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

            config.logger?.invoke("FakeSlmRepository.request: stream done (outChars=${finalText.length})")
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

        return if (a.length >= config.minMainAnswerChars) {
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
     * - If enough follow-ups exist AND each follow-up is "useful" (min length), ACCEPTED.
     * - Else ask the next follow-up question.
     */
    private fun validateFollowUp(mainAnswer: String, followUpAnswers: List<String>): String {
        val a = mainAnswer.trim()

        val requiresFollowUp = a.length < config.minMainAnswerChars
        if (!requiresFollowUp) {
            // Main answer alone is good; follow-up phase should immediately accept.
            return jsonAccepted("Looks good. Thanks!")
        }

        // If we don't yet have enough follow-up answers, ask next.
        val n = followUpAnswers.size
        if (n < config.requiredFollowUps) {
            val q = nextFollowUpQuestion(index = n + 1)
            return jsonNeedFollowUp(
                assistantMessage = "Good. One more detail would make this answer complete.",
                followUpQuestion = q
            )
        }

        // If any follow-up is too short, ask for a more concrete detail.
        val shortIdx = followUpAnswers.indexOfFirst { it.trim().length < config.minFollowUpAnswerChars }
        if (shortIdx >= 0) {
            val q = "Could you make follow-up #${shortIdx + 1} more concrete (numbers, place, or example)?"
            return jsonNeedFollowUp(
                assistantMessage = "That detail is still a bit vague.",
                followUpQuestion = q
            )
        }

        return jsonAccepted("Great. Now your answer is complete.")
    }

    private fun nextFollowUpQuestion(index: Int): String {
        return when (index) {
            1 -> "Add one concrete example (what/where/when)."
            2 -> "Add one measurable detail (number, duration, frequency, or cost)."
            3 -> "Clarify one constraint or exception case."
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

    /**
     * Extract a multi-line block between markers.
     *
     * Example:
     *   MAIN_ANSWER_BEGIN
     *   ...text...
     *   MAIN_ANSWER_END
     */
    private fun extractBlock(text: String, begin: String, end: String): String? {
        val b = text.indexOf(begin)
        if (b < 0) return null
        val e = text.indexOf(end, startIndex = b + begin.length)
        if (e < 0) return null

        val start = b + begin.length
        return text.substring(start, e).trim()
    }

    /**
     * Parse follow-up answers from the history string produced by the VM.
     *
     * Expected format (example):
     * FOLLOW_UP_1_Q: ...
     * FOLLOW_UP_1_A: ...
     * FOLLOW_UP_2_Q: ...
     * FOLLOW_UP_2_A: ...
     */
    private fun parseFollowUpAnswers(history: String): List<String> {
        val h = history.trim()
        if (h.isEmpty() || h == "(none)") return emptyList()

        val out = mutableListOf<String>()
        h.lineSequence().forEach { line ->
            val t = line.trim()
            val idx = t.indexOf("_A:")
            if (idx > 0 && t.startsWith("FOLLOW_UP_")) {
                out += t.substring(idx + 3).trim()
            }
        }
        return out
    }

    /**
     * JSON-string-escape and wrap with quotes.
     */
    private fun String.jsonQuote(): String {
        val s = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        return "\"$s\""
    }
}
