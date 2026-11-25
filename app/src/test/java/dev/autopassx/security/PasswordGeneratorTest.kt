package dev.autopassx.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {
    @Test
    fun generatesRequestedLength() {
        assertEquals(16, PasswordGenerator.generate(16).length)
        assertEquals(24, PasswordGenerator.generate(24).length)
        assertEquals(40, PasswordGenerator.generate(40).length)
    }

    @Test
    fun outputContainsOnlySupportedCharacters() {
        val allowed = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*()-_=+"
        repeat(100) {
            assertTrue(PasswordGenerator.generate(32).all { it in allowed })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLengthBelowMinimum() {
        PasswordGenerator.generate(15)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLengthAboveMaximum() {
        PasswordGenerator.generate(65)
    }
}
