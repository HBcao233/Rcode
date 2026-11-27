package com.hbcao.rcode.platform.document

/**
 * Save transaction the UI can drive (T-0013); [AtomicDocumentSaver] is the production
 * implementation.
 *
 * The view model depends on this interface instead of the concrete saver so the JVM
 * unit tests can inject a fake without a `ContentResolver` or a file system.
 */
interface DocumentSaver {
    /**
     * Writes [bytes] to [document] and verifies the result.
     *
     * Never throws for I/O problems: every failure comes back as
     * [SaveResult.Failure] with the stage that failed. Implementations do their own
     * I/O; callers must still invoke this off the UI thread.
     */
    suspend fun save(
        document: SavableDocument,
        bytes: ByteArray,
    ): SaveResult
}
