package com.hbcao.rcode.platform.document

import android.content.ContentResolver
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hbcao.rcode.bridge.UniffiEditorSession
import com.hbcao.rcode.editor.DocumentFormat
import com.hbcao.rcode.editor.LineEnding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T-0012 on a device: reading a document through [ContentResolverDocumentIo] and
 * handing its bytes to the Rust session.
 *
 * `file://` URIs stand in for SAF here (as in `AtomicDocumentSaverInstrumentedTest`);
 * a real provider's display name and persisted write grant need the system picker and
 * are covered by the manual cases in the task file.
 */
@RunWith(AndroidJUnit4::class)
class ContentResolverDocumentIoInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver: ContentResolver get() = context.contentResolver

    @Test
    fun readsTheWholeDocumentAndFallsBackToThePathSegment() =
        runBlocking {
            withFile(ORIGINAL) { file, key ->
                val io = ContentResolverDocumentIo(resolver)
                try {
                    val document = io.open(key)

                    assertArrayEquals(ORIGINAL, document.bytes)
                    // `file://` has no OpenableColumns, so the fallback must kick in.
                    assertEquals(file.name, document.displayName)
                    assertEquals(file.path, document.path)
                    assertFalse(document.canWrite)
                    assertArrayEquals(ORIGINAL, document.savable.openRead().use { it.readBytes() })
                } finally {
                    io.release(key)
                }
            }
        }

    @Test
    fun bomAndCrlfDocumentRoundTripsThroughTheSession() =
        runBlocking {
            val bytes = UTF8_BOM + "alpha\r\nbeta\r\n".encodeToByteArray()
            withFile(bytes) { _, key ->
                val io = ContentResolverDocumentIo(resolver)
                val session = UniffiEditorSession()
                try {
                    val document = io.open(key)
                    val opened = session.open(document.bytes)

                    // A6, Kotlin side: what the session would save is the file itself.
                    assertEquals(DocumentFormat(bom = true, lineEnding = LineEnding.CRLF), opened.format)
                    assertArrayEquals(bytes, session.encode(session.format()).bytes)
                } finally {
                    session.close()
                    io.release(key)
                }
            }
        }

    @Test
    fun documentsLargerThanTheLimitAreRefused() =
        runBlocking {
            withFile(ByteArray(64) { 'a'.code.toByte() }) { _, key ->
                val io = ContentResolverDocumentIo(resolver, sizeLimitBytes = 16)

                val error =
                    assertThrows(DocumentReadException.TooLarge::class.java) {
                        runBlocking { io.open(key) }
                    }

                assertEquals(16, error.limitBytes)
            }
        }

    @Test
    fun missingDocumentsAreReportedAsNotFound() =
        runBlocking {
            val io = ContentResolverDocumentIo(resolver)
            val missing = File(context.cacheDir, "missing-${System.nanoTime()}.txt")

            assertThrows(DocumentReadException.NotFound::class.java) {
                runBlocking { io.open(Uri.fromFile(missing).toDocumentKey()) }
            }
        }

    @Test
    fun releaseIsANoOpWithoutAGrant() =
        runBlocking {
            withFile(ORIGINAL) { _, key ->
                // Must not throw: `file://` URIs never have a persisted grant.
                ContentResolverDocumentIo(resolver).release(key)
            }
        }

    @Test
    fun documentPathsPreferRealDirectories() {
        val primaryRoot = "/storage/emulated/0"
        val file = File(context.cacheDir, "notes.txt")

        assertEquals(file.path, documentPath(Uri.fromFile(file)))
        assertEquals(
            "$primaryRoot/Notes/todo.txt",
            documentPath(
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ANotes%2Ftodo.txt"),
                primaryRoot,
            ),
        )
        assertEquals(
            "/storage/1A2B-3C4D/Notes/todo.txt",
            documentPath(
                Uri.parse("content://com.android.externalstorage.documents/document/1A2B-3C4D%3ANotes%2Ftodo.txt"),
                primaryRoot,
            ),
        )
        assertEquals(
            primaryRoot,
            documentPath(Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"), primaryRoot),
        )
        // Providers whose document ids are not paths keep their decoded URI.
        assertEquals(
            "content://com.example.provider/document/42",
            documentPath(Uri.parse("content://com.example.provider/document/42"), primaryRoot),
        )
    }

    private suspend fun withFile(
        contents: ByteArray,
        block: suspend (File, DocumentKey) -> Unit,
    ) {
        val file = File.createTempFile("document-", ".txt", context.cacheDir)
        try {
            file.writeBytes(contents)
            block(file, Uri.fromFile(file).toDocumentKey())
        } finally {
            file.delete()
        }
    }

    private companion object {
        val ORIGINAL = "original content\n".encodeToByteArray()
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
