package com.hbcao.rcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hbcao.rcode.bridge.UniffiCoreBridge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check: Kotlin -> UniFFI -> librcode_ffi.so (arm64) -> back.
 * Requires a device or emulator; run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class CorePingInstrumentedTest {
    @Test
    fun pingReturnsCoreVersion() {
        assertEquals("rcode-core 0.1.0", UniffiCoreBridge().ping())
    }
}
