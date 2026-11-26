package com.hbcao.rcode.platform.document

import java.io.InputStream
import java.io.OutputStream

/**
 * A document the app can save into, abstracted over SAF and plain files.
 *
 * Implementations must open [openWrite] in truncating mode, as the save transaction
 * replaces the whole file. [AtomicDocumentSaver] reads the original through
 * [openRead] to build its backup and to verify the result, so a document that can
 * only be written is not savable.
 */
interface SavableDocument {
    /**
     * Human-readable identification for failure reports and logs.
     *
     * Use the URI or file path without query parameters or credentials; never include
     * file contents.
     */
    val description: String

    /** Opens the current contents for reading. The caller closes the stream. */
    fun openRead(): InputStream

    /** Opens the document for writing, truncating it. The caller closes the stream. */
    fun openWrite(): OutputStream
}
