/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: ExportScreen.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.negi.surveys.BuildConfig
import com.negi.surveys.logging.AppLog
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Export screen (frame-ready).
 *
 * Goals:
 * - Render a potentially large export payload (e.g., JSON).
 * - Allow users to copy/share the export content.
 * - Include lightweight debug metadata (length + sha256) to diagnose regressions.
 *
 * Notes:
 * - Keep this composable mostly pure: it receives the export text as input.
 * - Typically, [exportText] is provided by a ViewModel state.
 *
 * Hardening:
 * - Avoid UI jank by defaulting to preview mode for huge payloads.
 * - Avoid TransactionTooLarge by sharing large payloads as a file via FileProvider (best-effort).
 * - Clipboard may fail for large payloads; fallback to truncated copy.
 */
@Composable
fun ExportScreen(
    exportText: String = DEFAULT_EXPORT_PLACEHOLDER,
    onBack: () -> Unit,
    onCopyDone: (() -> Unit)? = null,
    onShareLaunched: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    val lenChars = exportText.length

    /**
     * Compute SHA-256 + UTF-8 byte count off the main thread to avoid UI jank for large payloads.
     *
     * Implementation detail:
     * - produceState launches a coroutine tied to this composition.
     * - Keyed by exportText so it recomputes only when the payload changes.
     */
    val digest = produceState(initialValue = ExportDigest.COMPUTING, key1 = exportText) {
        value = withContext(Dispatchers.Default) {
            computeDigest(exportText)
        }
    }.value

    val sha256 = digest.sha256
    val lenBytes = digest.utf8Bytes

    
    /** Track whether the user manually toggled the preview/full mode. */
    var userToggled by remember { mutableStateOf(false) }

    // Default display policy:
    // - If payload is large, start in preview mode.
    // - If digest isn't ready yet, start in preview mode and correct once bytes are known.
    var showFull by remember { mutableStateOf(false) }

    
    /** Auto-select showFull once bytes are known, but do NOT override user's manual choice. */
    LaunchedEffect(lenBytes) {
        if (userToggled) return@LaunchedEffect
        if (lenBytes < 0) {
            showFull = false
            return@LaunchedEffect
        }
        showFull = lenBytes <= DEFAULT_SHOW_FULL_BYTES_THRESHOLD
    }

    LaunchedEffect(sha256, lenChars, lenBytes) {
        if (BuildConfig.DEBUG) {
            AppLog.d(TAG, "ExportScreen: chars=$lenChars bytes=$lenBytes sha256=$sha256")
            Log.d(TAG, "ExportScreen: chars=$lenChars bytes=$lenBytes sha256=$sha256")
        }
    }

    val displayedText = remember(exportText, showFull) {
        if (showFull) exportText else exportText.previewText(DEFAULT_PREVIEW_CHARS)
    }

    Column(
        modifier = Modifier
            /**
             * IMPORTANT:
             * - App-level TopBar consumes TOP statusBars inset.
             * - Apply only Horizontal + Bottom safeDrawing here.
             */
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            )
            .padding(16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Export",
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = "Chars: $lenChars",
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "Bytes(UTF-8): ${if (lenBytes < 0) "(computing...)" else lenBytes}",
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "SHA-256: ${if (sha256 == SHA_COMPUTING) "(computing...)" else sha256}",
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    userToggled = true
                    showFull = !showFull
                    AppLog.i(TAG, "toggle: showFull=$showFull")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (showFull) "Show Preview" else "Show Full")
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Payload:",
            style = MaterialTheme.typography.titleMedium
        )

        SelectionContainer {
            Text(
                text = displayedText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }

        if (!showFull && exportText.length > displayedText.length) {
            Text(
                text = "Previewing first ${displayedText.length} chars of ${exportText.length}. Use Share for full payload if it's very large.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onBack) {
                Text("Back")
            }

            OutlinedButton(
                onClick = {
                    val ok = copyToClipboardSafe(context, label = "Survey Export", text = exportText)
                    if (ok) onCopyDone?.invoke()
                }
            ) {
                Text("Copy")
            }

            OutlinedButton(
                onClick = {
                    val ok = shareExportSafe(context, subject = "Survey Export", text = exportText)
                    if (ok) onShareLaunched?.invoke()
                }
            ) {
                Text("Share")
            }
        }
    }
}

private const val TAG = "ExportScreen"
private const val SHA_COMPUTING = "__computing__"

/** Default preview length (chars). */
private const val DEFAULT_PREVIEW_CHARS: Int = 40_000

/** If payload is larger than this, default to preview mode. */
private const val DEFAULT_SHOW_FULL_BYTES_THRESHOLD: Int = 256_000 // 256 KB

/**
 * Conservative safety caps to avoid binder TransactionTooLarge.
 *
 * Notes:
 * - Intent extras cross process boundaries; keep well under ~1MB.
 * - Clipboard may also hit binder limits depending on OEM/OS behavior.
 */
private const val MAX_TEXT_BYTES_FOR_INTENT: Int = 650_000
private const val MAX_TEXT_BYTES_FOR_CLIPBOARD: Int = 650_000

/**
 * A safe placeholder to keep the screen functional before ViewModel wiring.
 */
private const val DEFAULT_EXPORT_PLACEHOLDER: String =
    "{\n" +
            "  \"status\": \"placeholder\",\n" +
            "  \"message\": \"Wire ViewModel exportText here.\"\n" +
            "}\n"

private data class ExportDigest(
    val sha256: String,
    val utf8Bytes: Int
) {
    companion object {
        val COMPUTING = ExportDigest(sha256 = SHA_COMPUTING, utf8Bytes = -1)
    }
}

