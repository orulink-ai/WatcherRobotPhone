package com.danielealbano.androidremotecontrolmcp.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentClassifier
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedContentInbox
import com.danielealbano.androidremotecontrolmcp.services.sharing.SharedItem
import com.danielealbano.androidremotecontrolmcp.services.sharing.readWithinCap
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Invisible activity registered as an Android share target (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`,
 * any MIME type). It captures the shared payload into the in-memory [SharedContentInbox] and finishes
 * with no visible UI.
 *
 * Textual `ACTION_SEND` with `EXTRA_TEXT` is stored as a text item. Otherwise each `EXTRA_STREAM` URI is
 * read fully into memory while counting bytes, aborting when the per-file cap is exceeded — the
 * provider-reported size ([OpenableColumns.SIZE]) is NOT trusted (it may be -1/null). Nothing is written
 * to disk. All reading happens before [finish] because the URI read grants are tied to this activity's
 * lifetime.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    @Inject
    lateinit var inbox: SharedContentInbox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // launchMode is `standard`, so every share starts a fresh instance handled here in onCreate;
        // onNewIntent is never delivered and is intentionally not overridden.
        process(intent)
    }

    /**
     * Reads the payload off the main thread (the read grant is tied to this activity), then finishes.
     *
     * [finish] is deferred until after the async read completes, so the manifest uses a translucent theme
     * (not `Theme.NoDisplay`, which requires `finish()` to be called synchronously within `onCreate`).
     */
    private fun process(intent: Intent) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { handleIntent(intent) }
            finish()
        }
    }

    private suspend fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> handleSend(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(intent)
        }
    }

    private suspend fun handleSend(intent: Intent) {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val type = intent.type
        if (text != null && type != null && SharedContentClassifier.isTextual(type)) {
            val now = System.currentTimeMillis()
            inbox.add(
                SharedItem(
                    id = UUID.randomUUID().toString(),
                    kind = SharedItem.Kind.TEXT,
                    mimeType = type,
                    fileName = null,
                    text = text,
                    bytes = null,
                    sizeBytes = text.toByteArray().size.toLong(),
                    createdAtMs = now,
                    expiresAtMs = now + SharedContentInbox.TTL_MS,
                ),
            )
            return
        }
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        readToInbox(uri)?.let { inbox.add(it) }
    }

    private suspend fun handleSendMultiple(intent: Intent) {
        val uris =
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        uris.forEach { uri -> readToInbox(uri)?.let { inbox.add(it) } }
    }

    /**
     * Reads [uri] fully into memory, enforcing [SharedContentInbox.MAX_FILE_BYTES] during the read.
     * Returns the built [SharedItem], or null when the stream cannot be opened, exceeds the cap, or fails.
     */
    private fun readToInbox(uri: Uri): SharedItem? {
        val mimeType = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
        val displayName = queryDisplayName(uri)
        val bytes = readUriCapped(uri) ?: return null
        val now = System.currentTimeMillis()
        return SharedItem(
            id = UUID.randomUUID().toString(),
            kind = SharedItem.Kind.BLOB,
            mimeType = mimeType,
            fileName = displayName,
            text = null,
            bytes = bytes,
            sizeBytes = bytes.size.toLong(),
            createdAtMs = now,
            expiresAtMs = now + SharedContentInbox.TTL_MS,
        )
    }

    /** Reads [uri] into a [ByteArray], or null if it cannot be opened, exceeds the cap, or fails to read. */
    private fun readUriCapped(uri: Uri): ByteArray? =
        try {
            contentResolver.openInputStream(uri)?.use { source ->
                readWithinCap(source, SharedContentInbox.MAX_FILE_BYTES)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read shared stream", e)
            null
        }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()

    companion object {
        private const val TAG = "MCP:ShareReceiver"
    }
}
