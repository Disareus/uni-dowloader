package ua.security.network

import android.content.Context
import android.content.pm.ApplicationInfo
import okhttp3.Interceptor
import okhttp3.Response
import ua.security.crypto.SecureStorageManager
import java.io.IOException

class TamperDetectionInterceptor(
    context: Context,
    private val securityStateProvider: SecurityStateProvider
) : Interceptor {

    private val context: Context = context.applicationContext

    interface SecurityStateProvider {
        fun isCriticalAttackDetected(): Boolean
        fun isEnvironmentRiskDetected(): Boolean
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        if (securityStateProvider.isCriticalAttackDetected() && !isDebug) {
            try {
                SecureStorageManager.getInstance(context).emergencyWipe()
            } catch (ignored: Exception) {}

            throw IOException("Network request terminated: security violation detected")
        }

        return chain.proceed(chain.request())
    }
}
