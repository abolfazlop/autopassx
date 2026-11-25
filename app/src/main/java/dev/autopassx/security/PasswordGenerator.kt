package dev.autopassx.security

import java.security.SecureRandom

object PasswordGenerator {
    private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*()-_=+"
    fun generate(length: Int = 24): String {
        require(length in 16..64)
        val random = SecureRandom()
        return buildString(length) { repeat(length) { append(CHARS[random.nextInt(CHARS.length)]) } }
    }
}
