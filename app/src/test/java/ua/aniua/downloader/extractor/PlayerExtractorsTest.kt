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

    @Test
    fun testAnitubeRatingExtraction() {
        // 1. Тест вилучення рейтингу з лексингтон-боксу
        val sampleHtmlLexington = """
            <div class="story_c_rate">
                <div class="lexington-box">
                    <div class="div1">
                        <span><span>9.6</span>/<span>10</span>&nbsp;(<span>202</span>)</span>
                    </div>
                </div>
            </div>
        """.trimIndent()
        val doc1 = org.jsoup.Jsoup.parse(sampleHtmlLexington)
        val (rating1, votes1) = AnitubeExtractor.extractRating(doc1)
        assertEquals("★ 9.6/10 (202)", rating1)
        assertEquals("202", votes1)

        // 2. Тест вилучення рейтингу зі starbar_w
        val sampleHtmlStarbar = """
            <div class="starbar">
                <div class="outer">
                    <div class="starbar_w" style="width: 204.60px; display: block"></div>
                </div>
            </div>
        """.trimIndent()
        val doc2 = org.jsoup.Jsoup.parse(sampleHtmlStarbar)
        val (rating2, _) = AnitubeExtractor.extractRating(doc2)
        assertNotNull(rating2)
        assertTrue("Rating should contain 9.4: $rating2", rating2!!.contains("9.4"))

        // 3. Тест вилучення через schema.org мікророзмітку
        val sampleHtmlSchema = """
            <meta itemprop="ratingValue" content="8.7" />
            <meta itemprop="ratingCount" content="55" />
        """.trimIndent()
        val doc3 = org.jsoup.Jsoup.parse(sampleHtmlSchema)
        val (rating3, votes3) = AnitubeExtractor.extractRating(doc3)
        assertEquals("★ 8.7/10 (55)", rating3)
        assertEquals("55", votes3)
    }

    @Test
    fun testYouTubeUrlDetectionAndIdExtraction() {
        val watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertTrue(YouTubeExtractor.isYouTubeUrl(watchUrl))
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId(watchUrl))

        val shortShareUrl = "https://youtu.be/dQw4w9WgXcQ?si=abcdef12345"
        assertTrue(YouTubeExtractor.isYouTubeUrl(shortShareUrl))
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId(shortShareUrl))

        val shortsUrl = "https://youtube.com/shorts/3fMvQ9WgXcQ"
        assertTrue(YouTubeExtractor.isYouTubeUrl(shortsUrl))
        assertEquals("3fMvQ9WgXcQ", YouTubeExtractor.extractVideoId(shortsUrl))

        val embedUrl = "https://www.youtube.com/embed/dQw4w9WgXcQ"
        assertTrue(YouTubeExtractor.isYouTubeUrl(embedUrl))
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId(embedUrl))

        assertFalse(YouTubeExtractor.isYouTubeUrl("https://anitube.in.ua/video/123"))
    }

    @Test
    fun testTikTokUrlDetection() {
        assertTrue(TikTokExtractor.isTikTokUrl("https://www.tiktok.com/@mrbeast/video/7345678901234567890"))
        assertTrue(TikTokExtractor.isTikTokUrl("https://vm.tiktok.com/ZMxxxxxx/"))
        assertTrue(TikTokExtractor.isTikTokUrl("https://vt.tiktok.com/ZSxxxxxx/"))
        assertFalse(TikTokExtractor.isTikTokUrl("https://instagram.com/reel/123"))
    }

    @Test
    fun testInstagramUrlDetectionAndShortcodeExtraction() {
        val reelUrl = "https://www.instagram.com/reel/C89abcdef_X/?igsh=abcdef=="
        assertTrue(InstagramExtractor.isInstagramUrl(reelUrl))
        assertEquals("C89abcdef_X", InstagramExtractor.extractShortcode(reelUrl))

        val postUrl = "https://www.instagram.com/p/DB123456789/"
        assertTrue(InstagramExtractor.isInstagramUrl(postUrl))
        assertEquals("DB123456789", InstagramExtractor.extractShortcode(postUrl))

        val tvUrl = "https://instagr.am/tv/XYZ12345/"
        assertTrue(InstagramExtractor.isInstagramUrl(tvUrl))
        assertEquals("XYZ12345", InstagramExtractor.extractShortcode(tvUrl))

        assertFalse(InstagramExtractor.isInstagramUrl("https://youtube.com/watch?v=123"))
    }

    @Test
    fun testUaKinoUrlDetectionAndPlayerIdentification() {
        assertTrue(UaKinoExtractor.isUaKinoUrl("https://uakino.club/filmy/genre_comedy/123-film.html"))
        assertTrue(UaKinoExtractor.isUaKinoUrl("https://uakino.me/serials/456-serial.html"))
        assertFalse(UaKinoExtractor.isUaKinoUrl("https://anitube.in.ua/6085-anime.html"))

        assertTrue(UaKinoExtractor.isKnownPlayerUrl("https://ashdi.vip/vod/123"))
        assertTrue(UaKinoExtractor.isKnownPlayerUrl("https://tortuga.wtfs.stream/vod/456"))
        assertTrue(UaKinoExtractor.isKnownPlayerUrl("https://moonanime.net/iframe/789"))
        assertTrue(UaKinoExtractor.isKnownPlayerUrl("https://video.sibnet.ru/shell.php?videoid=111"))

        assertEquals("Ashdi", UaKinoExtractor.identifyPlayerName("https://ashdi.vip/vod/123", 1))
        assertEquals("Tortuga", UaKinoExtractor.identifyPlayerName("https://tortuga.wtfs.stream/vod/456", 2))
        assertEquals("Moon", UaKinoExtractor.identifyPlayerName("https://moonanime.net/iframe/789", 3))
        assertEquals("Sibnet", UaKinoExtractor.identifyPlayerName("https://video.sibnet.ru/shell.php?videoid=111", 4))
    }

    @Test
    fun testExtractorUtilsAndQualitySelection() {
        assertEquals("https://anitube.in.ua/123.html", ExtractorUtils.ensureHttpsScheme("anitube.in.ua/123.html"))
        assertEquals("https://cdn.example.com/v.mp4", ExtractorUtils.ensureHttpsScheme("//cdn.example.com/v.mp4"))
        assertTrue(ExtractorUtils.isDirectMediaUrl("https://rr1---sn.googlevideo.com/videoplayback?mime=video%2Fmp4"))
        assertTrue(ExtractorUtils.isDirectMediaUrl("https://cdn.example.com/master.m3u8?token=abc"))
        assertFalse(ExtractorUtils.isDirectMediaUrl("https://anitube.in.ua/6085-anime.html"))

        val qualities = PlayerJsDecoder.parsePlayerJsQualities(
            "[480p]https://cdn.example.com/480.mp4,[1080p]https://cdn.example.com/1080.mp4,[720p]https://cdn.example.com/720.mp4",
            "https://ashdi.vip/vod/1"
        )
        assertEquals(3, qualities.size)
        assertEquals(1080, qualities[0].height)
        assertEquals("https://cdn.example.com/720.mp4", ExtractorUtils.selectBestStream(qualities, "720p").url)
        assertEquals("https://cdn.example.com/480.mp4", ExtractorUtils.selectBestStream(qualities, "480p").url)
    }

    @Test
    fun testGenericSeriesDomEpisodeListExtraction() {
        val html = """
            <html>
            <head>
                <meta property="og:title" content="Watch Solo Leveling Online Free" />
                <meta property="og:image" content="/posters/solo.jpg" />
                <meta property="og:description" content="Arise of Sung Jinwoo." />
            </head>
            <body>
                <div class="episode-list">
                    <a href="/watch/solo-leveling-episode-1" title="Episode 1">Ep 1</a>
                    <a href="/watch/solo-leveling-episode-2" title="Episode 2">Ep 2</a>
                    <a href="/watch/solo-leveling-episode-3" title="Episode 3">Ep 3</a>
                    <a href="/watch/solo-leveling-episode-12" title="Episode 12">Ep 12</a>
                </div>
            </body>
            </html>
        """.trimIndent()

        val info = GenericSeriesExtractor.parseDocumentOffline("https://americansite.com/watch/solo-leveling-episode-1", html)
        assertTrue(info.isSeries)
        assertEquals("Solo Leveling", info.title)
        assertEquals("https://americansite.com/posters/solo.jpg", info.posterUrl)
        assertEquals("americansite", info.siteType)

        val episodes = info.studios.first().players.first().episodes
        // Because Ep 1..3 and Ep 12 were present, expansion fills 1..12 automatically!
        assertEquals(12, episodes.size)
        assertEquals("1", episodes[0].number)
        assertEquals("https://americansite.com/watch/solo-leveling-episode-1", episodes[0].iframeUrl)
        assertEquals("5", episodes[4].number)
        assertEquals("https://americansite.com/watch/solo-leveling-episode-5", episodes[4].iframeUrl)
        assertEquals("12", episodes[11].number)
        assertEquals("https://americansite.com/watch/solo-leveling-episode-12", episodes[11].iframeUrl)

        val selectHtml = """
            <html>
            <head><title>Frieren</title></head>
            <body>
                <select id="episode-select">
                    <option value="/watch/frieren-ep-1">Episode 1</option>
                    <option value="/watch/frieren-ep-2">Episode 2</option>
                    <option value="/watch/frieren-ep-3">Episode 3</option>
                </select>
            </body>
            </html>
        """.trimIndent()
        val selectInfo = GenericSeriesExtractor.parseDocumentOffline("https://enstream.org/frieren", selectHtml)
        assertTrue(selectInfo.isSeries)
        assertEquals(3, selectInfo.studios.first().players.first().episodes.size)
    }

    @Test
    fun testGenericSeriesJsonPlaylistAndNextDataExtraction() {
        val nextDataHtml = """
            <html>
            <head><title>Arcane</title></head>
            <body>
                <script id="__NEXT_DATA__" type="application/json">
                {
                  "props": {
                    "pageProps": {
                      "show": {
                        "numberOfEpisodes": 9,
                        "episodes": [
                          {"season": 1, "number": 1, "title": "Welcome to the Playground", "url": "/tv/94605/1/1"},
                          {"season": 1, "number": 2, "title": "Some Mysteries Are Better Left Unsolved", "url": "/tv/94605/1/2"},
                          {"season": 1, "number": 3, "title": "The Base Violence Necessary for Change", "url": "/tv/94605/1/3"}
                        ]
                      }
                    }
                  }
                }
                </script>
            </body>
            </html>
        """.trimIndent()

        val nextInfo = GenericSeriesExtractor.parseDocumentOffline("https://usflix.tv/tv/94605/1/1", nextDataHtml)
        assertTrue(nextInfo.isSeries)
        val nextEps = nextInfo.studios.first().players.first().episodes
        assertEquals(9, nextEps.size)
        assertEquals("Welcome to the Playground", nextEps[0].title)
        assertEquals("https://usflix.tv/tv/94605/1/9", nextEps[8].iframeUrl)

        val playerJsHtml = """
            <html>
            <head><title>Dune Prophecy</title></head>
            <body>
                <script>
                    var player = new Playerjs({
                        id: "player",
                        file: [{"title":"English Dub","folder":[{"title":"Episode 1","file":"https://cdn.us/ep1.m3u8"},{"title":"Episode 2","file":"https://cdn.us/ep2.m3u8"}]}]
                    });
                </script>
            </body>
            </html>
        """.trimIndent()

        val pjsInfo = GenericSeriesExtractor.parseDocumentOffline("https://usmovies.io/dune-prophecy", playerJsHtml)
        assertTrue(pjsInfo.isSeries)
        assertEquals("English Dub", pjsInfo.studios.first().name)
        val pjsEps = pjsInfo.studios.first().players.first().episodes
        assertEquals(2, pjsEps.size)
        assertEquals("https://cdn.us/ep1.m3u8", pjsEps[0].iframeUrl)
        assertEquals("https://cdn.us/ep2.m3u8", pjsEps[1].iframeUrl)
    }

    @Test
    fun testGenericSeriesUrlTemplateAndEpisodeCountExpansion() {
        val html = """
            <html>
            <head>
                <meta property="og:title" content="Arcane Season 1 Episode 1" />
                <script type="application/ld+json">
                {
                    "@context": "https://schema.org",
                    "@type": "TVSeries",
                    "name": "Arcane",
                    "numberOfEpisodes": 9
                }
                </script>
            </head>
            <body>
                <div>Episodes: 9</div>
            </body>
            </html>
        """.trimIndent()

        val info = GenericSeriesExtractor.parseDocumentOffline("https://americansite.com/watch/arcane-episode-1", html)
        assertTrue(info.isSeries)
        assertEquals("Arcane", info.title)
        val eps = info.studios.first().players.first().episodes
        assertEquals(9, eps.size)
        assertEquals("https://americansite.com/watch/arcane-episode-1", eps[0].iframeUrl)
        assertEquals("https://americansite.com/watch/arcane-episode-9", eps[8].iframeUrl)

        val tvTpl = GenericSeriesExtractor.buildEpisodeUrlTemplate("https://vidsrc.to/embed/tv/tt11126994/1/1")
        assertNotNull(tvTpl)
        assertTrue(tvTpl!!.supportsSeason)
        assertEquals("https://vidsrc.to/embed/tv/tt11126994/2/5", tvTpl.buildUrl(2, 5))
    }

    @Test
    fun testGenericPlayerResolverJsUnpackerAndNestedIframe() {
        val packed = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p;}('0(\"1://2.3/4\\/5.6\");',7,7,'play|https|cdn|stream|hls|master|m3u8'.split('|'),0,{}))"
        val unpacked = GenericPlayerResolver.unpackJs(packed)
        assertTrue("Unpacked JS should contain stream URL, got: $unpacked", unpacked.contains("https://cdn.stream/hls\\/master.m3u8"))

        val normalized = GenericPlayerResolver.normalizeEscapedUrls(unpacked)
        assertTrue("Normalized JS should unescape slashes, got: $normalized", normalized.contains("https://cdn.stream/hls/master.m3u8"))
    }
}

