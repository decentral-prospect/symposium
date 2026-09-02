package com.decentralprospect.symposium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayReleaseMetadataTest {
    @Test
    fun installerMetadataComesFromLockedBuildConfig() {
        val metadata = configuredRelayRelease()

        assertEquals(BuildConfig.RELAY_RELEASE_VERSION, metadata.version)
        assertEquals(BuildConfig.RELAY_BINARY_URL, metadata.downloadUrl)
        assertEquals(BuildConfig.RELAY_BINARY_SHA256, metadata.sha256)
        assertEquals(
            "https://github.com/decentral-prospect/symposium-relay/releases/download/" +
                "${metadata.version}/symposium-server-linux-amd64",
            metadata.downloadUrl
        )
        assertTrue(metadata.sha256.matches(Regex("^[0-9a-f]{64}$")))
    }
}
