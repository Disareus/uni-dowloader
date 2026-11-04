package ua.security.hardening

import android.content.Context
import android.os.Handler
import android.os.Looper
import ua.security.crypto.SecureStorageManager
import ua.security.network.TamperDetectionInterceptor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AppSecurityManager private constructor(context: Context) : TamperDetectionInterceptor.SecurityStateProvider {

    private val context: Context = context.applicationContext
    private val rootDetector = RootDetector(context)
    private val emulatorDetector = EmulatorDetector(context)
    private val integrityDetector = IntegrityDetector(context)
    private val antiDebugDetector = AntiDebugAndHookDetector(context)

    private val currentThreatLevel = AtomicReference(ThreatLevel.CLEAN)
    private val isEmergencyWipeInProgress = AtomicBoolean(false)

    private var securityListener: SecurityListener? = null
    private var monitorExecutor: ScheduledExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile
        private var sInstance: AppSecurityManager? = null

        @JvmStatic
        fun getInstance(context: Context): AppSecurityManager {
            return sInstance ?: synchronized(AppSecurityManager::class.java) {
                sInstance ?: AppSecurityManager(context).also { sInstance = it }
            }
        }
    }

    fun setSecurityListener(listener: SecurityListener?) {
        this.securityListener = listener
    }

    fun registerListener(listener: SecurityListener?) {
        setSecurityListener(listener)
    }

    fun getCurrentThreatLevel(): ThreatLevel = currentThreatLevel.get()

    fun performAudit(): SecurityAuditResult = performFullAudit()

    fun startPeriodicAudit(intervalSeconds: Int) {
        startPeriodicSecurityMonitor(intervalSeconds)
    }

    override fun isCriticalAttackDetected(): Boolean {
        return currentThreatLevel.get() == ThreatLevel.CRITICAL_ATTACK
    }

    override fun isEnvironmentRiskDetected(): Boolean {
        return currentThreatLevel.get() == ThreatLevel.ENVIRONMENT_RISK
    }

    @Synchronized
    fun performFullAudit(): SecurityAuditResult {
        val allIndicators = mutableListOf<String>()

        val tamperIndicators = integrityDetector.detectTamperIndicators()
        allIndicators.addAll(tamperIndicators)
        val isTampered = tamperIndicators.isNotEmpty()

        val debugIndicators = antiDebugDetector.detectDebugAndHookIndicators()
        allIndicators.addAll(debugIndicators)
        val isDebuggedOrHooked = debugIndicators.isNotEmpty()

        val rootIndicators = rootDetector.detectRootIndicators()
        allIndicators.addAll(rootIndicators)
        val isRooted = rootIndicators.isNotEmpty()

        val emulatorIndicators = emulatorDetector.detectEmulatorIndicators()
        allIndicators.addAll(emulatorIndicators)
        val isEmulator = emulatorIndicators.isNotEmpty()

        val evaluatedLevel = when {
            isTampered || isDebuggedOrHooked -> ThreatLevel.CRITICAL_ATTACK
            isRooted || isEmulator -> ThreatLevel.ENVIRONMENT_RISK
            else -> ThreatLevel.CLEAN
        }

        currentThreatLevel.set(evaluatedLevel)

        val result = SecurityAuditResult(
            isRooted = isRooted,
            isEmulator = isEmulator,
            isTampered = isTampered,
            isDebuggedOrHooked = isDebuggedOrHooked,
            indicators = allIndicators,
            timestamp = System.currentTimeMillis()
        )

        dispatchAuditResult(result)
        return result
    }

    @Synchronized
    fun startPeriodicSecurityMonitor(intervalSeconds: Int) {
        stopPeriodicSecurityMonitor()

        val interval = intervalSeconds.coerceAtLeast(10)
        monitorExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AppSecurity-Watcher").apply { isDaemon = true }
        }

        monitorExecutor?.scheduleWithFixedDelay(
            {
                try {
                    performPeriodicLightweightAudit()
                } catch (ignored: Exception) {}
            },
            interval.toLong(),
            interval.toLong(),
            TimeUnit.SECONDS
        )
    }

    @Synchronized
    fun stopPeriodicSecurityMonitor() {
        monitorExecutor?.shutdownNow()
        monitorExecutor = null
    }

    private fun performPeriodicLightweightAudit() {
        val dynamicTamperIndicators = antiDebugDetector.detectDebugAndHookIndicators()
        if (dynamicTamperIndicators.isNotEmpty()) {
            currentThreatLevel.set(ThreatLevel.CRITICAL_ATTACK)
            notifyListenerOnMainThread(
                ThreatLevel.CRITICAL_ATTACK,
                "DYNAMIC_HOOK_DETECTED",
                dynamicTamperIndicators.joinToString(", ")
            )
            triggerEmergencyWipe()
        }
    }

    private fun dispatchAuditResult(result: SecurityAuditResult) {
        if (result.overallThreatLevel == ThreatLevel.CRITICAL_ATTACK) {
            triggerEmergencyWipe()
            notifyListenerOnMainThread(
                ThreatLevel.CRITICAL_ATTACK,
                "CRITICAL_INTEGRITY_VIOLATION",
                result.indicators.joinToString(", ")
            )
        } else if (result.overallThreatLevel == ThreatLevel.ENVIRONMENT_RISK) {
            notifyListenerOnMainThread(
                ThreatLevel.ENVIRONMENT_RISK,
                "UNTRUSTED_ENVIRONMENT",
                result.indicators.joinToString(", ")
            )
        }
    }

    private fun notifyListenerOnMainThread(level: ThreatLevel, threatType: String, details: String) {
        mainHandler.post {
            securityListener?.onThreatDetected(level, threatType, details)
        }
    }

    private fun triggerEmergencyWipe() {
        if (isEmergencyWipeInProgress.compareAndSet(false, true)) {
            try {
                SecureStorageManager.getInstance(context).emergencyWipe()
                mainHandler.post {
                    securityListener?.onEmergencyWipeRequested()
                }
            } catch (ignored: Exception) {}
        }
    }
}