/**
 * Computes SHA-256 digest and UTF-8 byte count.
 *
 * Why:
 * - Enables quick payload comparison across runs/logs without printing full content.
 */
private fun computeDigest(text: String): ExportDigest {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return ExportDigest(
        sha256 = digestToHex(digest),
        utf8Bytes = bytes.size
    )
}

private fun digestToHex(digest: ByteArray): String {
    val hexChars = "0123456789abcdef".toCharArray()
    val out = CharArray(digest.size * 2)

    var j = 0
    for (b in digest) {
        val v = b.toInt() and 0xFF
        out[j++] = hexChars[v ushr 4]
        out[j++] = hexChars[v and 0x0F]
    }
    return String(out)
}

/**
 * Returns a preview string with a clear truncation marker.
 */
private fun String.previewText(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    val head = take(maxChars)
    return head + "\n\n--- TRUNCATED PREVIEW (show full or share) ---\n"
}

/**
 * Copies text to the Android clipboard with best-effort safeguards for large payloads.
 *
 * Notes:
 * - Clipboard access may be restricted by device policy in managed environments.
 * - Large text may fail on some devices; fallback to truncated copy.
 *
 * @return true if a clipboard write succeeded (full or truncated), false otherwise.
 */
private fun copyToClipboardSafe(context: Context, label: String, text: String): Boolean {
    val cm = runCatching {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }.getOrNull() ?: return false

    return runCatching {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= MAX_TEXT_BYTES_FOR_CLIPBOARD) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            AppLog.i(TAG, "copyToClipboard: ok(full) bytes=${bytes.size}")
            true
        } else {
            val truncated = text.previewText(DEFAULT_PREVIEW_CHARS)
            cm.setPrimaryClip(ClipData.newPlainText(label, truncated))
            AppLog.w(TAG, "copyToClipboard: ok(preview) payloadTooLarge bytes=${bytes.size}")
            true
        }
    }.onFailure { t ->
        Log.w(TAG, "copyToClipboard failed (${t.javaClass.simpleName})", t)
        AppLog.w(TAG, "copyToClipboard failed (${t.javaClass.simpleName})")
    }.getOrDefault(false)
}

/**
 * Shares export payload safely.
 *
 * Strategy:
 * - If small enough: share as plain text via EXTRA_TEXT.
 * - If too large: write to a cache file and share as a stream via FileProvider (best-effort).
 *
 * Notes:
 * - FileProvider requires manifest configuration. If missing, we fallback to truncated text share.
 *
 * @return true if a share intent was launched, false otherwise.
 */
private fun shareExportSafe(context: Context, subject: String, text: String): Boolean {
    return runCatching {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= MAX_TEXT_BYTES_FOR_INTENT) {
            shareText(context, subject = subject, text = text)
        } else {
            val okFile = shareAsFileViaFileProvider(context, subject = subject, text = text)
            if (okFile) true else {
                val preview = text.previewText(DEFAULT_PREVIEW_CHARS)
                shareText(context, subject = subject, text = preview)
            }
        }
    }.onFailure { t ->
        Log.w(TAG, "shareExportSafe failed (${t.javaClass.simpleName})", t)
        AppLog.w(TAG, "shareExportSafe failed (${t.javaClass.simpleName})")
    }.getOrDefault(false)
}

/**
 * Launches Android Sharesheet to share plain text (small payloads).
 */
private fun shareText(context: Context, subject: String, text: String): Boolean {
    return runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share export"))
        AppLog.i(TAG, "shareText: launched")
        true
    }.onFailure { t ->
        Log.w(TAG, "shareText failed (${t.javaClass.simpleName})", t)
        AppLog.w(TAG, "shareText failed (${t.javaClass.simpleName})")
    }.getOrDefault(false)
}

/**
 * Writes export payload to cache and shares it via FileProvider.
 *
 * Requirements:
 * - A FileProvider with authority "${applicationId}.fileprovider" (or compatible) must exist.
 * - Provider must grant URI permissions.
 */
private fun shareAsFileViaFileProvider(context: Context, subject: String, text: String): Boolean {
    return runCatching {
        val cacheDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = "survey-export-${System.currentTimeMillis()}.json"
        val outFile = File(cacheDir, fileName)

        outFile.writeText(text, Charsets.UTF_8)

        
        /** Prefer APPLICATION_ID-based authority; fallback to packageName-based authority. */
        val authorities = listOf(
            BuildConfig.APPLICATION_ID + ".fileprovider",
            context.packageName + ".fileprovider"
        ).distinct()

        var uri = runCatching {
            FileProvider.getUriForFile(context, authorities.first(), outFile)
        }.getOrNull()

        if (uri == null && authorities.size > 1) {
            uri = runCatching {
                FileProvider.getUriForFile(context, authorities[1], outFile)
            }.getOrNull()
        }

        if (uri == null) {
            throw IllegalArgumentException("FileProvider authority not configured: tried=$authorities")
        }

        
        /** Use EXTRA_STREAM for large payloads; keep MIME flexible for broad app compatibility. */
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(Intent.createChooser(intent, "Share export file"))
        AppLog.i(TAG, "shareAsFile: launched file=${outFile.name} bytes=${outFile.length()}")
        true
    }.onFailure { t ->
        // Common failures:
        // - IllegalArgumentException: missing FileProvider/authority
        // - ActivityNotFoundException: no share targets
        Log.w(TAG, "shareAsFileViaFileProvider failed (${t.javaClass.simpleName})", t)
        AppLog.w(TAG, "shareAsFileViaFileProvider failed (${t.javaClass.simpleName})")
    }.getOrDefault(false)
}