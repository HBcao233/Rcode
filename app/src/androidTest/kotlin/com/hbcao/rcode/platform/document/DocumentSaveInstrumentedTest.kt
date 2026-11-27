package com.hbcao.rcode.platform.document

import android.content.ContentResolver
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hbcao.rcode.bridge.CoreBridge
import com.hbcao.rcode.bridge.UniffiEditorSession
import com.hbcao.rcode.editor.DocumentFormat
import com.hbcao.rcode.editor.LineEnding
import com.hbcao.rcode.state.DocumentUiState
import com.hbcao.rcode.state.MainViewModel
import com.hbcao.rcode.state.SaveFailure
import com.hbcao.rcode.state.SaveOutcome
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A6/A7 on a device through the production pieces (T-0013): `DocumentIo.open` →
 * `EditorSession.open` → `encode(format)` → `AtomicDocumentSaver.save`, with the real
 * Rust codec and the real ContentResolver-backed file streams.
 *
 * `file://` URIs stand in for SAF as in the T-0010/T-0012 instrumented tests; the
 * picker's persisted grants and `ACTION_CREATE_DOCUMENT` need a manual run.
 */
@RunWith(AndroidJUnit4::class)
class DocumentSaveInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver: ContentResolver get() = context.contentResolver

    @Test
    fun lfDocumentRoundTripsThroughOpenEncodeAndSave() =
        runBlocking {
            withFile(LF_DOCUMENT) { file, key ->
                val io = ContentResolverDocumentIo(resolver)
                val saver = AtomicDocumentSaver(context.cacheDir)
                val session = UniffiEditorSession()
                try {
                    val document = io.open(key)
                    session.open(document.bytes)

                    assertEquals(DocumentFormat(bom = false, lineEnding = LineEnding.LF), session.format())
                    val result = saver.save(document.savable, session.encode(session.format()).bytes)

                    assertTrue("unexpected result: $result", result is SaveResult.Success)
                    assertArrayEquals(LF_DOCUMENT, file.readBytes())
                } finally {
                    session.close()
                    io.release(key)
                }
            }
        }

    @Test
    fun bomAndCrlfDocumentRoundTripsThroughOpenEncodeAndSave() =
        runBlocking {
            withFile(BOM_CRLF_DOCUMENT) { file, key ->
                val io = ContentResolverDocumentIo(resolver)
                val saver = AtomicDocumentSaver(context.cacheDir)
                val session = UniffiEditorSession()
                try {
                    val document = io.open(key)
                    session.open(document.bytes)

                    assertEquals(DocumentFormat(bom = true, lineEnding = LineEnding.CRLF), session.format())
                    val result = saver.save(document.savable, session.encode(session.format()).bytes)

                    assertTrue("unexpected result: $result", result is SaveResult.Success)
                    assertArrayEquals(BOM_CRLF_DOCUMENT, file.readBytes())
                } finally {
                    session.close()
                    io.release(key)
                }
            }
        }

    @Test
    fun editsAreWrittenInTheOpenedFormat() =
        runBlocking {
            withFile(BOM_CRLF_DOCUMENT) { file, key ->
                val io = ContentResolverDocumentIo(resolver)
                val saver = AtomicDocumentSaver(context.cacheDir)
                val session = UniffiEditorSession()
                try {
                    val document = io.open(key)
                    session.open(document.bytes)
                    session.insertText("new ")
                    val encoded = session.encode(session.format())

                    val result = saver.save(document.savable, encoded.bytes)

                    assertTrue("unexpected result: $result", result is SaveResult.Success)
                    assertEquals(encoded.version, session.version())
                    // BOM kept, and the new text uses CRLF like the rest of the file.
                    assertArrayEquals(UTF8_BOM + "new alpha\r\nbeta\r\n".encodeToByteArray(), file.readBytes())
                } finally {
                    session.close()
                    io.release(key)
                }
            }
        }

    /** A7 with the view model: a failed overwrite keeps the file and the UI state. */
    @Test
    fun failedSaveKeepsTheOriginalAndReportsTheStage() =
        runBlocking {
            withFile(LF_DOCUMENT) { file, key ->
                val session = UniffiEditorSession()
                val viewModel =
                    MainViewModel(
                        bridge = FakeBridge(),
                        editorSession = session,
                        // `file://` has no persisted grant; this fake reports the document
                        // as writable so the view model saves in place instead of asking
                        // for a location.
                        documentIo = WritableFileIo(resolver, key, LF_DOCUMENT),
                        documentSaver = FailingSaver(AtomicDocumentSaver(context.cacheDir)),
                    )
                try {
                    viewModel.openDocument(key)
                    withTimeout(TIMEOUT_MS) {
                        viewModel.documentState.filter { it is DocumentUiState.Opened }.first()
                    }
                    val opened = viewModel.documentState.value
                    val version = session.version()
                    session.insertText("changed ")
                    viewModel.onDocumentVersionChanged(session.version())
                    assertTrue(viewModel.dirty.value)

                    viewModel.saveDocument()
                    val outcome =
                        withTimeout(TIMEOUT_MS) { viewModel.saveOutcome.filterNotNull().first() }

                    assertEquals(
                        SaveOutcome.Failed(SaveFailure.WriteFailed(SaveStage.WRITE_ORIGINAL)),
                        outcome,
                    )
                    assertArrayEquals(LF_DOCUMENT, file.readBytes())
                    assertEquals(opened, viewModel.documentState.value)
                    assertTrue(viewModel.dirty.value)
                    assertEquals(version + 1, session.version())
                    assertEquals("changed alpha\nbeta\n", session.text())
                    assertTrue(viewModel.saveRequest.value == null)
                } finally {
                    session.close()
                }
            }
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

    private class FakeBridge : CoreBridge {
        override fun ping(): String = "rcode-core 0.0.0-test"
    }

    /** Returns [contents] as a writable document backed by [uri]. */
    private class WritableFileIo(
        private val resolver: ContentResolver,
        private val key: DocumentKey,
        private val contents: ByteArray,
    ) : DocumentIo {
        override suspend fun open(key: DocumentKey): ReadDocument {
            val uri = Uri.parse(key.uri)
            return ReadDocument(
                key = key,
                displayName = uri.lastPathSegment ?: key.uri,
                path = uri.path ?: key.uri,
                bytes = contents,
                canWrite = true,
                savable = ContentResolverDocument(resolver, uri),
            )
        }

        override suspend fun adopt(key: DocumentKey): WritableDocument = throw UnsupportedOperationException("not used")

        override suspend fun release(key: DocumentKey) = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val LF_DOCUMENT = "alpha\nbeta\n".encodeToByteArray()
        val BOM_CRLF_DOCUMENT = UTF8_BOM + "alpha\r\nbeta\r\n".encodeToByteArray()
    }
}

/** Runs the save against a document whose overwrite fails halfway (A7 injection). */
private class FailingSaver(
    private val delegate: DocumentSaver,
) : DocumentSaver {
    override suspend fun save(
        document: SavableDocument,
        bytes: ByteArray,
    ): SaveResult = delegate.save(FailingOverwriteDocument(document), bytes)
}
