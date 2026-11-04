package ua.security.hardening

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.telephony.TelephonyManager
import java.io.File

class EmulatorDetector(context: Context) {

    private val context: Context = context.applicationContext

    companion object {
        private val KNOWN_EMULATOR_FILES = arrayOf(
            "/dev/qemu_pipe",
            "/dev/socket/qemud",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/nox-prop",
            "/data/data/com.bluestacks.home",
            "/dev/vboxguest",
            "/dev/vboxuser",
            "/system/bin/androVM-prop",
            "/system/bin/microvirt-prop"
        )
    }

    fun detectEmulatorIndicators(): List<String> {
        val indicators = mutableListOf<String>()

        if (checkBuildProperties()) {
            indicators.add("BUILD_PROPERTIES_EMULATOR")
        }

        checkEmulatorFiles()?.let {
            indicators.add("EMULATOR_DEVICE_FILE_FOUND:$it")
        }

        if (checkTelephonyOperator()) {
            indicators.add("TELEPHONY_OPERATOR_ANDROID")
        }

        if (checkPhysicalSensors()) {
            indicators.add("LACK_OF_HARDWARE_SENSORS")
        }

        return indicators
    }

    fun isEmulator(): Boolean = detectEmulatorIndicators().isNotEmpty()

    fun checkBuildProperties(): Boolean {
        val fingerprint = Build.FINGERPRINT?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val hardware = Build.HARDWARE?.lowercase() ?: ""
        val product = Build.PRODUCT?.lowercase() ?: ""
        val board = Build.BOARD?.lowercase() ?: ""

        if (fingerprint.startsWith("generic") || fingerprint.startsWith("unknown")) return true
        if (model.contains("google_sdk") || model.contains("emulator") || model.contains("android sdk built for x86")) return true
        if (hardware.contains("goldfish") || hardware.contains("ranchu") || hardware.contains("vbox86")) return true
        if (product.contains("sdk") || product.contains("google_sdk") || product.contains("sdk_x86") || product.contains("vbox86p")) return true
        if (board.contains("goldfish")) return true
        if (manufacturer.contains("genymotion") || manufacturer.contains("nox") || manufacturer.contains("bluestacks")) return true

        return false
    }

    private fun checkEmulatorFiles(): String? {
        for (path in KNOWN_EMULATOR_FILES) {
            try {
                val f = File(path)
                if (f.exists()) return path
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun checkTelephonyOperator(): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val name = tm?.networkOperatorName?.lowercase() ?: ""
            name == "android"
        } catch (ignored: Exception) {
            false
        }
    }

    private fun checkPhysicalSensors(): Boolean {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            accel == null && gyro == null
        } catch (ignored: Exception) {
            false
        }
    }
}
