/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: SafeLog.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.logging

import android.os.Build
import android.util.Log

/**
 * Minimal safe logger wrapper.
 *
 * Goals:
 * - Never crash due to logging.
 * - Prefer AppLog when available, fallback to Logcat always.
 * - Avoid extra allocations (no runCatching) and support lazy messages.
 *
 * Notes:
 * - Pre-Android N (API < 24) Log tag length limit is 23 chars.
 */
object SafeLog {

    private const val MAX_TAG_PRE_N = 23

    private fun normalizeTag(tag: String): String {
        val trimmed = tag.trim()
        val safe = if (trimmed.isNotEmpty()) trimmed else "SafeLog"
        return if (Build.VERSION.SDK_INT >= 24) safe else safe.take(MAX_TAG_PRE_N)
    }

    /**
     * Debug log (eager message).
     */
    fun d(tag: String, msg: String) {
        val t = normalizeTag(tag)
        try {
            AppLog.d(t, msg)
        } catch (_: Throwable) {
            Log.d(t, msg)
        }
    }

    /**
     * Debug log (lazy message).
     */
    fun d(tag: String, msg: () -> String) {
        val t = normalizeTag(tag)
        try {
            AppLog.d(t, msg())
        } catch (_: Throwable) {
            Log.d(t, safeEval(msg))
        }
    }

    /**
     * Info log (eager message).
     */
    fun i(tag: String, msg: String) {
        val t = normalizeTag(tag)
        try {
            AppLog.i(t, msg)
        } catch (_: Throwable) {
            Log.i(t, msg)
        }
    }

    /**
     * Info log (lazy message).
     */
    fun i(tag: String, msg: () -> String) {
        val t = normalizeTag(tag)
        try {
            AppLog.i(t, msg())
        } catch (_: Throwable) {
            Log.i(t, safeEval(msg))
        }
    }

    /**
     * Warn log (eager message).
     */
    fun w(tag: String, msg: String, t: Throwable? = null) {
        val tt = normalizeTag(tag)
        try {
            if (t != null) AppLog.w(tt, msg, t) else AppLog.w(tt, msg)
        } catch (_: Throwable) {
            if (t != null) Log.w(tt, msg, t) else Log.w(tt, msg)
        }
    }

    /**
     * Warn log (lazy message).
     */
    fun w(tag: String, t: Throwable? = null, msg: () -> String) {
        val tt = normalizeTag(tag)
        try {
            val m = msg()
            if (t != null) AppLog.w(tt, m, t) else AppLog.w(tt, m)
        } catch (_: Throwable) {
            val m = safeEval(msg)
            if (t != null) Log.w(tt, m, t) else Log.w(tt, m)
        }
    }

    /**
     * Error log (eager message).
     */
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val tt = normalizeTag(tag)
        try {
            if (t != null) AppLog.e(tt, msg, t) else AppLog.e(tt, msg)
        } catch (_: Throwable) {
            if (t != null) Log.e(tt, msg, t) else Log.e(tt, msg)
        }
    }

    /**
     * Error log (lazy message).
     */
    fun e(tag: String, t: Throwable? = null, msg: () -> String) {
        val tt = normalizeTag(tag)
        try {
            val m = msg()
            if (t != null) AppLog.e(tt, m, t) else AppLog.e(tt, m)
        } catch (_: Throwable) {
            val m = safeEval(msg)
            if (t != null) Log.e(tt, m, t) else Log.e(tt, m)
        }
    }

    /**
     * Evaluates a message supplier safely (never throws).
     */
    private fun safeEval(msg: () -> String): String {
        return try {
            msg()
        } catch (_: Throwable) {
            "<log message threw>"
        }
    }
}