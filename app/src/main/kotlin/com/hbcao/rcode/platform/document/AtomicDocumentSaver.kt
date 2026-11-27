package com.hbcao.rcode.platform.document

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Saves a document transactionally, preserving the original bytes on any catchable
 * failure (A7, `architecture.md` §3.6).
 *
 * # Protocol
 *
 * 1. Write the new bytes to `cacheDir/save/new-*.tmp`, flush and best-effort `fsync`.
 * 2. Copy the original into `cacheDir/save/old-*.tmp`, flush and best-effort `fsync`.
 * 3. Open the document with [SavableDocument.openWrite] (truncating) and copy the new
 *    bytes in.
 * 4. Read the document back and compare it byte for byte with the new bytes.
 * 5. On success, or after a successful rollback, delete both temporary files.
 *
 * Any failure before step 3 returns [SaveStage.WRITE_TEMP] or
 * [SaveStage.READ_ORIGINAL] with the original untouched. A failure in step 3 or 4
 * restores the original from the backup and returns [SaveStage.WRITE_ORIGINAL] or
 * [SaveStage.VERIFY] with `originalPreserved = true`. If the restore itself fails, the
 * result is [SaveStage.RESTORE] with `originalPreserved = false` and the backup is
 * **kept**: it is the only remaining copy of the original bytes, and its path is
 * reported so the UI can tell the user where it is.
 *
 * # Guarantee boundary (Q-T10-1, Q-T10-2)
 *
 * This class guarantees **failure atomicity for catchable errors**: a failed save
 * leaves the document with its pre-save bytes. It does **not** guarantee crash
 * atomicity: if the process is killed between truncating the document and finishing
 * the restore, the file on disk can be incomplete. No save has reported success in
 * that case, so the draft mechanism (T-0022/T-0023) remains the recovery path for
 * unsaved text (G4). Disk-level crash recovery (journal, startup check) is an M2
 * evaluation and out of scope here.
 *
 * # Threading
 *
 * [save] must run off the UI thread; the caller picks the coroutine and this class
 * moves the transaction to [ioDispatcher]. Calls on one instance are serialized by a
 * [Mutex], so a second save (or a save started after the document went dirty again)
 * never interleaves with the first. The wait for the mutex is cancellable; once the
 * transaction starts it runs under [NonCancellable], so cancelling the coroutine
 * cannot stop it between truncating the document and rolling back. Cancellation is
 * rethrown afterwards; the transaction always ends in a complete success or a
 * complete rollback.
 *
 * # Memory
 *
 * The new bytes and the backup file cost about two extra document sizes in
 * `cacheDir` during a save; both temporary files are deleted afterwards, except a
 * backup kept for [SaveStage.RESTORE].
 */
