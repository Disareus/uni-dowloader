package ua.aniua.downloader

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.Log
import ua.security.crypto.SecureStorageManager
import ua.security.hardening.AppSecurityManager
import ua.security.hardening.SecurityListener
import ua.security.hardening.ThreatLevel
import ua.security.network.NetworkSecurityManager

class AniUaApp : Application(), SecurityListener {

    companion object {
        private const val TAG = "AniUaApp"
    }

    override fun onCreate() {
        super.onCreate()

        val securityManager = AppSecurityManager.getInstance(this)
        securityManager.registerListener(this)
        securityManager.startPeriodicAudit(30)

        NetworkSecurityManager.init(this, securityManager)
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
