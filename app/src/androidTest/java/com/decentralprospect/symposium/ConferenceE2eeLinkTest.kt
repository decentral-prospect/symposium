package com.decentralprospect.symposium

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConferenceE2eeLinkTest {

    @Test
    fun conferenceSecretLivesOnlyInUrlFragment() {
        val secret = generateConferenceE2eeSecret()
        val link = buildConnectHttpRedirectLink(
            ip = "relay.example",
            httpsPort = 443,
            room = "private-room",
            relayTlsPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            e2eeSecret = secret
        )

        val uri = Uri.parse(link)
        assertFalse("HTTP query leaked the E2EE secret", uri.query.orEmpty().contains(secret))
        assertTrue(uri.fragment.orEmpty().contains(secret))
        assertEquals(secret, parseConnectLink(link)?.e2eeSecret)
    }

    @Test
    fun linksWithoutAValidE2eeSecretAreRejected() {
        val legacy = "symposium://connect?ip=relay.example&room=room&tlsPin=sha256%2FAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA%3D"
        assertNull(parseConnectLink(legacy))
    }

    @Test
    fun generatedSecretIsCanonicalAndFullEntropy() {
        val secret = generateConferenceE2eeSecret()
        assertEquals(43, secret.length)
        assertEquals(secret, normalizeConferenceE2eeSecret(secret))
        assertEquals(CONFERENCE_E2EE_SECRET_BYTES, decodeConferenceE2eeSecret(secret).size)

        val another = generateConferenceE2eeSecret()
        assertFalse(secret == another)
    }

    @Test
    fun importedSecretIsReusedForTheCanonicalRelayEndpoint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ConferenceE2eeSecretStore(context)
        val room = "imported-${System.nanoTime()}"
        val secret = generateConferenceE2eeSecret()

        try {
            store.save("relay.example:38443", null, room, secret)
            assertEquals(secret, store.getOrCreate("relay.example", 38443, room))
        } finally {
            store.remove("relay.example", 38443, room)
        }
    }
}
