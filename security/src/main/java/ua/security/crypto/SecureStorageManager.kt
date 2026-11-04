package ua.security.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SecureStorageManager private constructor(context: Context) {

    private val context: Context = context.applicationContext
    private val keystoreCrypto: KeystoreCryptoManager = KeystoreCryptoManager.getInstance()
    private val volatileMemoryCache = ConcurrentHashMap<String, ByteArray>()
    private var encryptedPrefs: SharedPreferences? = null

    init {
        initEncryptedPreferences()
    }

    companion object {
        private const val TAG = "SecureStorageManager"
        private const val PREF_FILE_NAME = "aniua_secure_prefs"

        @Volatile
        private var sInstance: SecureStorageManager? = null

        @JvmStatic
        fun getInstance(context: Context): SecureStorageManager {
            return sInstance ?: synchronized(SecureStorageManager::class.java) {
                sInstance ?: SecureStorageManager(context).also { sInstance = it }
            }
        }
    }

    @Synchronized
    private fun initEncryptedPreferences() {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()

            encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                PREF_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Помилка ініціалізації EncryptedSharedPreferences: ${e.message}")
            fallbackRecoverPrefs()
        }
    }

    private fun fallbackRecoverPrefs() {
        try {
            val prefFile = File(context.applicationInfo.dataDir + "/shared_prefs/" + PREF_FILE_NAME + ".xml")
            if (prefFile.exists()) {
                val deleted = prefFile.delete()
                Log.w(TAG, "Пошкоджені secure prefs видалено для захисту: $deleted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не вдалося скинути налаштування: ${e.message}")
        }
    }

    fun putSecureString(key: String, value: String?) {
        if (value == null) {
            remove(key)
            return
        }
        encryptedPrefs?.edit()?.putString(key, value)?.apply()
    }

    fun getSecureString(key: String, defaultValue: String? = null): String? {
        return encryptedPrefs?.getString(key, defaultValue) ?: defaultValue
    }

    fun remove(key: String) {
        encryptedPrefs?.edit()?.remove(key)?.apply()
        volatileMemoryCache.remove(key)?.let { KeystoreCryptoManager.zeroByteArray(it) }
    }

    @Synchronized
    fun emergencyWipe() {
        Log.w(TAG, "EMERGENCY WIPE triggered! Ліквідація чутливих даних...")
        try {
            encryptedPrefs?.edit()?.clear()?.commit()
        } catch (ignored: Exception) {}

        for (bytes in volatileMemoryCache.values) {
            KeystoreCryptoManager.zeroByteArray(bytes)
        }
        volatileMemoryCache.clear()

        keystoreCrypto.emergencyWipe()
        fallbackRecoverPrefs()
    }
}
