package com.hbcao.rcode.platform.document

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [SavableDocument] backed by a `content://` or `file://` URI through
 * [ContentResolver].
 *
 * The URI must carry both read and write permission: the saver reads the original to
 * build a backup and to verify the result. T-0012 must persist picked URIs with
 * `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION`
 * (`ACTION_OPEN_DOCUMENT` does not grant read access by default). With only write
 * permission the save fails before touching the file as
 * [SaveStage.READ_ORIGINAL].
 *
 * Opening for writing uses mode `"wt"` (truncate). A provider that does not support
 * it surfaces the platform error as a save failure; this class never silently
 * falls back to a non-truncating or read-modify-write mode.
 */
class ContentResolverDocument(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : SavableDocument {
    override val description: String
        get() = uri.toString()

    override fun openRead(): InputStream =
        resolver.openInputStream(uri)
            ?: throw IOException("content provider returned no stream for $description")

    override fun openWrite(): OutputStream =
        try {
            resolver.openOutputStream(uri, "wt")
                ?: throw IOException("content provider returned no stream for $description")
        } catch (e: FileNotFoundException) {
            throw IOException("provider cannot open $description for writing (\"wt\")", e)
        }
}
