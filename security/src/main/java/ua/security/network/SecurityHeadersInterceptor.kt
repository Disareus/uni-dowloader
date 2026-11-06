package ua.security.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class SecurityHeadersInterceptor : Interceptor {

    companion object {
        const val SAFE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val TRUSTED_API_HOSTS = setOf(
            "api.aniua.org",
            "auth.aniua.org",
            "sync.aniua.org"
        )

        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val HEADER_AUTH_TOKEN = "X-Auth-Token"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val HEADER_REFERER = "Referer"
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        val host = originalRequest.url.host.lowercase()

        // 1. Примусове встановлення безпечного User-Agent (Chrome Desktop/Mobile)
        if (originalRequest.header(HEADER_USER_AGENT) == null) {
            requestBuilder.header(HEADER_USER_AGENT, SAFE_USER_AGENT)
        }

        // 2. Автоматична підстановка правильного Referer
        if (originalRequest.header(HEADER_REFERER) == null) {
            resolveRefererForHost(host)?.let {
                requestBuilder.header(HEADER_REFERER, it)
            }
        }

        // 3. Запобігання витоку токенів
        if (!isTrustedApiHost(host)) {
            requestBuilder.removeHeader(HEADER_AUTHORIZATION)
            requestBuilder.removeHeader(HEADER_AUTH_TOKEN)
        }

        return chain.proceed(requestBuilder.build())
    }

    private fun isTrustedApiHost(host: String): Boolean {
        for (trustedHost in TRUSTED_API_HOSTS) {
            if (host == trustedHost || host.endsWith(".$trustedHost")) {
                return true
            }
        }
        return false
    }

    private fun resolveRefererForHost(host: String): String? {
        return when {
            host.contains("ashdi") -> "https://ashdi.vip/"
            host.contains("tortuga") -> "https://tortuga.wtf/"
            host.contains("uakino") -> "https://uakino.me/"
            host.contains("anitube") -> "https://anitube.in.ua/"
            host.contains("moonanime") -> "https://moonanime.art/"
            host.contains("moon") -> "https://anitube.in.ua/"
            host.contains("fenix") -> "https://fenixplay.xyz/"
            host.contains("peertube") -> "https://$host/"
            else -> null
        }
    }
}
