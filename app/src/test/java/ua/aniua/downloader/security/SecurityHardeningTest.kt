package ua.aniua.downloader.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.aniua.downloader.extractor.ExtractorUtils
import ua.aniua.downloader.extractor.MoonAnimeExtractor
import ua.aniua.downloader.extractor.UaKinoExtractor
import ua.security.hardening.ObfuscatedSecrets
import ua.security.storage.DownloadSecurityManager

class SecurityHardeningTest {

    @Test
    fun testObfuscatedSecretsPackageAndCertPinState() {
        assertEquals("ua.aniua.downloader", ObfuscatedSecrets.getExpectedPackageName())
        assertFalse(
            "Unconfigured cert pin should not be active or decode to corrupt bytes",
            ObfuscatedSecrets.isCertPinConfigured()
        )
    }

    @Test
    fun testSameSiteCookieIsolationPreventsCrossDomainLeakage() {
        assertTrue(
            ExtractorUtils.isSameSiteForCookies(
                "https://cdn.anitube.in.ua/video/ep1.m3u8",
                "https://anitube.in.ua/6085-anime.html"
            )
        )
        assertFalse(
            ExtractorUtils.isSameSiteForCookies(
                "https://evil-cdn.example.net/stream.m3u8",
                "https://anitube.in.ua/6085-anime.html"
            )
        )
        assertFalse(ExtractorUtils.isSameSiteForCookies("https://cdn.example.com/a.mp4", null))
    }

    @Test
    fun testHostBasedUrlMatchingAvoidsPathHijacking() {
        assertTrue(MoonAnimeExtractor.isMoonUrl("https://moonanime.net/iframe/abc"))
        assertTrue(MoonAnimeExtractor.isMoonUrl("https://moonplayer.xyz/v/123"))
        assertFalse(MoonAnimeExtractor.isMoonUrl("https://example.com/movies/moon-knight"))

        assertTrue(UaKinoExtractor.isUaKinoUrl("https://uakino.me/serials/123.html"))
        assertFalse(UaKinoExtractor.isUaKinoUrl("https://google.com/search?q=uakino"))
    }

    @Test
    fun testFilenameSanitizationBlocksPathTraversal() {
        val sanitized = DownloadSecurityManager.sanitizeFilename("../../etc/passwd:720p*")
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
        assertFalse(sanitized.startsWith("."))
    }
}
