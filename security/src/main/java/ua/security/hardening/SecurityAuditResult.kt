package ua.security.hardening

data class SecurityAuditResult(
    val isRooted: Boolean = false,
    val isEmulator: Boolean = false,
    val isTampered: Boolean = false,
    val isDebuggedOrHooked: Boolean = false,
    val indicators: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    val overallThreatLevel: ThreatLevel
        get() = when {
            isTampered || isDebuggedOrHooked -> ThreatLevel.CRITICAL_ATTACK
            isRooted || isEmulator -> ThreatLevel.ENVIRONMENT_RISK
            else -> ThreatLevel.CLEAN
        }
}
