package com.hbcao.rcode.state

import com.hbcao.rcode.bridge.CoreBridge
import com.hbcao.rcode.editor.DocumentOpenException
import com.hbcao.rcode.editor.FakeEditorSession
import com.hbcao.rcode.editor.UnsupportedEncodingKind
import com.hbcao.rcode.platform.document.DocumentIo
import com.hbcao.rcode.platform.document.DocumentKey
import com.hbcao.rcode.platform.document.DocumentReadException
import com.hbcao.rcode.platform.document.ReadDocument
import com.hbcao.rcode.platform.document.SavableDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private class FakeBridge(
        private val result: () -> String,
    ) : CoreBridge {
        override fun ping(): String = result()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region M0 core status

    @Test
    fun `refresh publishes core version as Ready`() =
        runTest {
            val viewModel =
                MainViewModel(
                    FakeBridge { "rcode-core 0.1.0" },
                    FakeEditorSession(),
                    FakeDocumentIo(),
                    StandardTestDispatcher(testScheduler),
                    StandardTestDispatcher(testScheduler),
                )
            viewModel.refresh()
            assertEquals(MainUiState.Ready("rcode-core 0.1.0"), viewModel.state.value)
        }

    @Test
    fun `bridge failure publishes Error`() =
        runTest {
            val viewModel =
                MainViewModel(
                    FakeBridge { throw IllegalStateException("boom") },
                    FakeEditorSession(),
                    FakeDocumentIo(),
                    StandardTestDispatcher(testScheduler),
                    StandardTestDispatcher(testScheduler),
                )
            viewModel.refresh()
            assertEquals(MainUiState.Error("boom"), viewModel.state.value)
        }

    @Test
    fun `editor session is exposed for the screen`() {
        val session = FakeEditorSession("hello")
        val viewModel =
            MainViewModel(
                FakeBridge { "rcode-core 0.1.0" },
                session,
                FakeDocumentIo(),
                StandardTestDispatcher(),
                StandardTestDispatcher(),
            )

        assertEquals("hello", viewModel.editorSession.text())
    }

    // endregion

    // region open flow (T-0012)

    @Test
    fun `open loads the bytes and publishes the provider display name`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { add(key, "hello\n", displayName = "a.txt") }
            val session = FakeEditorSession()
            val viewModel = newViewModel(io, session)

            viewModel.openDocument(key)
            advanceUntilIdle()

            assertEquals(DocumentUiState.Opened("a.txt", canWrite = true, documentId = 1), viewModel.documentState.value)
            assertNull(viewModel.openRequest.value)
            assertNull(viewModel.openError.value)
            assertEquals("hello\n", session.text())
            assertFalse(viewModel.dirty.value)
            assertNotNull(viewModel.currentDocument)
            assertSame(io.savables.getValue(key), viewModel.currentDocument)
            assertTrue(io.released.isEmpty())
        }

    @Test
    fun `open reports a read-only document`() =
        runTest {
            val key = DocumentKey("content://test/ro.txt")
            val io = FakeDocumentIo().apply { add(key, "body", canWrite = false) }
            val viewModel = newViewModel(io)

            viewModel.openDocument(key)
            advanceUntilIdle()

            assertEquals(
                DocumentUiState.Opened("ro.txt", canWrite = false, documentId = 1),
                viewModel.documentState.value,
            )
        }

    @Test
    fun `large file asks first and confirm opens it`() =
        runTest {
            val key = DocumentKey("content://test/big.txt")
            val io = FakeDocumentIo().apply { add(key, "0123456789", displayName = "big.txt") }
            val session = FakeEditorSession()
            val viewModel = newViewModel(io, session, largeFileThresholdBytes = 8)

            viewModel.openDocument(key)
            advanceUntilIdle()

            assertEquals(OpenRequest.ConfirmLargeFile("big.txt", 10), viewModel.openRequest.value)
            assertEquals(DocumentUiState.Untitled, viewModel.documentState.value)
            assertEquals("", session.text())

            viewModel.confirmOpen()
            advanceUntilIdle()

            assertEquals(DocumentUiState.Opened("big.txt", canWrite = true, documentId = 1), viewModel.documentState.value)
            assertEquals("0123456789", session.text())
            assertNull(viewModel.openRequest.value)
        }

    @Test
    fun `cancelling the large-file prompt keeps the document and releases the new grant`() =
        runTest {
            val first = DocumentKey("content://test/a.txt")
            val second = DocumentKey("content://test/big.txt")
            val io =
                FakeDocumentIo().apply {
                    add(first, "alpha")
                    add(second, "0123456789", displayName = "big.txt")
                }
            val session = FakeEditorSession()
            val viewModel = newViewModel(io, session, largeFileThresholdBytes = 8)

            viewModel.openDocument(first)
            advanceUntilIdle()
            val opened = viewModel.documentState.value

            viewModel.openDocument(second)
            advanceUntilIdle()
            assertEquals(OpenRequest.ConfirmLargeFile("big.txt", 10), viewModel.openRequest.value)

            viewModel.cancelOpen()
            advanceUntilIdle()

            assertEquals(opened, viewModel.documentState.value)
            assertEquals("alpha", session.text())
            assertNull(viewModel.openRequest.value)
            assertNull(viewModel.openError.value)
            assertEquals(listOf(second), io.released)
        }

    @Test
    fun `read failures surface as failures and keep the current document`() =
        runTest {
            val first = DocumentKey("content://test/a.txt")
            val failing = DocumentKey("content://test/gone.txt")
            val io = FakeDocumentIo().apply { add(first, "alpha") }
            val viewModel = newViewModel(io)

            viewModel.openDocument(first)
            advanceUntilIdle()
            val opened = viewModel.documentState.value

            val failures =
                listOf(
                    DocumentReadException.NotFound("gone.txt") to OpenFailure.FileNotFound,
                    DocumentReadException.PermissionDenied("gone.txt") to OpenFailure.PermissionDenied,
                    DocumentReadException.TooLarge(64L * 1024 * 1024) to OpenFailure.TooLarge(64L * 1024 * 1024),
                    DocumentReadException.Io("gone.txt", java.io.IOException("disk")) to OpenFailure.ReadFailed,
                )
            for ((exception, expected) in failures) {
                io.failure = exception

                viewModel.openDocument(failing)
                advanceUntilIdle()

                assertEquals(expected, viewModel.openError.value)
                assertEquals(opened, viewModel.documentState.value)
                assertEquals("alpha", viewModel.editorSession.text())

                viewModel.dismissOpenError()
                assertNull(viewModel.openError.value)
            }
        }

    @Test
    fun `unsupported files are rejected without touching the document`() =
        runTest {
            val first = DocumentKey("content://test/a.txt")
            val binary = DocumentKey("content://test/b.bin")
            val io =
                FakeDocumentIo().apply {
                    add(first, "alpha")
                    add(binary, "??")
                }
            val session = FakeEditorSession()
            val viewModel = newViewModel(io, session)

            viewModel.openDocument(first)
            advanceUntilIdle()
            val opened = viewModel.documentState.value

            session.openFailure = DocumentOpenException.NotUtf8(offset = 3)
            viewModel.openDocument(binary)
            advanceUntilIdle()

            assertEquals(OpenFailure.NotUtf8(3), viewModel.openError.value)
            assertEquals(opened, viewModel.documentState.value)
            assertEquals("alpha", session.text())
            assertEquals(listOf(binary), io.released)

            viewModel.dismissOpenError()
            session.openFailure = DocumentOpenException.UnsupportedEncoding(UnsupportedEncodingKind.Utf16Le)
            viewModel.openDocument(binary)
            advanceUntilIdle()

            assertEquals(
                OpenFailure.UnsupportedEncoding(UnsupportedEncodingKind.Utf16Le),
                viewModel.openError.value,
            )
            assertEquals(opened, viewModel.documentState.value)
        }

    @Test
    fun `unexpected failures are logged and never swallowed`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { failure = IllegalStateException("boom") }
            val logged = mutableListOf<Pair<String, Throwable?>>()
            val viewModel =
                newViewModel(io, logger = { message, error -> logged += message to error })

            viewModel.openDocument(key)
            advanceUntilIdle()

            assertEquals(OpenFailure.Internal, viewModel.openError.value)
            assertEquals(DocumentUiState.Untitled, viewModel.documentState.value)
            val failure = logged.lastOrNull { (_, error) -> error is IllegalStateException }
            assertNotNull(failure)
            assertEquals("boom", failure?.second?.message)
        }

    @Test
    fun `edits make the document dirty and opening another file asks first`() =
        runTest {
            val first = DocumentKey("content://test/a.txt")
            val second = DocumentKey("content://test/b.txt")
            val io =
                FakeDocumentIo().apply {
                    add(first, "alpha", displayName = "a.txt")
                    add(second, "beta", displayName = "b.txt")
                }
            val session = FakeEditorSession()
            val viewModel = newViewModel(io, session)

            viewModel.openDocument(first)
            advanceUntilIdle()
            val opened = viewModel.documentState.value

            session.insertText("!")
            viewModel.onDocumentVersionChanged(session.version())
            assertTrue(viewModel.dirty.value)

            viewModel.openDocument(second)
            advanceUntilIdle()
            assertEquals(OpenRequest.ConfirmDiscard, viewModel.openRequest.value)
            assertEquals(1, io.openCount)

            viewModel.cancelOpen()
            advanceUntilIdle()
            assertEquals(opened, viewModel.documentState.value)
            assertEquals("!alpha", session.text())
            assertTrue(viewModel.dirty.value)
            assertNull(viewModel.openRequest.value)

            viewModel.openDocument(second)
            advanceUntilIdle()
            viewModel.confirmOpen()
            advanceUntilIdle()

            assertEquals(DocumentUiState.Opened("b.txt", canWrite = true, documentId = 2), viewModel.documentState.value)
            assertEquals("beta", session.text())
            assertFalse(viewModel.dirty.value)
            assertEquals(listOf(first), io.released)
        }

    @Test
    fun `untitled edits ask before being discarded`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { add(key, "alpha") }
            val session = FakeEditorSession()
            val viewModel = newViewModel(io, session)

            session.insertText("draft")
            viewModel.onDocumentVersionChanged(session.version())

            viewModel.openDocument(key)
            advanceUntilIdle()

            assertEquals(OpenRequest.ConfirmDiscard, viewModel.openRequest.value)
            assertEquals(DocumentUiState.Untitled, viewModel.documentState.value)
            assertEquals(0, io.openCount)
        }

    @Test
    fun `a second request while reading is ignored`() =
        runTest {
            val first = DocumentKey("content://test/a.txt")
            val second = DocumentKey("content://test/b.txt")
            val gate = CompletableDeferred<Unit>()
            val io =
                FakeDocumentIo().apply {
                    add(first, "alpha")
                    add(second, "beta")
                    this.gate = gate
                }
            val viewModel = newViewModel(io)

            viewModel.openDocument(first)
            advanceUntilIdle()
            assertEquals(1, io.openCount)
            assertEquals(OpenRequest.Reading, viewModel.openRequest.value)

            viewModel.openDocument(second)
            advanceUntilIdle()
            assertEquals(1, io.openCount)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(DocumentUiState.Opened("a.txt", canWrite = true, documentId = 1), viewModel.documentState.value)
            assertEquals("alpha", viewModel.editorSession.text())
        }

    @Test
    fun `cancelling a read publishes no failure`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val gate = CompletableDeferred<Unit>()
            val io =
                FakeDocumentIo().apply {
                    add(key, "alpha")
                    this.gate = gate
                }
            val viewModel = newViewModel(io)

            viewModel.openDocument(key)
            advanceUntilIdle()
            assertEquals(OpenRequest.Reading, viewModel.openRequest.value)

            viewModel.cancelOpen()
            advanceUntilIdle()

            assertNull(viewModel.openError.value)
            assertNull(viewModel.openRequest.value)
            assertEquals(DocumentUiState.Untitled, viewModel.documentState.value)
        }

    @Test
    fun `reopening the same document keeps its grant`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { add(key, "alpha") }
            val viewModel = newViewModel(io)

            viewModel.openDocument(key)
            advanceUntilIdle()
            viewModel.openDocument(key)
            advanceUntilIdle()

            assertEquals(DocumentUiState.Opened("a.txt", canWrite = true, documentId = 2), viewModel.documentState.value)
            assertTrue(io.released.isEmpty())
        }

    // endregion

    private fun TestScope.newViewModel(
        io: DocumentIo,
        session: FakeEditorSession = FakeEditorSession(),
        largeFileThresholdBytes: Long = MainViewModel.LARGE_FILE_THRESHOLD_BYTES,
        logger: (String, Throwable?) -> Unit = { _, _ -> },
    ): MainViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return MainViewModel(
            bridge = FakeBridge { "rcode-core 0.1.0" },
            editorSession = session,
            documentIo = io,
            workDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            largeFileThresholdBytes = largeFileThresholdBytes,
            logger = logger,
        )
    }
}

