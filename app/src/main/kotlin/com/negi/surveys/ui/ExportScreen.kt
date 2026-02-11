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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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

    val len = exportText.length

    /**
     * Compute SHA-256 off the main thread to avoid UI jank for large payloads.
     *
     * Implementation detail:
     * - produceState launches a coroutine tied to this composition.
     * - Keyed by exportText so the digest recomputes only when the payload changes.
     */
    val sha256 = produceState(initialValue = SHA_COMPUTING, key1 = exportText) {
        value = withContext(Dispatchers.Default) {
            sha256Hex(exportText)
        }
    }.value

    LaunchedEffect(sha256, len) {
        if (sha256 != SHA_COMPUTING) {
            Log.d(TAG, "ExportScreen: len=$len sha256=$sha256")
        } else {
            Log.d(TAG, "ExportScreen: len=$len sha256=(computing)")
        }
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
            text = "Length: $len",
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "SHA-256: ${if (sha256 == SHA_COMPUTING) "(computing...)" else sha256}",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Payload:",
            style = MaterialTheme.typography.titleMedium
        )

        SelectionContainer {
            Text(
                text = exportText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
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
                    val ok = copyToClipboard(context, label = "Survey Export", text = exportText)
                    if (ok) onCopyDone?.invoke()
                }
            ) {
                Text("Copy")
            }

            OutlinedButton(
                onClick = {
                    val ok = shareText(context, subject = "Survey Export", text = exportText)
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

/**
 * A safe placeholder to keep the screen functional before ViewModel wiring.
 */
private const val DEFAULT_EXPORT_PLACEHOLDER: String =
    "{\n" +
            "  \"status\": \"placeholder\",\n" +
            "  \"message\": \"Wire ViewModel exportText here.\"\n" +
            "}\n"

/**
 * Copies text to the Android clipboard.
 *
 * Notes:
 * - Clipboard access may be restricted by device policy in managed environments.
 * - This method intentionally does not show a toast; callers can decide UX feedback.
 *
 * @return true if clipboard write succeeded, false otherwise.
 */
private fun copyToClipboard(context: Context, label: String, text: String): Boolean {
    return runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.onFailure { t ->
        Log.w(TAG, "copyToClipboard failed (${t.javaClass.simpleName})")
    }.getOrDefault(false)
}

/**
 * Launches Android Sharesheet to share plain text.
 *
 * Notes:
 * - Uses ACTION_SEND for broad compatibility.
 * - Missing handlers or policy restrictions can throw; guarded to avoid crashes.
 *
 * @return true if the sharesheet was launched, false otherwise.
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
        true
    }.onFailure { t ->
        Log.w(TAG, "shareText failed (${t.javaClass.simpleName})")
    }.getOrDefault(false)
}

/**
 * Computes SHA-256 digest as lowercase hex.
 *
 * Why:
 * - Enables quick payload comparison across runs/logs without printing the full content.
 */
private fun sha256Hex(text: String): String {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)

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
