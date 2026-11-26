package com.hbcao.rcode.platform.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM tests for the save transaction (A7): real files, injected failures.
 *
 * `EditorSession.encode` is covered by Rust tests; here the bytes are passed in
 * directly, which is exactly the seam T-0013 uses.
 */
class AtomicDocumentSaverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val original = "original content\n".toByteArray()

    private lateinit var cacheDir: File
    private lateinit var documentFile: File
    private lateinit var saver: AtomicDocumentSaver

    @Before
    fun setUp() {
        cacheDir = folder.newFolder("cache")
        documentFile = folder.newFile("document.txt")
        documentFile.writeBytes(original)
        saver = AtomicDocumentSaver(cacheDir, ioDispatcher = Dispatchers.IO)
    }

    @Test
    fun `successful save writes the bytes and deletes temporary files`() =
        runBlocking {
            val bytes = "new content\r\n".toByteArray()

            val result = saver.save(FakeSavableDocument(documentFile), bytes)

            assertTrue("unexpected result: $result", result is SaveResult.Success)
            assertArrayEquals(bytes, documentFile.readBytes())
            assertNoTemporaryFiles()
        }

    @Test
    fun `temp file failure leaves the original untouched`() =
        runBlocking {
            // A regular file where the cache directory should be: mkdirs() must fail.
            val blockedCache = folder.newFile("not-a-directory")
            val blockedSaver = AtomicDocumentSaver(blockedCache, ioDispatcher = Dispatchers.IO)

            val result = blockedSaver.save(FakeSavableDocument(documentFile), "new".toByteArray())

            val failure = result as SaveResult.Failure
            assertEquals(SaveStage.WRITE_TEMP, failure.stage)
            assertTrue(failure.originalPreserved)
            assertArrayEquals(original, documentFile.readBytes())
        }

    @Test
    fun `backup read failure leaves the original untouched`() =
        runBlocking {
            val document = FakeSavableDocument(documentFile, readError = { call -> call == 1 })

            val result = saver.save(document, "new".toByteArray())

            val failure = result as SaveResult.Failure
            assertEquals(SaveStage.READ_ORIGINAL, failure.stage)
            assertTrue(failure.originalPreserved)
            assertArrayEquals(original, documentFile.readBytes())
            assertNoTemporaryFiles()
        }

    @Test
    fun `partial overwrite is rolled back`() =
        runBlocking {
            // First openWrite truncates and fails halfway; the rollback write works.
            val document = FakeSavableDocument(documentFile, writeError = { call -> call == 1 })

            val result = saver.save(document, "new content that is much longer".toByteArray())

            val failure = result as SaveResult.Failure
            assertEquals(SaveStage.WRITE_ORIGINAL, failure.stage)
            assertTrue(failure.originalPreserved)
            assertNotNull(failure.cause)
            assertArrayEquals(original, documentFile.readBytes())
            assertNoTemporaryFiles()
        }

    @Test
    fun `verification mismatch is rolled back`() =
        runBlocking {
            // The backup sees the real file, the verification read sees other bytes.
            val document =
                FakeSavableDocument(
                    documentFile,
                    readOverride = { call, content ->
                        if (call == 2) "corrupted".toByteArray() else content
                    },
                )

            val result = saver.save(document, "new".toByteArray())

            val failure = result as SaveResult.Failure
            assertEquals(SaveStage.VERIFY, failure.stage)
            assertTrue(failure.originalPreserved)
            assertArrayEquals(original, documentFile.readBytes())
            assertNoTemporaryFiles()
        }

    @Test
    fun `failed restore keeps the backup and reports it`() =
        runBlocking {
            // Every write fails: the overwrite truncates the file and the rollback too.
            val document = FakeSavableDocument(documentFile, writeError = { true })

            val result = saver.save(document, "new content that is much longer".toByteArray())

            val failure = result as SaveResult.Failure
            assertEquals(SaveStage.RESTORE, failure.stage)
            assertFalse(failure.originalPreserved)
            assertNotNull(failure.restoreCause)
            val backup = failure.backupPath
            assertNotNull("a failed restore must report the backup path", backup)
            backup ?: return@runBlocking
            assertTrue("backup file should exist: $backup", File(backup).isFile)
            assertArrayEquals(original, File(backup).readBytes())
            assertFalse(documentFile.readBytes().contentEquals(original))
            assertNoNewTemporaryFiles()
        }

    @Test
    fun `empty bytes truncate the document`() =
        runBlocking {
            val result = saver.save(FakeSavableDocument(documentFile), ByteArray(0))

            assertTrue("unexpected result: $result", result is SaveResult.Success)
            assertEquals(0L, documentFile.length())
            assertNoTemporaryFiles()
        }

    @Test
    fun `concurrent saves are serialized`() =
        runBlocking {
            val first = ByteArray(256 * 1024) { 'A'.code.toByte() }
            val second = ByteArray(256 * 1024) { 'B'.code.toByte() }
            val document = FakeSavableDocument(documentFile)

            val results =
                listOf(
                    async(Dispatchers.IO) { saver.save(document, first) },
                    async(Dispatchers.IO) { saver.save(document, second) },
                ).awaitAll()

            assertTrue(results.all { it is SaveResult.Success })
            val content = documentFile.readBytes()
            assertTrue(
                "the document must contain one complete payload, not an interleaving",
                content.contentEquals(first) || content.contentEquals(second),
            )
            assertNoTemporaryFiles()
        }

    @Test
    fun `cancellation cannot interrupt the transaction`() =
        runBlocking {
            val bytes = ByteArray(4 * 1024) { 'N'.code.toByte() }
            val writeStarted = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val document =
                FakeSavableDocument(
                    documentFile,
                    onWriteOpened = { call ->
                        if (call == 1) {
                            writeStarted.countDown()
                            releaseWrite.await(10, TimeUnit.SECONDS)
                        }
                    },
                )

            val job = launch(Dispatchers.IO) { saver.save(document, bytes) }
            assertTrue("the overwrite never started", writeStarted.await(10, TimeUnit.SECONDS))
            job.cancel()
            releaseWrite.countDown()
            job.join()

            // NonCancellable finishes the write; the document is never left truncated or
            // partially written. (A successful transaction is also acceptable.)
            assertArrayEquals(bytes, documentFile.readBytes())
            assertNoTemporaryFiles()
        }

    @Test
    fun `ten megabyte save is measured and completes`() =
        runBlocking {
            // V10: not a gate, just a recorded number. The document is a 10 MB ASCII
            // file; the cache peak is read while both temporary files exist.
            val bytes = ByteArray(10 * 1024 * 1024) { index -> ('a'.code + index % 26).toByte() }
            val saved = bytes.copyOf().also { it[0] = 'z'.code.toByte() }
            documentFile.writeBytes(bytes)
            var cachePeakBytes = 0L
            val document =
                FakeSavableDocument(
                    documentFile,
                    onWriteOpened = {
                        cachePeakBytes = tempFiles().sumOf { it.length() }
                    },
                )

            val startedAt = System.nanoTime()
            val result = saver.save(document, saved)
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            println(
                "10 MB save: $elapsedMillis ms, cache peak ${cachePeakBytes / (1024 * 1024)} MB",
            )
            assertTrue("unexpected result: $result", result is SaveResult.Success)
            assertArrayEquals(saved, documentFile.readBytes())
            assertNoTemporaryFiles()
        }

    private fun tempFiles(): List<File> {
        val directory = File(cacheDir, AtomicDocumentSaver.TEMP_DIRECTORY_NAME)
        return directory.listFiles()?.toList().orEmpty()
    }

    private fun assertNoTemporaryFiles() {
        assertTrue("temporary files left behind: ${tempFiles().map { it.name }}", tempFiles().isEmpty())
    }

    private fun assertNoNewTemporaryFiles() {
        assertTrue(
            "new-*.tmp must be removed even when the restore fails: ${tempFiles().map { it.name }}",
            tempFiles().none { it.name.startsWith(AtomicDocumentSaver.NEW_PREFIX) },
        )
    }
}

