package ua.aniua.downloader.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerExtractorsTest {

    @Test
    fun testPeerTubeUrlDetectionAndIdExtraction() {
        val embedUrl = "https://peertube.in.ua/videos/embed/3r79HgbqLJd3id8ez2Df2W"
        assertTrue(PeerTubeExtractor.isPeerTubeUrl(embedUrl))

        val parsed = PeerTubeExtractor.extractVideoIdAndHost(embedUrl)
        assertNotNull(parsed)
        assertEquals("https://peertube.in.ua", parsed?.first)
        assertEquals("3r79HgbqLJd3id8ez2Df2W", parsed?.second)

        val watchUrl = "https://peertube.chatinbit.com/videos/watch/487f5a45-b3e7-4c28-8967-ee654dcd1e96"
        assertTrue(PeerTubeExtractor.isPeerTubeUrl(watchUrl))
        val parsedWatch = PeerTubeExtractor.extractVideoIdAndHost(watchUrl)
        assertNotNull(parsedWatch)
        assertEquals("https://peertube.chatinbit.com", parsedWatch?.first)
        assertEquals("487f5a45-b3e7-4c28-8967-ee654dcd1e96", parsedWatch?.second)

        val shortUrl = "https://peertube.in.ua/w/3r79HgbqLJd3id8ez2Df2W"
        assertTrue(PeerTubeExtractor.isPeerTubeUrl(shortUrl))
        val parsedShort = PeerTubeExtractor.extractVideoIdAndHost(shortUrl)
        assertEquals("3r79HgbqLJd3id8ez2Df2W", parsedShort?.second)

        assertFalse(PeerTubeExtractor.isPeerTubeUrl("https://anitube.in.ua/6085-monolog.html"))
    }

    @Test
    fun testFenixUrlDetection() {
        assertTrue(FenixExtractor.isFenixUrl("https://fenixplay.xyz/vod/eyJ0Ijoidm9kIn0"))
        assertTrue(FenixExtractor.isFenixUrl("https://fenixplay.xyz/embed/monoloh"))
        assertTrue(FenixExtractor.isFenixUrl("https://media.fenixplay.xyz/anime/ep001/master.m3u8"))
        assertFalse(FenixExtractor.isFenixUrl("https://anitube.in.ua/video/123"))
    }

    @Test
    fun testMoonUrlDetection() {
        assertTrue(MoonAnimeExtractor.isMoonUrl("https://moonanime.net/iframe/rsgxbik6y6wv3rs"))
        assertTrue(MoonAnimeExtractor.isMoonUrl("https://moonanime.art/iframe/7spsvvoqpc77i6hkhigyokt"))
        assertTrue(MoonAnimeExtractor.isMoonUrl("https://moonplayer.xyz/v/123"))
        assertTrue(MoonAnimeExtractor.isMoonUrl("https://s3.mooncdn.xyz/video17/test/hls/playlist.m3u8"))
        assertFalse(MoonAnimeExtractor.isMoonUrl("https://ashdi.vip/vod/123"))
    }

    @Test
    fun testMoonAnimeDeriveDirectMasterUrl() {
        val variantContent = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXTINF:10.010000,
            https://s3.mooncdn.online/video17/58978_source_s2_e1_GlassMoon_58978/hls/720/segment_0000.ts
            #EXTINF:10.010000,
            https://s3.mooncdn.online/video17/58978_source_s2_e1_GlassMoon_58978/hls/720/segment_0001.ts
        """.trimIndent()

        val derived = MoonAnimeExtractor.deriveDirectMasterFromContent(variantContent)
        assertNotNull(derived)
        assertEquals("https://s3.mooncdn.xyz/video17/58978_source_s2_e1_GlassMoon_58978/hls/playlist.m3u8", derived)
    }

    @Test
    fun testMoonAnimeInnerXorDecryption() {
        // Key: "testkey123"
        val key = "testkey123"
        val originalUrl = "https://s3.mooncdn.xyz/stream/video.m3u8"
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val origBytes = originalUrl.toByteArray(Charsets.UTF_8)
        val xorBytes = ByteArray(origBytes.size)
        for (i in origBytes.indices) {
            xorBytes[i] = (origBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        val encodedB64 = java.util.Base64.getEncoder().encodeToString(xorBytes)

        val decrypted = MoonAnimeExtractor.decodeInnerXor(encodedB64, key)
        assertEquals(originalUrl, decrypted)
    }

    @Test
    fun testDlePlaylistParserPlayerGrouping() {
        val sampleHtml = """
            <div class="playlists-player">
                <div class="playlists-lists">
                    <div class="playlists-items">
                        <ul>
                            <li data-id="0_0">Озвучення</li>
                        </ul>
                    </div>
                    <div class="playlists-items">
                        <ul>
                            <li data-id="0_0_0">Далі Буде</li>
                        </ul>
                    </div>
                    <div class="playlists-items">
                        <ul>
                            <li data-id="0_0_0_0">ПЛЕЄР FENIX</li>
                            <li data-id="0_0_0_1">ПЛЕЄР MOON</li>
                            <li data-id="0_0_0_2">ПЛЕЄР PEER</li>
                        </ul>
                    </div>
                </div>
                <div class="playlists-videos">
                    <div class="playlists-items">
                        <ul>
                            <li data-file="https://fenixplay.xyz/vod/eyJ0Ijoidm9kIiwicyI6InRlc3QiLCJlIjoxfQ" data-id="0_0_0_0">1 серія</li>
                            <li data-file="https://moonanime.net/iframe/abc123" data-id="0_0_0_1">1 серія</li>
                            <li data-file="https://peertube.in.ua/videos/embed/xyz789" data-id="0_0_0_2">1 серія</li>
                        </ul>
                    </div>
                </div>
            </div>
        """.trimIndent()

        val studios = DlePlaylistParser.parseDleHtml(sampleHtml, "https://anitube.in.ua")
        assertEquals(1, studios.size)
        val studio = studios[0]
        assertEquals("Далі Буде", studio.name)

        val playerIds = studio.players.map { it.id }
        assertTrue("Expected fenix player in $playerIds", playerIds.any { it.contains("fenix") })
        assertTrue("Expected moon player in $playerIds", playerIds.any { it.contains("moon") })
        assertTrue("Expected peertube player in $playerIds", playerIds.any { it.contains("peertube") || it.contains("peer") })
    }
}

