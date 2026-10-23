package hu.szfvbus.data

import hu.szfvbus.data.api.UpdateClient.Companion.isNewer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateClientTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewer("v0.5.1", "0.5.0"))
        assertTrue(isNewer("v0.10.0", "0.9.1"))
        assertTrue(isNewer("v1.0", "0.9.9"))
        assertFalse(isNewer("v0.5.0", "0.5.0"))
        assertFalse(isNewer("v0.5.0", "0.5.1"))
        assertFalse(isNewer("v0.5", "0.5.0"))
    }

    @Test
    fun ignoresSuffixes() {
        assertFalse(isNewer("v0.5.0-beta", "0.5.0"))
        assertTrue(isNewer("v0.6.0-beta", "0.5.0"))
    }
}
