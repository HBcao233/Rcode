package com.hbcao.rcode.platform.document

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/** Wraps a URI returned by the SAF picker into a [DocumentKey]. */
fun Uri.toDocumentKey(): DocumentKey = DocumentKey(toString())

/**
 * Production [DocumentIo] backed by [ContentResolver] (T-0012).
 *
 * Reads at most [sizeLimitBytes] into one `ByteArray` (Q-T11-2 decided on a single
 * `Vec<u8>` across the FFI, and Q-T12-3 caps it at 64 MiB), takes a persisted
 * read/write grant for the picked URI, and exposes the same URI as a
 * [SavableDocument] so T-0013 can save in place.
 *
 * The picker must request `FLAG_GRANT_READ_URI_PERMISSION or
 * FLAG_GRANT_WRITE_URI_PERMISSION` (see `MainScreen`); a provider that grants read
 * only still opens, with [ReadDocument.canWrite] `false`.
 */
class ContentResolverDocumentIo(
    private val resolver: ContentResolver,
    private val sizeLimitBytes: Long = DEFAULT_SIZE_LIMIT_BYTES,
) : DocumentIo {
    override suspend fun open(key: DocumentKey): ReadDocument {
        val uri = Uri.parse(key.uri)
        val bytes = readCapped(uri)
        val displayName = displayName(uri)
        val canWrite = takePersistablePermission(uri)
        return ReadDocument(
            key = key,
            displayName = displayName,
            bytes = bytes,
            canWrite = canWrite,
            savable = ContentResolverDocument(resolver, uri),
        )
    }

    override suspend fun release(key: DocumentKey) {
        val uri = Uri.parse(key.uri)
        if (!releasePermission(uri, READ_WRITE_FLAGS)) {
            releasePermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun readCapped(uri: Uri): ByteArray {
        val stream =
            try {
                resolver.openInputStream(uri)
            } catch (e: FileNotFoundException) {
                throw DocumentReadException.NotFound(uri.toString())
            } catch (e: SecurityException) {
                throw DocumentReadException.PermissionDenied(uri.toString())
            } catch (e: IOException) {
                throw DocumentReadException.Io(uri.toString(), e)
            } catch (e: RuntimeException) {
                throw DocumentReadException.Io(uri.toString(), e)
            } ?: throw DocumentReadException.NotFound(uri.toString())

        stream.use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(READ_CHUNK_BYTES)
            while (true) {
                val read =
                    try {
                        input.read(chunk)
                    } catch (e: IOException) {
                        throw DocumentReadException.Io(uri.toString(), e)
                    }
                if (read < 0) break
                if (output.size().toLong() + read > sizeLimitBytes) {
                    // Q-T12-3: refuse instead of loading a document we cannot hold.
                    throw DocumentReadException.TooLarge(sizeLimitBytes)
                }
                output.write(chunk, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun displayName(uri: Uri): String =
        queryDisplayName(uri)
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: uri.toString()

    private fun queryDisplayName(uri: Uri): String? =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            // Providers that do not answer OpenableColumns (and `file://` URIs) fall
            // back to the URI's last path segment; the open itself is unaffected.
            Log.w(LOG_TAG, "display name query failed for $uri", e)
            null
        }

    /**
     * Persists the picker's grant and reports whether it covers writing.
     *
     * `ACTION_OPEN_DOCUMENT` may grant read only (`file://` grants nothing at all);
     * requesting read+write then throws, so retry with read only before giving up.
     */
    private fun takePersistablePermission(uri: Uri): Boolean {
        try {
            resolver.takePersistableUriPermission(uri, READ_WRITE_FLAGS)
            return true
        } catch (e: SecurityException) {
            Log.i(LOG_TAG, "no persistable read/write grant for $uri", e)
        }
        return try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            false
        } catch (e: SecurityException) {
            Log.i(LOG_TAG, "no persistable read grant for $uri", e)
            false
        }
    }

    private fun releasePermission(
        uri: Uri,
        flags: Int,
    ): Boolean =
        try {
            resolver.releasePersistableUriPermission(uri, flags)
            true
        } catch (e: SecurityException) {
            Log.i(LOG_TAG, "nothing to release for $uri", e)
            false
        }

    companion object {
        /** Q-T12-3: hard read limit; larger documents are refused with [DocumentReadException.TooLarge]. */
        const val DEFAULT_SIZE_LIMIT_BYTES: Long = 64L * 1024 * 1024

        private const val READ_CHUNK_BYTES = 64 * 1024
        private const val LOG_TAG = "rcode-document"

        private val READ_WRITE_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
