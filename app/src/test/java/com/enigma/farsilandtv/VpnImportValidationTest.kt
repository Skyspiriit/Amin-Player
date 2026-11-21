package com.enigma.farsilandtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnImportValidationTest {
    @Test fun importedFilesKeepTheirPayloadAndNormalizeTheTextWrapper() {
        val url = "https://freesocks.org/sub/test-fixture"
        assertEquals(url, VpnImport.text("\uFEFF$url\r\n"))
        assertTrue(VpnImport.remote(VpnImport.text(url)))
        val inline = "vless://fixture"
        assertEquals(inline, VpnImport.text(inline))
        assertTrue(!VpnImport.remote(inline))
    }

    @Test fun insecureReferencesEmptyFilesAndOversizedFilesAreRejected() {
        assertTrue(runCatching { VpnImport.text("http://freesocks.org/sub/test-fixture") }.isFailure)
        assertTrue(runCatching { VpnImport.text("\n\r ") }.isFailure)
        assertTrue(runCatching { VpnImport.text("a".repeat(SubscriptionAddress.MAX_BYTES + 1)) }.isFailure)
    }
}
