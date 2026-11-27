package com.hbcao.rcode.platform.document

/**
 * Opaque identifier of a document the user picked; a SAF URI string in production.
 *
 * `state` and the JVM unit tests must not depend on `android.net.Uri`, so the key is
 * a plain string and only the `platform` layer converts it back to a URI.
 */
@JvmInline
value class DocumentKey(
    val uri: String,
)

/**
 * A document read from a SAF URI, ready to hand to `EditorSession.open` (T-0012).
 *
 * Deliberately not part of any UI state: [bytes] is only held while the open is in
 * flight, and the view model drops the instance after the Rust session loaded it
 * ([bytes] can be tens of MiB).
 *
 * @property displayName provider display name, or the URI's last path segment.
 * @property path best-effort readable location (see [documentPath]); shown in the header.
 * @property canWrite `true` when a persisted **write** grant was obtained, so the
 * save flow (T-0013) can overwrite the document in place.
 * @property savable handle for the save transaction, backed by the same URI.
 */
class ReadDocument(
    val key: DocumentKey,
    val displayName: String,
    val path: String,
    val bytes: ByteArray,
    val canWrite: Boolean,
    val savable: SavableDocument,
)

/**
 * A document just created by SAF (`ACTION_CREATE_DOCUMENT`), ready to be saved into
 * (T-0013, Q-T13-4).
 *
 * Unlike [ReadDocument] this carries no bytes: the created document is empty, and the
 * current text is written by the save transaction instead of being read back.
 *
 * @property displayName provider display name, or the URI's last path segment.
 * @property path best-effort readable location (see [documentPath]); shown in the header.
 * @property canWrite `true` when a persisted **write** grant was obtained.
 * @property savable handle for the save transaction, backed by the same URI.
 */
class WritableDocument(
    val key: DocumentKey,
    val displayName: String,
    val path: String,
    val canWrite: Boolean,
    val savable: SavableDocument,
)

/**
 * Why a document could not be read from its URI.
 *
 * Mirrors the provider failures the open flow maps to UI states; the cause of
 * [Io] is kept for logcat, never shown to the user.
 */
sealed class DocumentReadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The provider has no document for the URI (moved or deleted). */
    class NotFound(
        description: String,
    ) : DocumentReadException("document not found: $description")

    /** The app does not hold a read grant for the URI. */
    class PermissionDenied(
        description: String,
    ) : DocumentReadException("no read permission for $description")

    /** The stream is longer than [limitBytes]; the reader stops before loading it. */
    class TooLarge(
        val limitBytes: Long,
    ) : DocumentReadException("document exceeds the $limitBytes-byte read limit")

    /** Any other provider/stream failure; [cause] carries the platform exception. */
    class Io(
        description: String,
        cause: Throwable,
    ) : DocumentReadException("reading $description failed", cause)
}

/**
 * Reads whole documents by URI and manages the persisted URI grants (T-0012).
 *
 * Implementations perform I/O; callers must invoke both methods off the main
 * thread. Cancellation propagates as `CancellationException` and is not reported as
 * a [DocumentReadException].
 */
interface DocumentIo {
    /**
     * Reads all bytes of [key], capped by the implementation's size limit.
     *
     * @throws DocumentReadException when the bytes cannot be read.
     */
    @Throws(DocumentReadException::class)
    suspend fun open(key: DocumentKey): ReadDocument

    /**
     * Takes the persisted grant of a URI returned by `ACTION_CREATE_DOCUMENT` and
     * wraps it in a [WritableDocument] without reading its (empty) contents.
     *
     * @throws DocumentReadException when the provider cannot describe the document.
     */
    @Throws(DocumentReadException::class)
    suspend fun adopt(key: DocumentKey): WritableDocument

    /**
     * Releases the persisted grant taken for [key]. A no-op when none is held, so
     * it is safe to call for URIs that were never opened.
     */
    suspend fun release(key: DocumentKey)
}
