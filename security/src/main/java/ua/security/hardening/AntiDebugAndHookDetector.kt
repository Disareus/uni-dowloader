package ua.security.hardening

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class AntiDebugAndHookDetector(context: Context) {

    private val context: Context = context.applicationContext

    companion object {
        private val FRIDA_DEFAULT_PORTS = intArrayOf(27042, 27043)
        private val FRIDA_MAPS_PATTERNS = arrayOf(
            "frida-agent",
            "frida-gadget",
            "libfrida",
            "gadget.so",
            "gum-js-loop",
            "pool-frida",
            "linjector"
        )
        private val FRIDA_THREAD_NAMES = arrayOf(
            "gmain",
            "gum-js-loop",
            "gdbus",
            "pool-frida"
        )
        private val XPOSED_CLASSES = arrayOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XC_MethodHook",
            "me.weishu.exposed.ExposedBridge",
            "com.saurik.substrate.MS\$MethodPointer"
        )
    }

    fun detectDebugAndHookIndicators(): List<String> {
        val indicators = mutableListOf<String>()
        val isDebug = checkDebuggableFlag()

        if (!isDebug) {
            if (checkDebuggerConnected()) {
                indicators.add("DEBUGGER_IS_CONNECTED")
            }

            if (checkTracerPid()) {
                indicators.add("TRACER_PID_NON_ZERO")
            }
        }

        if (checkFridaPorts()) {
            indicators.add("FRIDA_SERVER_PORT_OPEN")
        }

        val foundMapsPattern = checkMapsForHooking()
        if (foundMapsPattern != null) {
            indicators.add("HOOKING_LIBRARY_IN_MAPS:$foundMapsPattern")
        }

        val foundThread = checkFridaThreads()
        if (foundThread != null) {
            indicators.add("FRIDA_THREAD_DETECTED:$foundThread")
        }

        val foundXposedClass = checkXposedClasses()
        if (foundXposedClass != null) {
            indicators.add("XPOSED_CLASS_FOUND:$foundXposedClass")
        }

        if (checkStackTraceForHooks()) {
            indicators.add("HOOK_IN_STACK_TRACE")
        }

        return indicators
    }

    fun isDebuggedOrHooked(): Boolean = detectDebugAndHookIndicators().isNotEmpty()

    fun checkDebuggableFlag(): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun checkDebuggerConnected(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    fun checkTracerPid(): Boolean {
        val statusFile = File("/proc/self/status")
        if (!statusFile.exists() || !statusFile.canRead()) return false

        try {
            BufferedReader(InputStreamReader(FileInputStream(statusFile), StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith("TracerPid:")) {
                        val pidStr = l.substring("TracerPid:".length).trim()
                        val pid = pidStr.toIntOrNull() ?: 0
                        return pid > 0
                    }
                }
            }
        } catch (ignored: Exception) {}
        return false
    }

    fun checkFridaPorts(): Boolean {
        for (port in FRIDA_DEFAULT_PORTS) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 40)
                    return true
                }
            } catch (ignored: Exception) {}
        }
        return false
    }

    fun checkMapsForHooking(): String? {
        val mapsFile = File("/proc/self/maps")
        if (!mapsFile.exists() || !mapsFile.canRead()) return null

        try {
            BufferedReader(InputStreamReader(FileInputStream(mapsFile), StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.lowercase() ?: continue
                    for (pattern in FRIDA_MAPS_PATTERNS) {
                        if (l.contains(pattern)) return pattern
                    }
                    if (l.contains("xposedbridge.jar") || l.contains("edxposed") || l.contains("lsposed")) {
                        return "xposed_framework"
                    }
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    fun checkFridaThreads(): String? {
        val tasksDir = File("/proc/self/task")
        if (!tasksDir.exists() || !tasksDir.isDirectory) return null

        val taskFolders = tasksDir.listFiles() ?: return null
        for (folder in taskFolders) {
            val commFile = File(folder, "comm")
            if (commFile.exists() && commFile.canRead()) {
                try {
                    BufferedReader(InputStreamReader(FileInputStream(commFile), StandardCharsets.UTF_8)).use { r ->
                        val threadName = r.readLine()?.trim() ?: ""
                        for (known in FRIDA_THREAD_NAMES) {
                            if (threadName.contains(known)) return threadName
                        }
                    }
                } catch (ignored: Exception) {}
            }
        }
        return null
    }

    fun checkXposedClasses(): String? {
        val cl = context.classLoader ?: ClassLoader.getSystemClassLoader()
        for (className in XPOSED_CLASSES) {
            try {
                Class.forName(className, false, cl)
                return className
            } catch (ignored: ClassNotFoundException) {
            } catch (ignored: Exception) {}
        }
        return null
    }

    fun checkStackTraceForHooks(): Boolean {
        try {
            throw Exception("Stack trace probe")
        } catch (e: Exception) {
            for (elem in e.stackTrace) {
                val cls = elem.className.lowercase()
                if (cls.contains("de.robv.android.xposed") || cls.contains("me.weishu.exposed") || cls.contains("org.lsposed")) {
                    return true
                }
            }
        }
        return false
    }
}
