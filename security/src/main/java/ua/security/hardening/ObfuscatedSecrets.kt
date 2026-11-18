package ua.security.hardening

import java.nio.charset.StandardCharsets

object ObfuscatedSecrets {

    private const val MASK_KEY: Byte = 0x5A
    private const val ROLLING_KEY: Byte = 0xCD.toByte()

    private val SHA256_FINGERPRINT_REGEX = Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$")

    // XOR(0x5A) -> "ua.aniua.downloader"
    private val ENCODED_PACKAGE_NAME = byteArrayOf(
        0x2F, 0x3B, 0x74, 0x3B, 0x34, 0x33, 0x2F, 0x3B, 0x74,
        0x3E, 0x35, 0x2D, 0x34, 0x36, 0x35, 0x3B, 0x3E, 0x3F, 0x28
    )

    // Configure with XOR(0xCD) bytes of your release signing certificate SHA-256
    // (95-char "AA:BB:...:FF" uppercase hex string) before publishing a production Release APK.
    // Empty array disables release cert-pin mismatch kills so self-signed / CI builds do not self-destruct.
    private val ENCODED_CERT_SHA256 = byteArrayOf()

    fun getExpectedPackageName(): String {
        val result = ByteArray(ENCODED_PACKAGE_NAME.size)
        for (i in ENCODED_PACKAGE_NAME.indices) {
            result[i] = (ENCODED_PACKAGE_NAME[i].toInt() xor MASK_KEY.toInt()).toByte()
        }
        return String(result, StandardCharsets.UTF_8)
    }

    fun getExpectedCertSha256(): String {
        if (ENCODED_CERT_SHA256.isEmpty()) return ""
        val result = ByteArray(ENCODED_CERT_SHA256.size)
        for (i in ENCODED_CERT_SHA256.indices) {
            result[i] = (ENCODED_CERT_SHA256[i].toInt() xor ROLLING_KEY.toInt()).toByte()
        }
        return String(result, StandardCharsets.UTF_8)
    }

    fun isCertPinConfigured(): Boolean {
        val decoded = getExpectedCertSha256()
        return SHA256_FINGERPRINT_REGEX.matches(decoded)
    }
}
