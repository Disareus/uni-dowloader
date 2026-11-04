package ua.security.hardening

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class RootDetector(context: Context) {

    private val context: Context = context.applicationContext

    companion object {
        private val SU_PATHS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk",
            "/system/etc/init.d/99SuperSUDaemon",
            "/vendor/bin/su"
        )

        private val MAGISK_PATHS = arrayOf(
            "/sbin/.magisk",
            "/sbin/.core/mirror",
            "/cache/.disable_magisk",
            "/data/adb/magisk",
            "/data/adb/modules",
            "/data/adb/ksu",
            "/data/adb/ap"
        )

        private val ROOT_PACKAGES = arrayOf(
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "me.weishu.kernelsu"
        )
    }

    fun detectRootIndicators(): List<String> {
        val indicators = mutableListOf<String>()

        if (checkTestKeys()) {
            indicators.add("BUILD_TAGS_TEST_KEYS")
        }

        checkSuBinaryPaths()?.let { indicators.add("SU_BINARY_FOUND:$it") }
        checkMagiskPaths()?.let { indicators.add("MAGISK_OR_KSU_PATH_FOUND:$it") }
        checkRootPackages()?.let { indicators.add("ROOT_MANAGER_APP_FOUND:$it") }

        if (checkMountinfoForMagisk()) {
            indicators.add("MAGISK_MOUNTINFO_DETECTED")
        }

        if (checkRwMounts()) {
            indicators.add("RW_SYSTEM_MOUNTS_DETECTED")
        }

        return indicators
    }

    fun isRooted(): Boolean = detectRootIndicators().isNotEmpty()

    private fun checkTestKeys(): Boolean {
        val tags = Build.TAGS
        return tags != null && tags.contains("test-keys")
    }

    private fun checkSuBinaryPaths(): String? {
        for (path in SU_PATHS) {
            try {
                val f = File(path)
                if (f.exists()) return path
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun checkMagiskPaths(): String? {
        for (path in MAGISK_PATHS) {
            try {
                val f = File(path)
                if (f.exists()) return path
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun checkRootPackages(): String? {
        val pm = context.packageManager
        for (pkg in ROOT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (ignored: PackageManager.NameNotFoundException) {
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun checkMountinfoForMagisk(): Boolean {
        val mountinfo = File("/proc/self/mountinfo")
        if (!mountinfo.exists() || !mountinfo.canRead()) return false

        try {
            BufferedReader(InputStreamReader(FileInputStream(mountinfo), StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.lowercase() ?: continue
                    if (l.contains("magisk") || l.contains("core/mirror") || l.contains("core/img")) {
                        return true
                    }
                }
            }
        } catch (ignored: Exception) {}
        return false
    }

    private fun checkRwMounts(): Boolean {
        val mounts = File("/proc/mounts")
        if (!mounts.exists() || !mounts.canRead()) return false

        try {
            BufferedReader(InputStreamReader(FileInputStream(mounts), StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    val parts = l.split(" ")
                    if (parts.size >= 4) {
                        val mountPoint = parts[1]
                        val options = parts[3].split(",")
                        if ((mountPoint == "/system" || mountPoint == "/" || mountPoint == "/system_root") &&
                            options.contains("rw")
                        ) {
                            return true
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return false
    }
}
