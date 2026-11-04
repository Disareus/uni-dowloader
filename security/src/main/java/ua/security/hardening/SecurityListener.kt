package ua.security.hardening

interface SecurityListener {
    fun onThreatDetected(level: ThreatLevel, threatType: String, details: String)
    fun onEmergencyWipeRequested()
}