/**
 * [SavableDocument] backed by a real file with injectable failures.
 *
 * `readError`/`writeError` decide per call (1-based) whether the operation fails;
 * `readOverride` can return different content for the verification read.
 */
private class FakeSavableDocument(
    private val file: File,
    private val readError: (call: Int) -> Boolean = { false },
    private val readOverride: ((call: Int, content: ByteArray) -> ByteArray)? = null,
    private val writeError: (call: Int) -> Boolean = { false },
    private val onWriteOpened: (call: Int) -> Unit = {},
) : SavableDocument {
    var readCalls: Int = 0
        private set

    var writeCalls: Int = 0
        private set

    override val description: String = "fake:${file.name}"

    override fun openRead(): InputStream {
        readCalls += 1
        val call = readCalls
        if (readError(call)) throw IOException("injected read failure #$call")
        val content = file.readBytes()
        val bytes = readOverride?.invoke(call, content) ?: content
        return ByteArrayInputStream(bytes)
    }

    override fun openWrite(): OutputStream {
        writeCalls += 1
        val call = writeCalls
        // FileOutputStream truncates on open, like SAF's "wt" mode.
        val stream = FileOutputStream(file)
        onWriteOpened(call)
        return if (writeError(call)) FailingOutputStream(stream) else stream
    }
}

/** Writes half of the first chunk and then fails, simulating a mid-write error. */
private class FailingOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    override fun write(b: Int) {
        delegate.write(b)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        val half = len / 2
        if (half > 0) {
            delegate.write(b, off, half)
        }
        delegate.flush()
        throw IOException("injected write failure")
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}
