package io.xenoah.clipdock

import org.junit.Assert.*
import org.junit.Test

class DownloadSpecTest {
    @Test fun extractsUrlFromAndroidShareText() {
        assertEquals("https://example.org/watch?v=abc&list=xyz", InputRules.urlFromText("動画名\nhttps://example.org/watch?v=abc&list=xyz"))
    }
    @Test fun rejectsLocalFilesAndOptionInjection() {
        listOf("file:///data/data/private", "--exec rm -rf /", "javascript:alert(1)", "https:///bad", "https://user:secret@example.com/video").forEach {
            assertNull(it, InputRules.urlFromText(it))
        }
    }
    @Test fun acceptsUnicodeTitlesAroundUrl() {
        assertEquals("https://example.org/video", InputRules.urlFromText("『動画』はこちら（https://example.org/video）。"))
    }
    @Test fun enforcesResolutionOnBothVideoAndFallback() {
        val options = DownloadSpec("https://example.org", OutputMode.VIDEO, 720).options().toMap()
        assertEquals("bv*[height<=?720]+ba/b[height<=?720]", options["-f"])
        assertTrue(options.containsKey("--no-playlist"))
    }
    @Test fun bestQualityDoesNotCapHeight() {
        assertEquals("bv*+ba/b", DownloadSpec("", OutputMode.VIDEO, 0).options().toMap()["-f"])
    }
    @Test fun mp3UsesExplicitConversionAndBitrate() {
        val options = DownloadSpec("", OutputMode.MANUAL_AUDIO, 1080).options().toMap()
        assertEquals("QuayAudio:when=post_process;format=mp3;bitrate=192", options["--use-postprocessor"])
        assertFalse(options.containsKey("--audio-quality"))
        assertFalse(options.containsKey("-x"))
    }
    @Test fun readsOnlyExactBinaryChecksum() {
        val expected = "a".repeat(64)
        val manifest = "${"b".repeat(64)}  yt-dlp.exe\n$expected  yt-dlp\n${"c".repeat(64)}  yt-dlp_linux"
        assertEquals(expected, InputRules.expectedSha256(manifest))
        assertEquals(expected, InputRules.expectedSha256("$expected *yt-dlp"))
    }
    @Test(expected = IllegalStateException::class) fun refusesMalformedChecksum() {
        InputRules.expectedSha256("deadbeef  yt-dlp")
    }
    @Test(expected = IllegalStateException::class) fun refusesWrongAssetChecksum() {
        InputRules.expectedSha256("${"a".repeat(64)}  yt-dlp.exe")
    }
}
