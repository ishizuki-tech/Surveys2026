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

import com.negi.surveys.chat.FlowTextCollector.collectToTextResultWithBudget
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A fake "SLM" repository that can mimic both:
 * - One-step generation: stream natural text output.
 * - Two-step pipeline (real-like):
 *    Step 1 (Eval, internal): score judgment JSON.
 *    Step 2 (Main, streamed): follow-up question JSON (or ACCEPTED).
 *
 * Goals:
 * - Behave like a streaming LLM backend (delta chunk stream).
 * - Provide deterministic behaviors for testing pipelines.
 * - Keep prompt building explicit and readable (EvalPrompt vs FollowUpPrompt).
 *
 * Privacy:
 * - Do NOT log raw prompts/answers. If you pass [logger], keep it metadata-only.
 */
class FakeSlmRepository(
    config: Config = Config(),
    /** Shared prompt builder to keep prompt contract aligned with the real repository. */
    private val promptBuilder: SlmPromptBuilderI = DefaultSlmPromptBuilder,
) : ChatValidation.RepositoryI {

    data class Config(
        /** Delay before returning the Flow to simulate request preparation latency. */
        val requestSetupDelayMs: Long = 0L,

        /** Delay before emitting the first chunk to simulate time-to-first-token latency. */
        val firstChunkDelayMs: Long = 0L,

        /** Delay per chunk to simulate streaming latency. */
        val chunkDelayMs: Long = 0L,

        /** Chunk size in characters emitted per Flow emission. Must be >= 1. */
        val chunkSizeChars: Int = 32,

        /** If true, throw during request setup (before Flow is returned). */
        val throwOnRequestSetup: Boolean = false,

        /**
         * If >= 0, throw after emitting this many chunks (0 => throw before first emit).
         *
         * Notes:
         * - Applies to the *returned* stream (one-step output or step-2 stream).
         */
        val throwAfterEmits: Int = -1,

        /**
         * If true, inject failure into the internal Step-1 Eval collection stream.
         *
         * Notes:
         * - Useful to test: eval timeout / eval failure / fallback behaviors.
         */
        val throwOnEvalInternal: Boolean = false,

        /** If >= 0, throw after this many chunks in the internal Eval stream. */
        val throwAfterEmitsOnEvalInternal: Int = -1,

        /**
         * If true, sometimes prefix output with non-JSON text to test robustness.
         *
         * Deterministic using a seed fingerprint, so tests are reproducible.
         */
        val occasionallyPrependNonJson: Boolean = false,

        /** Add a trailing newline after JSON. */
        val appendTrailingNewline: Boolean = true,

        /** Enable the repository-level two-step behavior like real SlmRepository. */
        val enableTwoStepEval: Boolean = true,

        /** Reset conversation per request (no-op in fake, but logged as meta). */
        val resetConversationEachRequest: Boolean = true,

        /** Step-1 Eval: timeout budget for internal collection. */
        val evalTimeoutMs: Long = 8_000L,

        /** Step-1 Eval: max chars collected. */
        val evalMaxChars: Int = 8_192,

        /** Step-2 FollowUp: max chars for streamed JSON (defensive). */
        val followUpMaxChars: Int = 4_096,

        /** Score threshold: score >= threshold => ACCEPTED. */
        val acceptScoreThreshold: Int = 70,

        /** Optional metadata logger (do not log raw prompts/answers). */
        val logger: ((String) -> Unit)? = null,
    ) {
        /** Normalize config to safe operational values. */
        fun normalized(): Config {
            return copy(
                requestSetupDelayMs = requestSetupDelayMs.coerceAtLeast(0L),
                firstChunkDelayMs = firstChunkDelayMs.coerceAtLeast(0L),
                chunkDelayMs = chunkDelayMs.coerceAtLeast(0L),
                chunkSizeChars = chunkSizeChars.coerceAtLeast(1),
                evalTimeoutMs = evalTimeoutMs.coerceAtLeast(1L),
                evalMaxChars = evalMaxChars.coerceAtLeast(128),
                followUpMaxChars = followUpMaxChars.coerceAtLeast(128),
                acceptScoreThreshold = acceptScoreThreshold.coerceIn(0, 100),
            )
        }
    }

    private val cfg: Config = config.normalized()

    override fun buildPrompt(userPrompt: String): String {
        // Compatibility: legacy callers may use this.
        // IMPORTANT: Keep this I/O-free and aligned with the real prompt builder contract.
        return promptBuilder.buildAnswerLikePrompt(systemPrompt = null, userPrompt = userPrompt)
    }

    override fun buildPrompt(userPrompt: String, phase: ChatValidation.PromptPhase): String {
        // IMPORTANT:
        // - Do NOT introduce a nullable overload (PromptPhase?) because JVM signatures collide.
        // This is a legacy validator-style prompt path; it is intentionally NOT shared with the real repository.
        return buildValidatorStylePrompt(userPrompt = userPrompt, phase = phase)
    }

    override suspend fun request(prompt: String): Flow<String> {
        val raw = prompt.trim()
        if (raw.isBlank()) return emptyFlow()

        val setupDelay = cfg.requestSetupDelayMs
        val firstDelay = cfg.firstChunkDelayMs
        cfg.logger?.invoke(
            "FakeSlmRepository.request: setup (chars=${raw.length} setupDelayMs=$setupDelay firstChunkDelayMs=$firstDelay twoStep=${cfg.enableTwoStepEval})",
        )

        if (cfg.throwOnRequestSetup) {
            throw IllegalStateException("fake_request_setup_error")
        }

        if (setupDelay > 0L) delay(setupDelay)

        // If an explicit PHASE marker exists, behave like "validator-style" request and stream JSON directly.
        // This keeps old tests working.
        val explicitPhase = detectPhaseFromPrompt(raw)
        if (explicitPhase != null) {
            val json = buildLegacyValidatorJsonFromPrompt(prompt = raw, phase = explicitPhase)
            return streamTextAsCallbackFlow(
                seed = raw,
                fullText = jsonWithOptionalNoise(seed = raw, json = json),
                throwAfterEmits = cfg.throwAfterEmits,
                firstChunkDelayMs = cfg.firstChunkDelayMs,
                chunkDelayMs = cfg.chunkDelayMs,
                chunkSizeChars = cfg.chunkSizeChars,
            )
        }

        // Real-like behavior:
        // - One-step: stream answer-like text.
        // - Two-step: (1) eval JSON internal collect -> (2) follow-up JSON stream.
        val userPrompt = extractUserPromptBestEffort(raw)
        return if (cfg.enableTwoStepEval) {
            requestTwoStepEvalThenFollowUp(userPrompt = userPrompt, rawSeed = raw)
        } else {
            requestOneStepLikeReal(userPrompt = userPrompt, rawSeed = raw)
        }
    }

    // ---------------------------------------------------------------------
    // One-step (legacy-ish): stream answer-like text
    // ---------------------------------------------------------------------

    private fun requestOneStepLikeReal(userPrompt: String, rawSeed: String): Flow<String> {
        if (cfg.resetConversationEachRequest) {
            cfg.logger?.invoke("FakeSlmRepository: resetConversation (oneStep) metaOnly=true")
        }

        val main = generateAnswerLikeText(userPrompt = userPrompt, seed = rawSeed)
        cfg.logger?.invoke("FakeSlmRepository.oneStep: outChars=${main.length}")

        return streamTextAsCallbackFlow(
            seed = rawSeed,
            fullText = main,
            throwAfterEmits = cfg.throwAfterEmits,
            firstChunkDelayMs = cfg.firstChunkDelayMs,
            chunkDelayMs = cfg.chunkDelayMs,
            chunkSizeChars = cfg.chunkSizeChars,
        )
    }

    // ---------------------------------------------------------------------
    // Two-step (shared prompt contract): Eval internal -> FollowUp stream
    // ---------------------------------------------------------------------

    private suspend fun requestTwoStepEvalThenFollowUp(userPrompt: String, rawSeed: String): Flow<String> {
        val u = userPrompt.trim()
        if (u.isBlank()) return emptyFlow()

        val questionId = extractQuestionIdFromUserPrompt(u) ?: "AUTO-${UUID.randomUUID().toString().take(8)}"

        // Step 1: Eval internal collect (JSON)
        if (cfg.resetConversationEachRequest) {
            cfg.logger?.invoke("FakeSlmRepository: resetConversation (evalScore) metaOnly=true")
        }

        val evalPrompt = promptBuilder.buildEvalScorePrompt(
            questionId = questionId,
            userPrompt = u,
            acceptScoreThreshold = cfg.acceptScoreThreshold,
            userPromptMaxChars = EVAL_USER_PROMPT_MAX_CHARS,
        )

        val evalJsonIdeal = generateEvalScoreJson(
            seed = "$rawSeed#EVAL",
            questionId = questionId,
            userPrompt = u,
            threshold = cfg.acceptScoreThreshold,
        )

        val evalInternalFlow = streamTextAsCallbackFlow(
            seed = "$rawSeed#EVAL_INTERNAL",
            fullText = jsonWithOptionalNoise(seed = rawSeed + "#EVAL_INTERNAL", json = evalJsonIdeal),
            throwAfterEmits = if (cfg.throwOnEvalInternal) cfg.throwAfterEmitsOnEvalInternal else -1,
            firstChunkDelayMs = cfg.firstChunkDelayMs,
            chunkDelayMs = cfg.chunkDelayMs,
            chunkSizeChars = cfg.chunkSizeChars,
        )

        val evalCollected = evalInternalFlow.collectToTextResultWithBudget(
            timeoutBudgetMs = cfg.evalTimeoutMs,
            maxChars = cfg.evalMaxChars,
            onChunk = null,
        )

        val evalJsonRecovered = extractFirstJsonObjectBestEffort(evalCollected.text) ?: evalJsonIdeal
        val evalScore = parseScoreBestEffort(evalJsonRecovered)

        cfg.logger?.invoke(
            "FakeSlmRepository.twoStep: evalPromptChars=${evalPrompt.length} evalCollectedChars=${evalCollected.text.length} " +
                    "evalReason=${evalCollected.reason.name} score=${evalScore ?: -1}",
        )

        // Step 2: FollowUp stream (JSON)
        if (cfg.resetConversationEachRequest) {
            cfg.logger?.invoke("FakeSlmRepository: resetConversation (followUp) metaOnly=true")
        }

        val followUpPrompt = promptBuilder.buildFollowUpPrompt(
            questionId = questionId,
            userPrompt = u,
            evalJson = evalJsonRecovered,
            acceptScoreThreshold = cfg.acceptScoreThreshold,
            userPromptMaxChars = FOLLOWUP_USER_PROMPT_MAX_CHARS,
            evalJsonMaxChars = FOLLOWUP_EVAL_JSON_MAX_CHARS,
        )

        val followUpJson = generateFollowUpJson(
            seed = rawSeed + "#FOLLOWUP",
            questionId = questionId,
            userPrompt = u,
            evalJson = evalJsonRecovered,
            threshold = cfg.acceptScoreThreshold,
        )

        val streamed = clipForSafety(followUpJson, cfg.followUpMaxChars)

        cfg.logger?.invoke(
            "FakeSlmRepository.twoStep: followUpPromptChars=${followUpPrompt.length} followUpOutChars=${streamed.length}",
        )

        return streamTextAsCallbackFlow(
            seed = rawSeed + "#FOLLOWUP_STREAM",
            fullText = jsonWithOptionalNoise(seed = rawSeed + "#FOLLOWUP_STREAM", json = streamed),
            throwAfterEmits = cfg.throwAfterEmits,
            firstChunkDelayMs = cfg.firstChunkDelayMs,
            chunkDelayMs = cfg.chunkDelayMs,
            chunkSizeChars = cfg.chunkSizeChars,
        )
    }

    // ---------------------------------------------------------------------
    // Legacy validator-style prompt builder (PHASE overload compatibility)
    // ---------------------------------------------------------------------

    private fun buildValidatorStylePrompt(userPrompt: String, phase: ChatValidation.PromptPhase): String {
        val phaseLine = run {
            val tag = when (phase) {
                ChatValidation.PromptPhase.VALIDATE_MAIN -> "VALIDATE_MAIN"
                ChatValidation.PromptPhase.VALIDATE_FOLLOW_UP -> "VALIDATE_FOLLOW_UP"
            }
            "\nPHASE=$tag\n"
        }

        return """
SYSTEM:
You are a helpful assistant.$phaseLine
USER:
$userPrompt
""".trimIndent()
    }

    // ---------------------------------------------------------------------
    // Streaming engine (callbackFlow) to mimic cancellation semantics
    // ---------------------------------------------------------------------

    private fun streamTextAsCallbackFlow(
        seed: String,
        fullText: String,
        throwAfterEmits: Int,
        firstChunkDelayMs: Long,
        chunkDelayMs: Long,
        chunkSizeChars: Int,
    ): Flow<String> {
        val safeChunkSize = chunkSizeChars.coerceAtLeast(1)
        val safeChunkDelay = chunkDelayMs.coerceAtLeast(0L)
        val safeFirstDelay = firstChunkDelayMs.coerceAtLeast(0L)

        return callbackFlow {
            val closed = AtomicBoolean(false)
            var producer: Job? = null

            fun closeOnce(cause: Throwable? = null) {
                if (closed.compareAndSet(false, true)) {
                    if (cause == null) close() else close(cause)
                }
            }

            producer = launch(Dispatchers.Default) {
                try {
                    // 0 => throw before first emit (no artificial delay).
                    if (throwAfterEmits == 0) throw RuntimeException("fake_stream_error")

                    if (safeFirstDelay > 0L) delay(safeFirstDelay)

                    var emitCount = 0
                    var i = 0
                    while (i < fullText.length) {
                        if (throwAfterEmits >= 0 && emitCount >= throwAfterEmits) {
                            throw RuntimeException("fake_stream_error")
                        }

                        val end = min(i + safeChunkSize, fullText.length)
                        val chunk = fullText.substring(i, end)
                        trySend(chunk)
                        emitCount++

                        i = end
                        if (safeChunkDelay > 0L) delay(safeChunkDelay)
                    }

                    cfg.logger?.invoke(
                        "FakeSlmRepository.stream: done (seedFp=${promptFingerprint(seed)} outChars=${fullText.length})",
                    )
                    closeOnce()
                } catch (ce: CancellationException) {
                    cfg.logger?.invoke("FakeSlmRepository.stream: cancelled (seedFp=${promptFingerprint(seed)})")
                    closeOnce()
                } catch (t: Throwable) {
                    cfg.logger?.invoke(
                        "FakeSlmRepository.stream: error type=${t::class.java.simpleName} (seedFp=${promptFingerprint(seed)})",
                    )
                    closeOnce(t)
                }
            }

            awaitClose {
                producer.cancel()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Eval / FollowUp deterministic generators (NEW SPEC)
    // ---------------------------------------------------------------------

    /**
     * Generate eval score JSON deterministically.
     *
     * Scoring heuristic for fake:
     * - If prompt is short => lower score.
     * - If prompt contains QUESTION_ID marker => slight boost (more structured).
     */
    private fun generateEvalScoreJson(
        seed: String,
        questionId: String,
        userPrompt: String,
        threshold: Int,
    ): String {
        val base = (userPrompt.trim().length * 4).coerceIn(0, 100)
        val boost = if (userPrompt.contains("QUESTION_ID:")) 5 else 0
        val jitter = (promptFingerprint(seed) % 11) - 5 // [-5..+5]
        val score = (base + boost + jitter).coerceIn(0, 100)

        val status = if (score >= threshold) "ACCEPTED" else "NEED_FOLLOW_UP"
        val reason = if (status == "ACCEPTED") {
            "Prompt is sufficiently specified for the next step."
        } else {
            "Prompt is missing key constraints; follow-up is required."
        }

        return """
{
  "status": "$status",
  "score": $score,
  "reason": ${reason.jsonQuote()}
}
""".trimIndent()
    }

    /**
     * Generate follow-up JSON deterministically based on eval JSON.
     *
     * Rules:
     * - If score >= threshold => ACCEPTED with empty followUpQuestion.
     * - Else NEED_FOLLOW_UP with one concise question.
     */
    private fun generateFollowUpJson(
        seed: String,
        questionId: String,
        userPrompt: String,
        evalJson: String,
        threshold: Int,
    ): String {
        val score = parseScoreBestEffort(evalJson) ?: 0
        if (score >= threshold) {
            return """
{
  "status": "ACCEPTED",
  "followUpQuestion": ""
}
""".trimIndent()
        }

        val q = pickFollowUpQuestion(seed = seed, userPrompt = userPrompt, score = score)

        return """
{
  "status": "NEED_FOLLOW_UP",
  "followUpQuestion": ${q.jsonQuote()}
}
""".trimIndent()
    }

    private fun pickFollowUpQuestion(seed: String, userPrompt: String, score: Int): String {
        val t = userPrompt.trim()

        // Heuristic: choose question type based on prompt characteristics.
        val idx = kotlin.math.abs(promptFingerprint(seed) + score + t.length) % 4
        return when (idx) {
            0 -> "What is the exact goal/output you want (one sentence)?"
            1 -> "What constraints matter most (time, budget, device, language, format)?"
            2 -> "Can you provide one concrete example input and the expected output?"
            else -> "What is the success criteria (how do we judge it’s correct)?"
        }
    }

    // ---------------------------------------------------------------------
    // Legacy validator compatibility path (PHASE=... requests)
    // ---------------------------------------------------------------------

    private fun buildLegacyValidatorJsonFromPrompt(prompt: String, phase: ChatValidation.PromptPhase): String {
        // Backward-compatible behavior: old validator flows expect ACCEPTED/NEED_FOLLOW_UP with followUpQuestion.
        val mainAnswer = extractBlockAny(
            text = prompt,
            candidates = listOf(MarkerPair("MAIN_ANSWER_BEGIN", "MAIN_ANSWER_END")),
        ).orEmpty()

        return when (phase) {
            ChatValidation.PromptPhase.VALIDATE_MAIN -> {
                if (mainAnswer.trim().length >= 12) {
                    """
{
  "status": "ACCEPTED",
  "assistantMessage": "Looks good. Thanks!"
}
""".trimIndent()
                } else {
                    """
{
  "status": "NEED_FOLLOW_UP",
  "assistantMessage": "Thanks. I need more detail to validate your answer.",
  "followUpQuestion": "Add one concrete example (what/where/when)."
}
""".trimIndent()
                }
            }
            ChatValidation.PromptPhase.VALIDATE_FOLLOW_UP -> {
                """
{
  "status": "NEED_FOLLOW_UP",
  "assistantMessage": "Good. One more concrete detail would make this answer complete.",
  "followUpQuestion": "Add one measurable detail (number, duration, frequency, or cost)."
}
""".trimIndent()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Phase / ID / prompt extraction
    // ---------------------------------------------------------------------

    private fun detectPhaseFromPrompt(prompt: String): ChatValidation.PromptPhase? {
        val m = Regex("""(?m)^\s*PHASE=(VALIDATE_MAIN|VALIDATE_FOLLOW_UP)\s*$""").find(prompt) ?: return null
        return when (m.groupValues.getOrNull(1)) {
            "VALIDATE_FOLLOW_UP" -> ChatValidation.PromptPhase.VALIDATE_FOLLOW_UP
            else -> ChatValidation.PromptPhase.VALIDATE_MAIN
        }
    }

    private fun extractQuestionIdFromUserPrompt(prompt: String): String? {
        val m = Regex("""(?m)^\s*QUESTION_ID:\s*([^\s]+)\s*$""").find(prompt) ?: return null
        return m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractUserPromptBestEffort(prompt: String): String {
        val lines = prompt.lines()
        val idx = lines.indexOfFirst { it.trim() == "USER:" }
        if (idx >= 0 && idx + 1 < lines.size) {
            return lines.subList(idx + 1, lines.size).joinToString("\n").trim()
        }
        return prompt.trim()
    }

    private fun clipForSafety(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        var end = maxChars.coerceAtMost(text.length)
        if (end <= 0) return ""

        // Avoid splitting a surrogate pair.
        if (end < text.length) {
            val last = text[end - 1]
            val next = text[end]
            if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) {
                end -= 1
            }
        }
        if (end <= 0) return ""
        return text.take(end)
    }

    // ---------------------------------------------------------------------
    // JSON helpers
    // ---------------------------------------------------------------------

    private fun extractFirstJsonObjectBestEffort(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val start = t.indexOf('{')
        if (start < 0) return null
        val end = t.lastIndexOf('}')
        if (end <= start) return null
        return t.substring(start, end + 1)
    }

    private fun parseScoreBestEffort(json: String): Int? {
        val s = json.trim()
        if (s.isEmpty()) return null
        return runCatching {
            val obj = JSONObject(s)
            if (!obj.has("score")) return@runCatching null
            val score = obj.optInt("score", -1)
            score.takeIf { it in 0..100 }
        }.getOrNull()
    }

    private fun jsonWithOptionalNoise(seed: String, json: String): String {
        val prependNonJson = shouldPrependNonJson(seed)
        return buildString {
            if (prependNonJson) {
                append("analysis: (fake) streaming output begins\n")
            }
            append(json)
            if (cfg.appendTrailingNewline) append('\n')
        }
    }

    private fun shouldPrependNonJson(seed: String): Boolean {
        if (!cfg.occasionallyPrependNonJson) return false
        val fp = promptFingerprint(seed)
        return (fp % 5) == 0
    }

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
    // Legacy block extraction helpers (for PHASE paths)
    // ---------------------------------------------------------------------

    private data class MarkerPair(val begin: String, val end: String)

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

    private fun extractBlockAny(text: String, candidates: List<MarkerPair>): String? {
        for (p in candidates) {
            val v = extractBlock(text, p.begin, p.end)
            if (v != null) return v
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Answer-like generator (one-step)
    // ---------------------------------------------------------------------

    private fun generateAnswerLikeText(userPrompt: String, seed: String): String {
        val fp = promptFingerprint(seed)
        val firstLine = userPrompt.lineSequence().firstOrNull().orEmpty().trim().take(48)
        return buildString {
            append("Fake answer (fp=").append(fp).append("). ")
            if (firstLine.isNotBlank()) {
                append("TopicHint=").append(firstLine.replace("{", "").replace("}", "").replace("\"", "")).append(". ")
            }
            append("This is a deterministic streaming response used for integration testing.")
        }
    }

    // ---------------------------------------------------------------------
    // JSON escaping
    // ---------------------------------------------------------------------

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

    companion object {
        // Keep budgets aligned with SlmRepository's constants.
        private const val EVAL_USER_PROMPT_MAX_CHARS: Int = 4_000
        private const val FOLLOWUP_USER_PROMPT_MAX_CHARS: Int = 4_000
        private const val FOLLOWUP_EVAL_JSON_MAX_CHARS: Int = 2_000
    }
}