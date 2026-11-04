package ua.security.hardening

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class IntegrityDetector(context: Context) {

    private val context: Context = context.applicationContext

    fun detectTamperIndicators(): List<String> {
        val indicators = mutableListOf<String>()

        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            // У режимі розробки підпис і пакет генеруються локально
            return indicators
        }

        if (!verifyPackageName()) {
            indicators.add("PACKAGE_NAME_MISMATCH:current=${context.packageName}")
        }

        if (!verifyApkSignature()) {
            indicators.add("SIGNATURE_MISMATCH:unauthorized_keystore")
        }

        if (!verifyApkFileExistence()) {
            indicators.add("APK_BASE_FILE_MISSING_OR_CORRUPT")
        }

        return indicators
    }

    fun isTampered(): Boolean = detectTamperIndicators().isNotEmpty()

    fun verifyPackageName(): Boolean {
        val currentPackage = context.packageName
        val expectedPackage = ObfuscatedSecrets.getExpectedPackageName()
        return currentPackage == expectedPackage
    }

    fun verifyApkSignature(): Boolean {
        val currentSha256 = getCurrentSignatureSha256() ?: return false
        val expectedSha256 = ObfuscatedSecrets.getExpectedCertSha256()
        return currentSha256.equals(expectedSha256, ignoreCase = true)
    }

    fun getCurrentSignatureSha256(): String? {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }

            if (signatures.isNullOrEmpty()) return null

            val cert = signatures[0].toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(cert)

            val sb = StringBuilder()
            for (i in digest.indices) {
                sb.append(String.format(Locale.US, "%02X", digest[i]))
                if (i < digest.size - 1) sb.append(":")
            }
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun verifyApkFileExistence(): Boolean {
        return try {
            val sourceDir = context.applicationInfo.sourceDir
            val apkFile = File(sourceDir)
            apkFile.exists() && apkFile.length() > 0
        } catch (e: Exception) {
            false
        }
    }
}
