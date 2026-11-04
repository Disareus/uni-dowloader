package ua.security.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCryptoManager private constructor(private val keyAlias: String) {

    private val secureRandom = SecureRandom()

    init {
        try {
            ensureKeyExists()
        } catch (e: Exception) {
            Log.e(TAG, "Помилка ініціалізації Keystore: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "KeystoreCryptoManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_MASTER_KEY_ALIAS = "AniUA_Hardware_MasterKey_v1"

        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        @Volatile
        private var sInstance: KeystoreCryptoManager? = null

        @JvmStatic
        fun getInstance(): KeystoreCryptoManager {
            return sInstance ?: synchronized(KeystoreCryptoManager::class.java) {
                sInstance ?: KeystoreCryptoManager(DEFAULT_MASTER_KEY_ALIAS).also { sInstance = it }
            }
        }

        @JvmStatic
        fun getInstance(keyAlias: String): KeystoreCryptoManager = KeystoreCryptoManager(keyAlias)

        @JvmStatic
        fun zeroByteArray(array: ByteArray?) {
            if (array != null) {
                Arrays.fill(array, 0.toByte())
            }
        }

        @JvmStatic
        fun zeroCharArray(array: CharArray?) {
            if (array != null) {
                Arrays.fill(array, '\u0000')
            }
        }
    }

    @Synchronized
    private fun ensureKeyExists() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (!keyStore.containsAlias(keyAlias)) {
            generateMasterKey()
        }
    }

    private fun generateMasterKey() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                createKeyInternal(useStrongBox = true)
                Log.d(TAG, "Майстер-ключ згенеровано в StrongBox")
                return
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox недоступний, fallback на TEE: ${e.message}")
            }
        }
        createKeyInternal(useStrongBox = false)
        Log.d(TAG, "Майстер-ключ згенеровано у TEE")
    }

    private fun createKeyInternal(useStrongBox: Boolean) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && useStrongBox) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        var entry = keyStore.getEntry(keyAlias, null)
        if (entry !is KeyStore.SecretKeyEntry) {
            generateMasterKey()
            keyStore.load(null)
            entry = keyStore.getEntry(keyAlias, null)
        }
        return (entry as KeyStore.SecretKeyEntry).secretKey
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun encrypt(plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val secretKey = getSecretKey()
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }

        val cipherTextWithTag = cipher.doFinal(plaintext)
        val byteBuffer = ByteBuffer.allocate(iv.size + cipherTextWithTag.size)
        byteBuffer.put(iv)
        byteBuffer.put(cipherTextWithTag)
        return byteBuffer.array()
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun decrypt(encryptedBundle: ByteArray, aad: ByteArray?): ByteArray {
        if (encryptedBundle.size < GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
            throw GeneralSecurityException("Пошкоджений буфер зашифрованих даних")
        }

        val byteBuffer = ByteBuffer.wrap(encryptedBundle)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        byteBuffer.get(iv)

        val cipherTextWithTag = ByteArray(byteBuffer.remaining())
        byteBuffer.get(cipherTextWithTag)

        val secretKey = getSecretKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }

        return cipher.doFinal(cipherTextWithTag)
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun encryptString(plaintext: String, aad: String?): String {
        val plainBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        val aadBytes = aad?.toByteArray(StandardCharsets.UTF_8)
        return try {
            val encrypted = encrypt(plainBytes, aadBytes)
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } finally {
            zeroByteArray(plainBytes)
        }
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun decryptString(base64Encrypted: String, aad: String?): String {
        val encryptedBundle = Base64.decode(base64Encrypted, Base64.NO_WRAP)
        val aadBytes = aad?.toByteArray(StandardCharsets.UTF_8)
        val decrypted = decrypt(encryptedBundle, aadBytes)
        return try {
            String(decrypted, StandardCharsets.UTF_8)
        } finally {
            zeroByteArray(decrypted)
        }
    }

    @Synchronized
    fun emergencyWipe() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
                Log.w(TAG, "Майстер-ключ Keystore [$keyAlias] знищено")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Помилка при екстреному знищенні ключа: ${e.message}")
        }
    }
}
