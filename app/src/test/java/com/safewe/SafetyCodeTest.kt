package com.safewe

import com.safewe.security.SafetyCode
import org.junit.Assert.*
import org.junit.Test

class SafetyCodeTest {
    @Test fun deterministicFiveGroupsOfFiveDigits() {
        val c = SafetyCode.compute("pa", "ps", "ca", "cs")!!
        assertEquals(c, SafetyCode.compute("pa", "ps", "ca", "cs"))
        assertTrue(c.matches(Regex("(\\d{5} ){4}\\d{5}")))
    }
    @Test fun anySubstitutedKeyChangesTheCode() {
        val base = SafetyCode.compute("pa", "ps", "ca", "cs")
        assertNotEquals(base, SafetyCode.compute("pX", "ps", "ca", "cs"))
        assertNotEquals(base, SafetyCode.compute("pa", "ps", "ca", "cX"))
    }
    @Test fun missingKeyGivesNull() = assertNull(SafetyCode.compute("pa", "", "ca", "cs"))
}
