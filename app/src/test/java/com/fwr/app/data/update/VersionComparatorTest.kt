package com.fwr.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun newerPatchMinorMajorAreDetected() {
        assertTrue(VersionComparator.isNewer("v0.4.6", "0.4.5"))
        assertTrue(VersionComparator.isNewer("0.5.0", "0.4.5"))
        assertTrue(VersionComparator.isNewer("v1.0", "0.9.9"))
        assertTrue(VersionComparator.isNewer("0.4.10", "0.4.9"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(VersionComparator.isNewer("v0.4.5", "0.4.5"))
        assertFalse(VersionComparator.isNewer("0.4.5", "0.4.5-alpha"))
        assertFalse(VersionComparator.isNewer("0.4", "0.4.0"))
        assertFalse(VersionComparator.isNewer("0.4.4", "0.4.5"))
    }

    @Test
    fun garbageTagIsNeverNewer() {
        assertFalse(VersionComparator.isNewer("", "0.4.5"))
        assertFalse(VersionComparator.isNewer("latest", "0.4.5"))
    }

    @Test
    fun cleanStripsPrefix() {
        assertEquals("0.5.0", VersionComparator.clean("v0.5.0"))
        assertEquals("0.5.0", VersionComparator.clean("0.5.0"))
    }
}
