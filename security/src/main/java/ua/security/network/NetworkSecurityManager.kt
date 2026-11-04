package ua.security.network

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.CipherSuite
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

class NetworkSecurityManager private constructor(
    context: Context,
    stateProvider: TamperDetectionInterceptor.SecurityStateProvider
) {

    private val context: Context = context.applicationContext
    val hardenedHttpClient: OkHttpClient = buildSecureOkHttpClient(stateProvider)

    val client: OkHttpClient get() = hardenedHttpClient

    companion object {
        private const val TAG = "NetworkSecurityManager"
        private const val API_HOST = "api.aniua.org"
        private const val AUTH_HOST = "auth.aniua.org"

        private const val PIN_PRIMARY_API = "sha256/7HIpactkIAq2Y49orFOOQKurWxmmSFZhBCoQYcRhJ3Y="
        private const val PIN_BACKUP_API = "sha256/k2ErWTSpWKuhPzczbxCcWFzOBU9UhesWhMkZZ0gowFE="

        private val DUAL_LAYER_PINS = mapOf(
            API_HOST to listOf(
                "7HIpactkIAq2Y49orFOOQKurWxmmSFZhBCoQYcRhJ3Y=",
                "k2ErWTSpWKuhPzczbxCcWFzOBU9UhesWhMkZZ0gowFE="
            ),
            AUTH_HOST to listOf(
                "7HIpactkIAq2Y49orFOOQKurWxmmSFZhBCoQYcRhJ3Y=",
                "k2ErWTSpWKuhPzczbxCcWFzOBU9UhesWhMkZZ0gowFE="
            )
        )

        @Volatile
        private var sInstance: NetworkSecurityManager? = null

        @JvmStatic
        fun init(
            context: Context,
            stateProvider: TamperDetectionInterceptor.SecurityStateProvider
        ): NetworkSecurityManager {
            return sInstance ?: synchronized(NetworkSecurityManager::class.java) {
                sInstance ?: NetworkSecurityManager(context, stateProvider).also { sInstance = it }
            }
        }

        @JvmStatic
        fun getInstance(): NetworkSecurityManager {
            return sInstance ?: throw IllegalStateException("NetworkSecurityManager must be initialized via init() first")
        }

        @JvmStatic
        fun getInstance(context: Context): NetworkSecurityManager {
            return sInstance ?: synchronized(NetworkSecurityManager::class.java) {
                sInstance ?: NetworkSecurityManager(
                    context,
                    ua.security.hardening.AppSecurityManager.getInstance(context)
                ).also { sInstance = it }
            }
        }
    }

    private fun buildSecureOkHttpClient(stateProvider: TamperDetectionInterceptor.SecurityStateProvider): OkHttpClient {
        val builder = OkHttpClient.Builder()

        val modernTlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .cipherSuites(
                CipherSuite.TLS_AES_128_GCM_SHA256,
                CipherSuite.TLS_AES_256_GCM_SHA384,
                CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
            )
            .build()

        builder.connectionSpecs(listOf(modernTlsSpec))

        val certificatePinner = CertificatePinner.Builder()
            .add(API_HOST, PIN_PRIMARY_API, PIN_BACKUP_API)
            .add(AUTH_HOST, PIN_PRIMARY_API, PIN_BACKUP_API)
            .build()
        builder.certificatePinner(certificatePinner)

        val secureDns = buildDnsOverHttps(builder)
        if (secureDns != null) {
            builder.dns(secureDns)
        }

        builder.addInterceptor(TamperDetectionInterceptor(context, stateProvider))
        builder.addInterceptor(SecurityHeadersInterceptor())
        builder.addNetworkInterceptor(DualLayerPinningInterceptor())

        builder.connectTimeout(20, TimeUnit.SECONDS)
        builder.readTimeout(30, TimeUnit.SECONDS)
        builder.writeTimeout(30, TimeUnit.SECONDS)

        builder.connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))

        val cacheDir = File(context.cacheDir, "secure_http_cache")
        builder.cache(Cache(cacheDir, 25L * 1024 * 1024))

        return builder.build()
    }

    private fun buildDnsOverHttps(bootstrapBuilder: OkHttpClient.Builder): Dns? {
        return try {
            val bootstrapClient = bootstrapBuilder.build()
            DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1"),
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("8.8.4.4")
                )
                .includeIPv6(false)
                .build()
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Не вдалося ініціалізувати DoH, використовуємо системний DNS: ${e.message}")
            Dns.SYSTEM
        }
    }

    private class DualLayerPinningInterceptor : Interceptor {
        @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val handshake = response.handshake
            if (handshake != null) {
                val host = chain.request().url.host.lowercase()
                val expectedPins = DUAL_LAYER_PINS[host]

                if (!expectedPins.isNullOrEmpty()) {
                    val certs = handshake.peerCertificates
                    if (certs.isEmpty()) {
                        throw SSLPeerUnverifiedException("Peer certificate chain is empty for $host")
                    }

                    val leafCert = certs[0]
                    val spkiBytes = leafCert.publicKey.encoded
                    val actualPinSha256 = sha256Base64(spkiBytes)

                    if (!expectedPins.contains(actualPinSha256)) {
                        throw SSLPeerUnverifiedException(
                            "Dual-Layer Pinning Failed for host: $host. Expected one of: $expectedPins, found SPKI: $actualPinSha256"
                        )
                    }
                }
            }
            return response
        }

        private fun sha256Base64(data: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(data)
            return Base64.encodeToString(digest, Base64.NO_WRAP)
        }
    }
}
