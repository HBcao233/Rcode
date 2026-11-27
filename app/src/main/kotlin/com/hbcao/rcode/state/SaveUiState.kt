package com.hbcao.rcode.state

import com.hbcao.rcode.platform.document.SaveStage

/** Why a save needs a new location before it can write anything (Q-T13-2/3). */
enum class SaveAsReason {
    /** The document has no file yet (`untitled.txt`). */
    Untitled,

    /** The opened file granted read access only. */
    ReadOnly,

    /** The user picked "Save as" for an already writable document. */
    Manual,
}

/**
 * Transient state of the save flow (T-0013); `null` means no save is pending.
 *
 * Like [OpenRequest] it is separate from [DocumentUiState], so a cancelled save
 * leaves the document untouched.
 */
sealed interface SaveRequest {
    /** The save transaction is running: encoding and/or writing the bytes. */
    data object Saving : SaveRequest

    /**
     * Waiting for `ACTION_CREATE_DOCUMENT` to return a URI.
     *
     * @property id increments per request, so the UI launches the picker exactly once
     *   per request (a rotation must not re-open it, a repeated request must).
     */
    data class SaveAs(
        val reason: SaveAsReason,
        val id: Long,
    ) : SaveRequest
}

/**
 * User-facing classification of a failed save.
 *
 * The underlying exceptions go to logcat only; the UI maps these variants to
 * `strings.xml` (`architecture.md` §3.5).
 */
sealed interface SaveFailure {
    /** The save failed; the document still has its pre-save bytes ([stage] says where). */
    data class WriteFailed(
        val stage: SaveStage,
    ) : SaveFailure

    /**
     * Restoring the original failed: [backupPath] is the only remaining copy and the
     * UI must show it (V5).
     */
    data class BackupLost(
        val stage: SaveStage,
        val backupPath: String,
    ) : SaveFailure

    /** Unexpected failure (bad FFI state, failed adopt, ...); logged, never swallowed. */
    data object Internal : SaveFailure
}

/** One-shot result of a save; dismissed by the UI after it is shown. */
sealed interface SaveOutcome {
    /**
     * The bytes reached the document and were verified.
     *
     * @property stillDirty `true` when the buffer changed while the save was running
     *   (version drift, V8); the saved text is the one encoded before those edits.
     */
    data class Saved(
        val fileName: String,
        val stillDirty: Boolean,
    ) : SaveOutcome

    data class Failed(
        val failure: SaveFailure,
    ) : SaveOutcome
}
