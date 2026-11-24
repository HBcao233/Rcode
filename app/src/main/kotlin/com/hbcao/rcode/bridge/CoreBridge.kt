package com.hbcao.rcode.bridge

import uniffi.rcode_ffi.ping as nativePing

/**
 * Thin wrapper around the UniFFI-generated Rust bindings.
 * Contains no business logic (see .agent/architecture.md).
 */
interface CoreBridge {
    /** Returns the liveness string from the Rust core, e.g. "rcode-core 0.1.0". */
    fun ping(): String
}

/** Production implementation that calls into `libcore` via UniFFI. */
class UniffiCoreBridge : CoreBridge {
    override fun ping(): String = nativePing()
}