class AtomicDocumentSaver(
    private val cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DocumentSaver {
    private val mutex = Mutex()

    /**
     * Writes [bytes] to [document] and verifies the result.
     *
     * Never throws for I/O problems: every failure is returned as
     * [SaveResult.Failure]. [CancellationException] is propagated only after the
     * transaction reached a terminal state.
     */
    override suspend fun save(
        document: SavableDocument,
        bytes: ByteArray,
    ): SaveResult =
        mutex.withLock {
            withContext(ioDispatcher + NonCancellable) {
                saveTransaction(document, bytes)
            }
        }

    private fun saveTransaction(
        document: SavableDocument,
        bytes: ByteArray,
    ): SaveResult {
        val directory = File(cacheDir, TEMP_DIRECTORY_NAME)
        val newFile =
            try {
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw IOException("cannot create temporary directory ${directory.path}")
                }
                File.createTempFile(NEW_PREFIX, TEMP_SUFFIX, directory)
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                return SaveResult.Failure(SaveStage.WRITE_TEMP, e, originalPreserved = true)
            }

        var backup: File? = null
        var keepBackup = false
        try {
            try {
                writeFile(newFile, bytes)
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                return SaveResult.Failure(SaveStage.WRITE_TEMP, e, originalPreserved = true)
            }

            backup =
                try {
                    createBackup(document, directory)
                } catch (e: Exception) {
                    rethrowIfCancellation(e)
                    return SaveResult.Failure(SaveStage.READ_ORIGINAL, e, originalPreserved = true)
                }

            try {
                writeDocument(document, newFile)
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                val result = rollback(document, backup, SaveStage.WRITE_ORIGINAL, e)
                keepBackup = result.stage == SaveStage.RESTORE
                return result
            }

            val verified =
                try {
                    contentMatches(document, bytes)
                } catch (e: Exception) {
                    rethrowIfCancellation(e)
                    val result = rollback(document, backup, SaveStage.VERIFY, e)
                    keepBackup = result.stage == SaveStage.RESTORE
                    return result
                }
            if (!verified) {
                val cause =
                    IOException(
                        "content read back from ${document.description} differs from the saved bytes",
                    )
                val result = rollback(document, backup, SaveStage.VERIFY, cause)
                keepBackup = result.stage == SaveStage.RESTORE
                return result
            }
            return SaveResult.Success
        } finally {
            newFile.delete()
            if (!keepBackup) {
                backup?.delete()
            }
        }
    }

    /**
     * Copies the original into `old-*.tmp` and returns the file.
     *
     * On failure the partially written backup is deleted and the error rethrown; the
     * document is not touched.
     */
    private fun createBackup(
        document: SavableDocument,
        directory: File,
    ): File {
        val backup = File.createTempFile(OLD_PREFIX, TEMP_SUFFIX, directory)
        try {
            document.openRead().use { input ->
                FileOutputStream(backup).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                    output.flush()
                    syncIfPossible(output)
                }
            }
        } catch (e: Exception) {
            backup.delete()
            throw e
        }
        return backup
    }

    private fun writeFile(
        file: File,
        bytes: ByteArray,
    ) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            syncIfPossible(output)
        }
    }

    private fun writeDocument(
        document: SavableDocument,
        source: File,
    ) {
        document.openWrite().use { output ->
            FileInputStream(source).use { input -> input.copyTo(output, BUFFER_SIZE) }
            output.flush()
            syncIfPossible(output)
        }
    }

    /** Reads [document] back and compares it with [expected] without buffering it whole. */
    private fun contentMatches(
        document: SavableDocument,
        expected: ByteArray,
    ): Boolean = document.openRead().use { input -> streamMatches(input, expected) }

    private fun streamMatches(
        input: InputStream,
        expected: ByteArray,
    ): Boolean {
        var index = 0
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                return index == expected.size
            }
            if (index + read > expected.size) {
                return false
            }
            for (offset in 0 until read) {
                if (buffer[offset] != expected[index + offset]) {
                    return false
                }
            }
            index += read
        }
    }

    /**
     * Restores the original from [backup].
     *
     * Returns `originalPreserved = true` on success and keeps [backup] only when the
     * restore itself fails, because then the backup is the only copy of the original.
     */
    private fun rollback(
        document: SavableDocument,
        backup: File,
        stage: SaveStage,
        cause: Throwable,
    ): SaveResult.Failure =
        try {
            writeDocument(document, backup)
            SaveResult.Failure(stage, cause, originalPreserved = true)
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            SaveResult.Failure(
                stage = SaveStage.RESTORE,
                cause = cause,
                originalPreserved = false,
                backupPath = backup.path,
                restoreCause = e,
            )
        }

    /**
     * Best-effort `fsync` (Q-T10-4).
     *
     * A provider-backed stream may not support it, and failing the save for that would
     * reject otherwise valid documents; the write itself has already been flushed.
     */
    private fun syncIfPossible(stream: OutputStream) {
        if (stream is FileOutputStream) {
            try {
                stream.fd.sync()
            } catch (_: IOException) {
                // Best effort: see the KDoc.
            }
        }
    }

    private fun rethrowIfCancellation(e: Exception) {
        if (e is CancellationException) {
            throw e
        }
    }

    internal companion object {
        /** Directory under `cacheDir` that holds in-flight temporary files. */
        internal const val TEMP_DIRECTORY_NAME = "save"

        internal const val NEW_PREFIX = "new-"
        internal const val OLD_PREFIX = "old-"
        internal const val TEMP_SUFFIX = ".tmp"

        /** Copy/compare chunk size; large enough to keep 10 MB saves syscall-bound. */
        internal const val BUFFER_SIZE = 64 * 1024
    }
}
