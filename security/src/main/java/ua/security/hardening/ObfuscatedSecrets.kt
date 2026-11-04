package ua.security.hardening

import java.nio.charset.StandardCharsets

object ObfuscatedSecrets {

    private const val MASK_KEY: Byte = 0x5A

    private val ENCODED_PACKAGE_NAME = byteArrayOf(
        0x2F, 0x3B, 0x74, 0x3B, 0x34, 0x33, 0x2F, 0x3B, 0x74,
        0x3E, 0x35, 0x2D, 0x34, 0x36, 0x35, 0x3B, 0x3E, 0x3F, 0x28
    )

    private val ENCODED_CERT_SHA256 = byteArrayOf(
        0x9F.toByte(), 0xEA.toByte(), 0x82.toByte(), 0x8D.toByte(), 0x81.toByte(), 0x89.toByte(), 0x82.toByte(), 0xEE.toByte(),
        0x9B.toByte(), 0x9B.toByte(), 0x82.toByte(), 0x8E.toByte(), 0x91.toByte(), 0x98.toByte(), 0x82.toByte(), 0x87.toByte(),
        0x9A.toByte(), 0x98.toByte(), 0x82.toByte(), 0x9B.toByte(), 0x93.toByte(), 0x82.toByte(), 0x9E.toByte(), 0x8E.toByte(),
        0x82.toByte(), 0x9F.toByte(), 0x9C.toByte(), 0x82.toByte(), 0x9D.toByte(), 0x98.toByte(), 0x82.toByte(), 0x99.toByte(),
        0x89.toByte(), 0x82.toByte(), 0x9C.toByte(), 0x9F.toByte(), 0x82.toByte(), 0x91.toByte(), 0x9F.toByte(), 0x82.toByte(),
        0x9A.toByte(), 0x9E.toByte(), 0x82.toByte(), 0x9D.toByte(), 0x8B.toByte(), 0x82.toByte(), 0x9C.toByte(), 0x9F.toByte(),
        0x82.toByte(), 0x8E.toByte(), 0x99.toByte(), 0x82.toByte(), 0x8E.toByte(), 0x9A.toByte(), 0x82.toByte(), 0x8B.toByte(),
        0x98.toByte(), 0x82.toByte(), 0x8B.toByte(), 0x8E.toByte(), 0x82.toByte(), 0x8E.toByte(), 0x8D.toByte(), 0x82.toByte(),
        0x8B.toByte(), 0x9A.toByte(), 0x82.toByte(), 0x99.toByte(), 0x93.toByte(), 0x82.toByte(), 0x98.toByte(), 0x9E.toByte(),
        0x82.toByte(), 0x99.toByte(), 0x8E.toByte(), 0x82.toByte(), 0x9A.toByte(), 0x99.toByte(), 0x82.toByte(), 0x8E.toByte(),
        0x82.toByte(), 0x82.toByte(), 0x99.toByte(), 0x8B.toByte(), 0x82.toByte(), 0x98.toByte(), 0x98.toByte(), 0x82.toByte(),
        0x87.toByte(), 0x9F.toByte(), 0x82.toByte(), 0x98.toByte(), 0x9C.toByte()
    )

    private const val ROLLING_KEY: Byte = 0xCD.toByte()

    fun getExpectedPackageName(): String {
        val result = ByteArray(ENCODED_PACKAGE_NAME.size)
        for (i in ENCODED_PACKAGE_NAME.indices) {
            result[i] = (ENCODED_PACKAGE_NAME[i].toInt() xor MASK_KEY.toInt()).toByte()
        }
        return String(result, StandardCharsets.UTF_8)
    }

    fun getExpectedCertSha256(): String {
        val result = ByteArray(ENCODED_CERT_SHA256.size)
        for (i in ENCODED_CERT_SHA256.indices) {
            result[i] = (ENCODED_CERT_SHA256[i].toInt() xor ROLLING_KEY.toInt()).toByte()
        }
        return String(result, StandardCharsets.UTF_8)
    }
}