/** In-memory [DocumentIo] for the open-flow tests; never touches Android APIs. */
private class FakeDocumentIo : DocumentIo {
    private val documents = mutableMapOf<DocumentKey, ByteArray>()
    private val names = mutableMapOf<DocumentKey, String>()
    private val writable = mutableMapOf<DocumentKey, Boolean>()

    /** [SavableDocument] per key, so tests can assert the handle handed to T-0013. */
    val savables = mutableMapOf<DocumentKey, SavableDocument>()
    val released = mutableListOf<DocumentKey>()
    var openCount = 0
        private set
    var failure: Exception? = null

    /** When set, [open] suspends until it completes (re-entrancy and cancellation tests). */
    var gate: CompletableDeferred<Unit>? = null

    fun add(
        key: DocumentKey,
        text: String,
        displayName: String = key.uri.substringAfterLast('/'),
        canWrite: Boolean = true,
    ) {
        documents[key] = text.encodeToByteArray()
        names[key] = displayName
        writable[key] = canWrite
        savables[key] = FakeSavableDocument(key.uri, canWrite)
    }

    override suspend fun open(key: DocumentKey): ReadDocument {
        openCount += 1
        gate?.await()
        failure?.let { throw it }
        val bytes = documents[key] ?: throw DocumentReadException.NotFound(key.uri)
        return ReadDocument(
            key = key,
            displayName = names.getValue(key),
            bytes = bytes,
            canWrite = writable.getValue(key),
            savable = savables.getValue(key),
        )
    }

    override suspend fun release(key: DocumentKey) {
        released += key
    }
}

private class FakeSavableDocument(
    override val description: String,
    private val writable: Boolean,
) : SavableDocument {
    override fun openRead(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun openWrite(): OutputStream {
        check(writable) { "$description is read-only" }
        return OutputStream.nullOutputStream()
    }
}
