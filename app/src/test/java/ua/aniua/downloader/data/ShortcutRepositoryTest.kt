package ua.aniua.downloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutRepositoryTest {

    @Test
    fun testUrlNormalizationAndValidation() {
        assertEquals("https://sflix.to/home", ShortcutRepository.normalizeAndValidateUrl("sflix.to/home"))
        assertEquals("https://uakino.me/", ShortcutRepository.normalizeAndValidateUrl("https://uakino.me/"))
        assertNull(ShortcutRepository.normalizeAndValidateUrl(""))
        assertNull(ShortcutRepository.normalizeAndValidateUrl("not a url"))
    }

    @Test
    fun testSiteNameAndFaviconDerivation() {
        assertEquals("Sflix", ShortcutRepository.deriveSiteName("https://www.sflix.to/tv"))
        assertEquals("Uakino", ShortcutRepository.deriveSiteName("https://uakino.me"))
        assertEquals(
            "https://www.google.com/s2/favicons?sz=128&domain=www.sflix.to",
            ShortcutRepository.buildFaviconUrl("https://www.sflix.to/tv")
        )
    }
}
