package com.decentralprospect.symposium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun sameVersionWithoutVPrefixDoesNotOfferUpdate() {
        assertNull(availableVersionFromResponse("v0.3.3", " 0.3.3\n"))
    }

    @Test
    fun differentVersionIsReturned() {
        assertEquals("v0.3.4", availableVersionFromResponse("v0.3.3", "v0.3.4"))
    }

    @Test
    fun olderDifferentVersionIsStillReturned() {
        assertEquals("0.3.2", availableVersionFromResponse("v0.3.3", "0.3.2"))
    }

    @Test
    fun blankOrUnexpectedResponseIsIgnored() {
        assertNull(availableVersionFromResponse("v0.3.3", ""))
        assertNull(availableVersionFromResponse("v0.3.3", "<html>offline</html>"))
    }
}
