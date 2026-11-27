package com.hbcao.rcode.state

import com.hbcao.rcode.bridge.CoreBridge
import com.hbcao.rcode.editor.FakeEditorSession
import com.hbcao.rcode.platform.document.DocumentIo
import com.hbcao.rcode.platform.document.DocumentKey
import com.hbcao.rcode.platform.document.SaveResult
import com.hbcao.rcode.platform.document.SaveStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM coverage of the save state machine (T-0013): encoding in the real Rust session
 * is replaced by [FakeEditorSession], the write by [FakeDocumentSaver]. V1/V2/V4/V6/V7
 * and the concurrency rules (V8, V9) are asserted here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSaveTest {
    private class FakeBridge : CoreBridge {
        override fun ping(): String = "rcode-core 0.1.0"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `save writes the encoded bytes and clears dirty`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { add(key, "alpha", displayName = "a.txt") }
            val session = FakeEditorSession()
            val saver = FakeDocumentSaver()
            val viewModel = newViewModel(io, session, saver)

            viewModel.openDocument(key)
            advanceUntilIdle()
            session.insertText("!")
            viewModel.onDocumentVersionChanged(session.version())
            assertTrue(viewModel.dirty.value)
            val versionBefore = session.version()

            viewModel.saveDocument()
            advanceUntilIdle()

            assertArrayEquals("!alpha".toByteArray(), saver.written.getValue(io.savables.getValue(key)))
            assertEquals(SaveOutcome.Saved("a.txt", stillDirty = false), viewModel.saveOutcome.value)
            assertNull(viewModel.saveRequest.value)
            assertFalse(viewModel.dirty.value)
            // V2: saving is not an edit.
            assertEquals(versionBefore, session.version())
            assertTrue(session.canUndo())
            assertEquals(1, saver.saveCount)
        }

    @Test
    fun `edits during the save keep the document dirty`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { add(key, "alpha") }
            val session = FakeEditorSession()
            val saver = FakeDocumentSaver()
            val viewModel = newViewModel(io, session, saver)

            viewModel.openDocument(key)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            saver.gate = gate

            viewModel.saveDocument()
            advanceUntilIdle()
            assertEquals(SaveRequest.Saving, viewModel.saveRequest.value)

            // V8: the typed character is not part of the bytes being written.
            session.insertText("beta")
            viewModel.onDocumentVersionChanged(session.version())

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(SaveOutcome.Saved("a.txt", stillDirty = true), viewModel.saveOutcome.value)
            assertTrue(viewModel.dirty.value)
            assertArrayEquals("alpha".toByteArray(), saver.written.getValue(io.savables.getValue(key)))
        }

    @Test
    fun `a write failure keeps the document and dirty state untouched`() =
        runTest {
            val stages =
                listOf(SaveStage.WRITE_TEMP, SaveStage.READ_ORIGINAL, SaveStage.WRITE_ORIGINAL, SaveStage.VERIFY)
            for (stage in stages) {
                val key = DocumentKey("content://test/a.txt")
                val io = FakeDocumentIo().apply { add(key, "alpha") }
                val session = FakeEditorSession()
                val saver = FakeDocumentSaver()
                val viewModel = newViewModel(io, session, saver)

                viewModel.openDocument(key)
                advanceUntilIdle()
                session.insertText("!")
                viewModel.onDocumentVersionChanged(session.version())
                val opened = viewModel.documentState.value
                val version = session.version()
                saver.failure =
                    SaveResult.Failure(
                        stage = stage,
                        cause = java.io.IOException("injected $stage"),
                        originalPreserved = true,
                    )

                viewModel.saveDocument()
                advanceUntilIdle()

                assertEquals(
                    SaveOutcome.Failed(SaveFailure.WriteFailed(stage)),
                    viewModel.saveOutcome.value,
                )
                assertEquals(opened, viewModel.documentState.value)
                assertEquals(version, session.version())
                assertTrue(viewModel.dirty.value)
                assertSame(io.savables.getValue(key), viewModel.currentDocument)
            }
        }

    @Test
    fun `a failed restore surfaces the backup path`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { add(key, "alpha") }
            val saver = FakeDocumentSaver()
            val viewModel = newViewModel(io, saver = saver)
            viewModel.openDocument(key)
            advanceUntilIdle()
            saver.failure =
                SaveResult.Failure(
                    stage = SaveStage.RESTORE,
                    cause = java.io.IOException("write failed"),
                    originalPreserved = false,
                    backupPath = "/cache/save/old-1.tmp",
                    restoreCause = java.io.IOException("restore failed"),
                )

            viewModel.saveDocument()
            advanceUntilIdle()

            assertEquals(
                SaveOutcome.Failed(SaveFailure.BackupLost(SaveStage.RESTORE, "/cache/save/old-1.tmp")),
                viewModel.saveOutcome.value,
            )
        }

    @Test
    fun `unknown save failures are logged and mapped to Internal`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { add(key, "alpha") }
            val saver = FakeDocumentSaver()
            val logged = mutableListOf<Pair<String, Throwable?>>()
            val viewModel = newViewModel(io, saver = saver, logger = { message, error -> logged += message to error })
            viewModel.openDocument(key)
            advanceUntilIdle()
            saver.error = IllegalStateException("boom")

            viewModel.saveDocument()
            advanceUntilIdle()

            assertEquals(SaveOutcome.Failed(SaveFailure.Internal), viewModel.saveOutcome.value)
            assertTrue(logged.any { (_, error) -> error is IllegalStateException })
            assertFalse(viewModel.dirty.value)
        }

    @Test
    fun `untitled documents save as a new file`() =
        runTest {
            val io = FakeDocumentIo()
            val session = FakeEditorSession()
            val saver = FakeDocumentSaver()
            val viewModel = newViewModel(io, session, saver)
            session.insertText("draft")
            viewModel.onDocumentVersionChanged(session.version())

            viewModel.saveDocument()
            advanceUntilIdle()

            assertEquals(
                SaveRequest.SaveAs(SaveAsReason.Untitled, 1),
                viewModel.saveRequest.value,
            )
            assertEquals(0, saver.saveCount)

            val created = DocumentKey("content://test/new.txt")
            io.addCreated(created, "new.txt")
            viewModel.saveAs(created)
            advanceUntilIdle()

            assertEquals(listOf(created), io.adopted)
            assertEquals(
                DocumentUiState.Opened("new.txt", path = created.uri, canWrite = true, documentId = 0),
                viewModel.documentState.value,
            )
            assertSame(io.savables.getValue(created), viewModel.currentDocument)
            assertArrayEquals("draft".toByteArray(), saver.written.getValue(io.savables.getValue(created)))
            assertEquals(SaveOutcome.Saved("new.txt", stillDirty = false), viewModel.saveOutcome.value)
            assertFalse(viewModel.dirty.value)
            assertNull(viewModel.saveRequest.value)
            assertTrue(io.released.isEmpty())
        }

    @Test
    fun `read-only documents save as a new file and drop the old grant`() =
        runTest {
            val readOnly = DocumentKey("content://test/ro.txt")
            val io = FakeDocumentIo().apply { add(readOnly, "alpha", displayName = "ro.txt", canWrite = false) }
            val session = FakeEditorSession()
            val saver = FakeDocumentSaver()
            val viewModel = newViewModel(io, session, saver)

            viewModel.openDocument(readOnly)
            advanceUntilIdle()
            session.insertText("!")
            viewModel.onDocumentVersionChanged(session.version())

            viewModel.saveDocument()
            advanceUntilIdle()
            assertEquals(SaveRequest.SaveAs(SaveAsReason.ReadOnly, 1), viewModel.saveRequest.value)

            val created = DocumentKey("content://test/copy.txt")
            io.addCreated(created, "copy.txt")
            viewModel.saveAs(created)
            advanceUntilIdle()

            assertEquals(
                DocumentUiState.Opened("copy.txt", path = created.uri, canWrite = true, documentId = 1),
                viewModel.documentState.value,
            )
            // The text did not change: the editor must not reset its viewport.
            assertSame(io.savables.getValue(created), viewModel.currentDocument)
            assertArrayEquals("!alpha".toByteArray(), saver.written.getValue(io.savables.getValue(created)))
            assertEquals(listOf(readOnly), io.released)
            assertFalse(viewModel.dirty.value)
        }

    @Test
    fun `save as from the menu asks for a location even for a writable document`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { add(key, "alpha") }
            val saver = FakeDocumentSaver()
            val viewModel = newViewModel(io, saver = saver)
            viewModel.openDocument(key)
            advanceUntilIdle()

            viewModel.saveAsNewDocument()
            advanceUntilIdle()

            assertEquals(SaveRequest.SaveAs(SaveAsReason.Manual, 1), viewModel.saveRequest.value)
            assertEquals(0, saver.saveCount)
        }

    @Test
    fun `repeated save while the transaction runs is ignored`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { add(key, "alpha") }
            val saver = FakeDocumentSaver()
            val gate = CompletableDeferred<Unit>()
            saver.gate = gate
            val viewModel = newViewModel(io, saver = saver)
            viewModel.openDocument(key)
            advanceUntilIdle()

            viewModel.saveDocument()
            advanceUntilIdle()
            viewModel.saveDocument()
            viewModel.saveAsNewDocument()
            advanceUntilIdle()

            assertEquals(1, saver.saveCount)
            assertEquals(SaveRequest.Saving, viewModel.saveRequest.value)
            assertEquals(0, io.adopted.size)

            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(viewModel.saveRequest.value)
        }

    @Test
    fun `opening is ignored while a save is running`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val other = DocumentKey("content://test/b.txt")
            val io =
                FakeDocumentIo().apply {
                    add(key, "alpha")
                    add(other, "beta")
                }
            val saver = FakeDocumentSaver()
            val gate = CompletableDeferred<Unit>()
            saver.gate = gate
            val viewModel = newViewModel(io, saver = saver)
            viewModel.openDocument(key)
            advanceUntilIdle()
            val opened = viewModel.documentState.value

            viewModel.saveDocument()
            advanceUntilIdle()
            viewModel.openDocument(other)
            advanceUntilIdle()

            assertEquals(1, io.openCount)
            assertEquals(opened, viewModel.documentState.value)
            assertTrue(io.released.isEmpty())

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(DocumentUiState.Opened("a.txt", path = key.uri, canWrite = true, documentId = 1), viewModel.documentState.value)
        }

    @Test
    fun `cancelling the save-as request writes nothing`() =
        runTest {
            val io = FakeDocumentIo()
            val saver = FakeDocumentSaver()
            val viewModel = newViewModel(io, saver = saver)
            viewModel.saveDocument()
            advanceUntilIdle()
            assertNotNull(viewModel.saveRequest.value)

            viewModel.cancelSave()
            advanceUntilIdle()

            assertNull(viewModel.saveRequest.value)
            assertEquals(0, saver.saveCount)
            assertEquals(DocumentUiState.Untitled, viewModel.documentState.value)
            assertNull(viewModel.saveOutcome.value)
        }

    @Test
    fun `a failing adopt reports an internal failure without touching the document`() =
        runTest {
            val io = FakeDocumentIo()
            val saver = FakeDocumentSaver()
            val logged = mutableListOf<Pair<String, Throwable?>>()
            val viewModel = newViewModel(io, saver = saver, logger = { message, error -> logged += message to error })
            val created = DocumentKey("content://test/new.txt")
            io.failure = IllegalStateException("no grant")

            viewModel.saveAs(created)
            advanceUntilIdle()

            assertEquals(SaveOutcome.Failed(SaveFailure.Internal), viewModel.saveOutcome.value)
            assertEquals(0, saver.saveCount)
            assertEquals(DocumentUiState.Untitled, viewModel.documentState.value)
            assertTrue(logged.any { (_, error) -> error is IllegalStateException })
        }

    @Test
    fun `the save outcome is one-shot`() =
        runTest {
            val key = DocumentKey("content://test/a.txt")
            val io = FakeDocumentIo().apply { add(key, "alpha") }
            val viewModel = newViewModel(io)
            viewModel.openDocument(key)
            advanceUntilIdle()
            viewModel.saveDocument()
            advanceUntilIdle()
            assertNotNull(viewModel.saveOutcome.value)

            viewModel.dismissSaveOutcome()

            assertNull(viewModel.saveOutcome.value)
        }

    private fun TestScope.newViewModel(
        io: DocumentIo,
        session: FakeEditorSession = FakeEditorSession(),
        saver: FakeDocumentSaver = FakeDocumentSaver(),
        logger: (String, Throwable?) -> Unit = { _, _ -> },
    ): MainViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return MainViewModel(
            bridge = FakeBridge(),
            editorSession = session,
            documentIo = io,
            documentSaver = saver,
            workDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            logger = logger,
        )
    }
}
