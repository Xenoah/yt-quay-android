package io.xenoah.clipdock

import org.junit.Assert.*
import org.junit.Test

class AudioSelectionTest {
    @Test fun freshInstallDefaultsToOriginalAudio() {
        assertEquals(AudioSelection(OutputMode.ORIGINAL_AUDIO, AudioFormat.MP3, 192),
            AudioSelection.restore(null, null, null))
    }

    @Test fun legacyVideoAndOriginalAudioPreferencesRemainUnchanged() {
        assertEquals(OutputMode.VIDEO, AudioSelection.restore(0, null, null).mode)
        assertEquals(OutputMode.ORIGINAL_AUDIO, AudioSelection.restore(1, null, null).mode)
    }

    @Test fun legacyMp3MigratesToManualMp3At192Kbps() {
        assertEquals(AudioSelection(OutputMode.MANUAL_AUDIO, AudioFormat.MP3, 192),
            AudioSelection.restore(2, null, null))
    }

    @Test fun stableModeIdsDoNotDependOnEnumNames() {
        assertEquals(0, OutputMode.VIDEO.storedId)
        assertEquals(1, OutputMode.ORIGINAL_AUDIO.storedId)
        assertEquals(2, OutputMode.MANUAL_AUDIO.storedId)
    }

    @Test fun savedManualSelectionsRoundTripForEverySupportedRate() {
        AudioFormat.entries.forEach { format ->
            format.bitrates.forEach { rate ->
                assertEquals(AudioSelection(OutputMode.MANUAL_AUDIO, format, rate),
                    AudioSelection.restore(2, format.id, rate))
            }
        }
    }

    @Test fun audioChoicesAreRetainedWhenVideoOrOriginalIsSelected() {
        listOf(OutputMode.VIDEO, OutputMode.ORIGINAL_AUDIO).forEach { mode ->
            assertEquals(AudioSelection(mode, AudioFormat.OPUS, 64), AudioSelection.restore(mode.storedId, "opus", 64))
        }
    }

    @Test fun invalidModeFallsBackToOriginalWithoutLosingValidAudioChoices() {
        listOf(-1, 3, Int.MAX_VALUE).forEach {
            assertEquals(AudioSelection(OutputMode.ORIGINAL_AUDIO, AudioFormat.M4A, 128), AudioSelection.restore(it, "m4a", 128))
        }
    }

    @Test fun unknownFormatAndInvalidRateUseSafeDefaults() {
        assertEquals(AudioSelection(OutputMode.MANUAL_AUDIO, AudioFormat.MP3, 192),
            AudioSelection.restore(2, "--exec bad", -1))
        assertNull(AudioFormat.fromId("mp3;bitrate=999"))
    }

    @Test fun invalidOrMissingBitrateUsesFormatSpecificDefault() {
        AudioFormat.entries.forEach { format ->
            listOf(null, -1, 0, 1, 11, 999, Int.MAX_VALUE).forEach { rate ->
                assertEquals(format.defaultBitrate, AudioSelection.restore(2, format.id, rate).bitrate)
            }
        }
        assertEquals(128, AudioSelection.restore(2, "opus", null).bitrate)
    }

    @Test fun originalAudioDoesNotRequestLossyExtractionOrBitrate() {
        val options = DownloadSpec("", OutputMode.ORIGINAL_AUDIO, 720).options().toMap()
        assertEquals("ba/b", options["-f"])
        assertEquals("QuayAudio:when=post_process;format=original", options["--use-postprocessor"])
        listOf("-x", "--audio-format", "--audio-quality", "--merge-output-format", "-S").forEach {
            assertFalse(options.containsKey(it))
        }
    }

    @Test fun everyManualFormatAndBitrateUsesExplicitProcessor() {
        AudioFormat.entries.forEach { format ->
            format.bitrates.forEach { rate ->
                val options = DownloadSpec("", OutputMode.MANUAL_AUDIO, 1080, format, rate).options().toMap()
                assertEquals("ba/b", options["-f"])
                assertEquals("QuayAudio:when=post_process;format=${format.id};bitrate=$rate", options["--use-postprocessor"])
                assertFalse(options.containsKey("-x"))
                assertFalse(options.containsKey("--audio-quality"))
            }
        }
    }

    @Test fun unsupportedManualRatesAreRejectedBeforeExecution() {
        AudioFormat.entries.forEach { format ->
            listOf(-1, 0, 8, 63, 65, 100, 512, Int.MAX_VALUE).forEach { rate ->
                assertThrows(IllegalArgumentException::class.java) {
                    DownloadSpec("", OutputMode.MANUAL_AUDIO, 1080, format, rate).options()
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadSpec("", OutputMode.MANUAL_AUDIO, 1080, AudioFormat.M4A, 320).options()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadSpec("", OutputMode.MANUAL_AUDIO, 1080, AudioFormat.OPUS, 320).options()
        }
    }

    @Test fun omittedManualBitrateUsesSelectedFormatsDefault() {
        assertEquals("QuayAudio:when=post_process;format=opus;bitrate=128",
            DownloadSpec("", OutputMode.MANUAL_AUDIO, 1080, AudioFormat.OPUS).options().toMap()["--use-postprocessor"])
    }

    @Test fun videoModeDoesNotLoadAudioProcessorOrChangeVideoSelection() {
        val options = DownloadSpec("", OutputMode.VIDEO, 1080, AudioFormat.OPUS, 128).options().toMap()
        assertEquals("bv*[height<=?1080]+ba/b[height<=?1080]", options["-f"])
        assertEquals("mp4/mkv", options["--merge-output-format"])
        assertFalse(options.containsKey("--use-postprocessor"))
    }

    @Test fun allModesKeepSharedSafetyAndSingleItemOptions() {
        OutputMode.entries.forEach { mode ->
            val options = DownloadSpec("", mode, 1080).options().toMap()
            assertTrue(options.containsKey("--no-playlist"))
            assertEquals("30", options["--socket-timeout"])
            assertEquals("3", options["--retries"])
            assertEquals("ejs:github", options["--remote-components"])
        }
    }
}
