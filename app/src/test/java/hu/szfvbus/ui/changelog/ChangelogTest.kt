package hu.szfvbus.ui.changelog

import hu.szfvbus.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {
    @Test
    fun newestReleaseIsTheBuiltVersion() {
        assertEquals(BuildConfig.VERSION_NAME, RELEASES.first().version)
        val versions = RELEASES.map { it.version }
        assertEquals(versions.distinct(), versions)
        assertTrue(RELEASES.all { it.changes.isNotEmpty() })
    }
}
