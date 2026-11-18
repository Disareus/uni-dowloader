package ua.aniua.downloader

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import android.os.StrictMode
import android.util.Log
import ua.security.crypto.SecureStorageManager
import ua.security.hardening.AppSecurityManager
import ua.security.hardening.SecurityListener
import ua.security.hardening.ThreatLevel
import ua.security.network.NetworkSecurityManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AniUaApp : Application(), SecurityListener {

    companion object {
        private const val TAG = "AniUaApp"
        private const val ORPHAN_TEMP_MAX_AGE_MS = 2L * 60L * 60L * 1000L
        private val TEMP_FILE_PREFIXES = listOf("yt_video_", "yt_audio_", "temp_video_", "temp_audio_", "muxed_")
    }

    private val startedActivities = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()

        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            enableDebugStrictMode()
        }

        val securityManager = AppSecurityManager.getInstance(this)
        securityManager.registerListener(this)
        registerLifecycleAwareSecurityMonitor(securityManager)

        NetworkSecurityManager.init(this, securityManager)
        sweepOrphanedTempFilesAsync()
    }

    private fun enableDebugStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
    }

    private fun registerLifecycleAwareSecurityMonitor(securityManager: AppSecurityManager) {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities.getAndIncrement() == 0) {
                    securityManager.startPeriodicAudit(30)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities.decrementAndGet() <= 0) {
                    startedActivities.set(0)
                    securityManager.stopPeriodicSecurityMonitor()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun sweepOrphanedTempFilesAsync() {
        val dir = cacheDir ?: return
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "AniUa-CacheSweep").apply { isDaemon = true }
        }.execute {
            try {
                val cutoff = System.currentTimeMillis() - ORPHAN_TEMP_MAX_AGE_MS
                dir.listFiles()?.forEach { file ->
                    if (file.isFile &&
                        TEMP_FILE_PREFIXES.any { file.name.startsWith(it) } &&
                        file.lastModified() < cutoff
                    ) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cache sweep skipped: ${e.message}")
            }
        }
    }

    override fun onThreatDetected(level: ThreatLevel, threatType: String, details: String) {
        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (level == ThreatLevel.CRITICAL_ATTACK) {
            if (isDebug) {
                Log.w(TAG, "Security Alert (Debug Mode bypassed): $threatType -> $details")
                return
            }
            onEmergencyWipeRequested()
            Process.killProcess(Process.myPid())
            System.exit(1)
        }
    }

    override fun onEmergencyWipeRequested() {
        try {
            SecureStorageManager.getInstance(this).emergencyWipe()
        } catch (ignored: Exception) {}
    }
}
