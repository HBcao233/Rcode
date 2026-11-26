package com.hbcao.rcode.platform.document

/**
 * Where a save failed.
 *
 * The values follow the transaction order, so a failure tells the caller how far the
 * save got before it gave up.
 */
enum class SaveStage {
    /** Writing the new bytes to the app-private temporary file. Original untouched. */
    WRITE_TEMP,

    /** Copying the original into a backup. Original untouched. */
    READ_ORIGINAL,

    /** Overwriting the original with the new bytes. Rolled back from the backup. */
    WRITE_ORIGINAL,

    /** Reading the original back and comparing it with the new bytes. Rolled back. */
    VERIFY,

    /** Restoring the original from the backup failed; [SaveResult.Failure.backupPath] is kept. */
    RESTORE,
}

/** Outcome of [AtomicDocumentSaver.save]. Failures carry their cause; nothing is swallowed. */
sealed class SaveResult {
    /** The document now contains exactly the bytes that were saved. */
    data object Success : SaveResult()

    /**
     * The save failed.
     *
     * @property stage step that failed.
     * @property cause error from that step (the write/verify error when
     *   [stage] is [SaveStage.RESTORE]).
     * @property originalPreserved `true` when the document still (or again) contains
     *   its pre-save bytes. `false` only when the restore itself failed.
     * @property backupPath path of the backup that holds the original bytes, kept only
     *   when [originalPreserved] is `false`; the caller must surface it (T-0013).
     * @property restoreCause error from the rollback, when [stage] is
     *   [SaveStage.RESTORE].
     */
    class Failure(
        val stage: SaveStage,
        val cause: Throwable,
        val originalPreserved: Boolean,
        val backupPath: String? = null,
        val restoreCause: Throwable? = null,
    ) : SaveResult()
}
