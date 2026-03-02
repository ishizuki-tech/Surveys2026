/*
 * =====================================================================
 *  IshizukiTech LLC — LiteRtLM Integration
 *  ---------------------------------------------------------------------
 *  File: LiteRtLmTextDelta.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm.liteRT

import com.google.ai.edge.litertlm.Message
import com.negi.surveys.logging.AppLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Text extraction + delta computation utilities for LiteRT-LM streaming callbacks.
 *
 * Notes:
 * - LiteRT-LM SDK has changed Message/Contents surfaces across versions.
 * - We keep this code isolated to reduce blast radius for future API drift.
 */
internal object LiteRtLmTextDelta {

    private val extractDebugCounter: AtomicLong = AtomicLong(0L)

    /**
     * Best-effort parse for debug strings like:
     * - Text(text=...)
     * - Text(value=...)
     * - Text(content=...)
     * - Text("...")
     */
    private fun extractTextFromDebugString(debug: String): String {
        if (debug.isBlank()) return ""

        fun unquote(s: String): String {
            val t = s.trim()
            if (t.length >= 2 &&
                ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))
            ) {
                return t.substring(1, t.length - 1)
            }
            return t
        }

        fun readQuoted(src: String, start: Int): Pair<String, Int> {
            if (start >= src.length) return "" to start
            val quote = src[start]
            if (quote != '"' && quote != '\'') return "" to start
            val sb = StringBuilder()
            var i = start + 1
            while (i < src.length) {
                val ch = src[i]
                if (ch == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (ch == quote) return sb.toString() to (i + 1)
                sb.append(ch)
                i++
            }
            return sb.toString() to i
        }

        fun readUntilDelim(src: String, start: Int): Pair<String, Int> {
            var i = start
            val sb = StringBuilder()
            while (i < src.length) {
                val ch = src[i]
                if (ch == ')' || ch == ',' || ch == ']' || ch == '}' || ch == '\n') break
                sb.append(ch)
                i++
            }
            return sb.toString() to i
        }

        val out = StringBuilder()
        var i = 0
        while (i < debug.length) {
            val idx = debug.indexOf("Text(", i)
            if (idx < 0) break

            var j = idx + "Text(".length
            while (j < debug.length && debug[j].isWhitespace()) j++

            if (j < debug.length && (debug[j] == '"' || debug[j] == '\'')) {
                val (q, next) = readQuoted(debug, j)
                if (q.isNotEmpty()) out.append(q)
                i = next
                continue
            }

            val keys = listOf("text=", "value=", "content=")
            var picked: String? = null
            var pickedEnd = j

            for (k in keys) {
                val kIdx = debug.indexOf(k, j)
                if (kIdx >= 0) {
                    var p = kIdx + k.length
                    while (p < debug.length && debug[p].isWhitespace()) p++

                    val (v, endPos) = if (p < debug.length && (debug[p] == '"' || debug[p] == '\'')) {
                        readQuoted(debug, p)
                    } else {
                        readUntilDelim(debug, p)
                    }

                    val vv = unquote(v)
                    if (vv.isNotBlank()) {
                        picked = vv
                        pickedEnd = endPos
                        break
                    }
                }
            }

            if (!picked.isNullOrBlank()) {
                out.append(picked)
                i = pickedEnd
            } else {
                i = idx + 4
            }
        }

        return out.toString()
    }

    /** Attempt to extract text from Message directly if such getter exists. */
    private fun extractTextFromMessageBestEffort(message: Message): String {
        val any = message as Any
        val candidates = listOf("getText", "text", "getValue", "value", "getContent", "content")
        for (name in candidates) {
            val m = runCatching {
                any.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 0 && it.returnType == String::class.java
                }
            }.getOrNull() ?: continue
            val v = runCatching { m.invoke(any) as? String }.getOrNull()
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    /** Choose the more "human text" candidate. */
    private fun chooseMoreHumanText(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a

        fun score(s: String): Int {
            var x = 0
            val debugish = listOf("Message(", "contents=", "Content.", "Text(", "engine=", "Conversation")
            if (debugish.any { s.contains(it) }) x -= 50
            x += s.length / 8
            x += s.count { it == ' ' || it == '\n' || it == '\t' }.coerceAtMost(40)
            x -= s.count { it == '=' || it == '[' || it == ']' || it == '{' || it == '}' }
                .coerceAtMost(30)
            return x
        }

        val sa = score(a)
        val sb = score(b)
        return if (sb > sa) b else a
    }

    /** Extract best-effort visible text from a Message. */
    internal fun extractRenderedText(message: Message): String {
        val direct = extractTextFromMessageBestEffort(message)
        if (direct.isNotBlank()) return direct

        val fromContents = runCatching {
            val contentsObj: Any = message.contents
            val s = contentsObj.toString()
            val parsed = extractTextFromDebugString(s)
            parsed.ifBlank { s }
        }.getOrElse { "" }

        val fromToString = runCatching { message.toString() }.getOrElse { "" }
        val parsedFromToString = extractTextFromDebugString(fromToString)
        val b = if (parsedFromToString.isNotBlank()) parsedFromToString else fromToString

        if (LiteRtLmLogging.DEBUG_EXTRACT) {
            val n = extractDebugCounter.incrementAndGet()
            if (n == 1L || n % LiteRtLmLogging.DEBUG_EXTRACT_EVERY_N == 0L) {
                AppLog.d(
                    LiteRtLmLogging.TAG,
                    "extractRenderedText[#${n}] fromContents.len=${fromContents.length} " +
                            "msgToString.len=${fromToString.length} parsedToString.len=${parsedFromToString.length}"
                )
            }
        }

        return chooseMoreHumanText(fromContents, b)
    }

    /** Normalize tokenizer artifacts into plain text. */
    internal fun normalizeDeltaText(s: String): String {
        if (s.isEmpty()) return s
        return s
            .replace('\u00A0', ' ')
            .replace('\uFEFF', ' ')
            .replace('\u2581', ' ')
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
    }

    // ---- Delta extraction performance work ----

    private const val DELTA_MAX_WINDOW = 1024
    private const val DELTA_PREFIX_PROBE_CHARS = 64
    private const val DELTA_PREFIX_STRICT_THRESHOLD = 2048

    /**
     * KMP-based overlap computation (O(window)).
     *
     * Finds k = max overlap where suffix(a) == prefix(b), with k <= maxWindow.
     */
    private fun overlapSuffixPrefixKmp(a: String, b: String, maxWindow: Int = DELTA_MAX_WINDOW): Int {
        if (a.isEmpty() || b.isEmpty()) return 0

        val aStart = max(0, a.length - maxWindow)
        val aLen = a.length - aStart
        val bLen = min(b.length, maxWindow)
        if (aLen <= 0 || bLen <= 0) return 0

        fun chooseSep(): Char {
            val candidates = charArrayOf('\u0001', '\u0002', '\u0000', '\u0003', '\u001F', '\uFFFF')
            for (c in candidates) {
                var found = false
                for (i in 0 until bLen) {
                    if (b[i] == c) {
                        found = true
                        break
                    }
                }
                if (!found) return c
            }
            return '\u0001'
        }

        val sep = chooseSep()

        val n = bLen + 1 + aLen
        val s = CharArray(n)
        for (i in 0 until bLen) s[i] = b[i]
        s[bLen] = sep
        for (i in 0 until aLen) s[bLen + 1 + i] = a[aStart + i]

        val pi = IntArray(n)
        var j = 0
        for (i in 1 until n) {
            while (j > 0 && s[i] != s[j]) {
                j = pi[j - 1]
            }
            if (s[i] == s[j]) j++
            pi[i] = j
        }

        val ov = pi[n - 1]
        return ov.coerceIn(0, min(aLen, bLen))
    }

    /**
     * Avoid O(N) `startsWith(prev)` on long snapshots.
     *
     * We probe start/middle/end to quickly decide "next is prefix extension of prev".
     */
    private fun looksLikePrefixExtension(prev: String, next: String, probeChars: Int = DELTA_PREFIX_PROBE_CHARS): Boolean {
        if (prev.isEmpty()) return true
        if (next.length < prev.length) return false

        val probe = min(probeChars, prev.length)
        if (probe <= 0) return true

        if (!next.regionMatches(0, prev, 0, probe, ignoreCase = false)) return false

        val prevEndStart = prev.length - probe
        if (!next.regionMatches(prevEndStart, prev, prevEndStart, probe, ignoreCase = false)) return false

        if (prev.length >= probe * 4) {
            val mid = (prev.length / 2) - (probe / 2)
            val safeMid = mid.coerceIn(0, prev.length - probe)
            if (!next.regionMatches(safeMid, prev, safeMid, probe, ignoreCase = false)) return false
        }

        return true
    }

    /**
     * Delta extractor that works for snapshot-style or delta-style streams.
     */
    internal fun computeDeltaSmart(emittedSoFar: String, newSnapshot: String): Pair<String, String> {
        if (newSnapshot.isEmpty()) return "" to emittedSoFar
        if (emittedSoFar.isEmpty()) return newSnapshot to newSnapshot

        if (newSnapshot.length >= emittedSoFar.length && emittedSoFar.length >= DELTA_PREFIX_STRICT_THRESHOLD) {
            if (looksLikePrefixExtension(emittedSoFar, newSnapshot)) {
                val delta = newSnapshot.substring(emittedSoFar.length)
                return delta to newSnapshot
            }
        } else {
            if (newSnapshot.length >= emittedSoFar.length && newSnapshot.startsWith(emittedSoFar)) {
                val delta = newSnapshot.substring(emittedSoFar.length)
                return delta to newSnapshot
            }
        }

        if (emittedSoFar.length > newSnapshot.length && emittedSoFar.startsWith(newSnapshot)) {
            return "" to emittedSoFar
        }

        val ov = overlapSuffixPrefixKmp(emittedSoFar, newSnapshot, maxWindow = DELTA_MAX_WINDOW)
        val delta = newSnapshot.substring(ov)
        return delta to (emittedSoFar + delta)
    }
}