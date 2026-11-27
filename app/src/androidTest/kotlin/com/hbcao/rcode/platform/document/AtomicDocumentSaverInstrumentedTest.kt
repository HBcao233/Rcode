package com.hbcao.rcode.platform.document

import android.content.ContentResolver
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A7 on a device: saving through [ContentResolverDocument] (the production path) keeps
 * the original bytes when a write fails.
 */
@RunWith(AndroidJUnit4::class)
class AtomicDocumentSaverInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver: ContentResolver get() = context.contentResolver

    @Test
    fun saveThroughContentResolverWritesTheBytes() =
        runBlocking {
            val saver = AtomicDocumentSaver(context.cacheDir)
            withDocument(ORIGINAL) { file, document ->
                val bytes = "new content\r\n".toByteArray()

                val result = saver.save(document, bytes)

                assertTrue("unexpected result: $result", result is SaveResult.Success)
                assertArrayEquals(bytes, file.readBytes())
            }
        }

    @Test
    fun injectedWriteFailurePreservesTheOriginal() =
        runBlocking {
            val saver = AtomicDocumentSaver(context.cacheDir)
            withDocument(ORIGINAL) { file, document ->
                val bytes = "new content that is much longer".toByteArray()

                val result = saver.save(FailingOverwriteDocument(document), bytes)

                val failure = result as SaveResult.Failure
                assertEquals(SaveStage.WRITE_ORIGINAL, failure.stage)
                assertTrue(failure.originalPreserved)
                assertArrayEquals(ORIGINAL, file.readBytes())
            }
        }

    @Test
    fun verificationMismatchPreservesTheOriginal() =
        runBlocking {
            val saver = AtomicDocumentSaver(context.cacheDir)
            withDocument(ORIGINAL) { file, document ->
                val result =
                    saver.save(
                        CorruptVerifyDocument(document),
                        "new content that is much longer".toByteArray(),
                    )

                val failure = result as SaveResult.Failure
                assertEquals(SaveStage.VERIFY, failure.stage)
                assertTrue(failure.originalPreserved)
                assertArrayEquals(ORIGINAL, file.readBytes())
            }
        }

    /**
     * Runs [block] with a fresh file in the app cache directory and a
     * [ContentResolverDocument] pointing at it (`file://` URIs are handled by
     * `ContentResolver` on the API levels this app supports).
     */
    private suspend fun withDocument(
        contents: ByteArray,
        block: suspend (File, ContentResolverDocument) -> Unit,
    ) {
        val file = File.createTempFile("document-", ".txt", context.cacheDir)
        try {
            file.writeBytes(contents)
            block(file, ContentResolverDocument(resolver, Uri.fromFile(file)))
        } finally {
            file.delete()
        }
    }

    private companion object {
        val ORIGINAL = "original content\n".toByteArray()
    }
}

/** Passes reads through and fails the first overwrite after writing half of it. */
internal class FailingOverwriteDocument(
    private val delegate: SavableDocument,
) : SavableDocument {
    private var writes = 0

    override val description: String
        get() = delegate.description

    override fun openRead(): InputStream = delegate.openRead()

    override fun openWrite(): OutputStream {
        writes += 1
        // The delegate truncates on open; the rollback write (call 2) succeeds.
        val stream = delegate.openWrite()
        return if (writes == 1) PartialFailingOutputStream(stream) else stream
    }
}

/** Reports corrupted content for the verification read (the second `openRead`). */
private class CorruptVerifyDocument(
    private val delegate: SavableDocument,
) : SavableDocument {
    private var reads = 0

    override val description: String
        get() = delegate.description

    override fun openRead(): InputStream {
        reads += 1
        if (reads == 2) {
            return "corrupted".byteInputStream()
        }
        return delegate.openRead()
    }

    override fun openWrite(): OutputStream = delegate.openWrite()
}

/** Writes half of the first chunk and then fails. */
internal class PartialFailingOutputStream(
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
