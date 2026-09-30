package io.xenoah.clipdock

import java.net.URI

enum class OutputMode(val label: String, val storedId: Int) {
    VIDEO("動画", 0), ORIGINAL_AUDIO("オリジナル音声", 1), MANUAL_AUDIO("音声 · 手動設定", 2);

    companion object {
        fun fromStored(id: Int?): OutputMode = entries.firstOrNull { it.storedId == id } ?: ORIGINAL_AUDIO
    }
}

enum class AudioFormat(val id: String, val label: String, val bitrates: List<Int>, val defaultBitrate: Int) {
    MP3("mp3", "MP3", listOf(64, 96, 128, 160, 192, 256, 320), 192),
    M4A("m4a", "M4A · AAC", listOf(64, 96, 128, 160, 192, 256), 192),
    OPUS("opus", "Opus", listOf(48, 64, 96, 128, 160, 192, 256), 128);

    companion object {
        fun fromId(id: String?): AudioFormat? = entries.firstOrNull { it.id == id }
    }
}

/** Stable mode IDs preserve old video/audio/MP3 preferences; old MP3 becomes manual 192 kbps. */
data class AudioSelection(val mode: OutputMode, val format: AudioFormat, val bitrate: Int) {
    companion object {
        fun restore(modeId: Int?, formatId: String?, bitrate: Int?): AudioSelection {
            val format = AudioFormat.fromId(formatId) ?: AudioFormat.MP3
            return AudioSelection(OutputMode.fromStored(modeId), format,
                bitrate?.takeIf { it in format.bitrates } ?: format.defaultBitrate)
        }
    }
}

data class DownloadSpec(
    val url: String,
    val mode: OutputMode,
    val height: Int,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val audioBitrate: Int = audioFormat.defaultBitrate,
) {
    fun options(): List<Pair<String, String?>> = buildList {
        add("--no-playlist" to null)
        add("--no-mtime" to null)
        add("--newline" to null)
        add("--no-warnings" to null)
        add("--socket-timeout" to "30")
        add("--retries" to "3")
        add("--fragment-retries" to "3")
        add("--concurrent-fragments" to "2")
        add("--remote-components" to "ejs:github")
        add("--trim-filenames" to "160")
        when (mode) {
            OutputMode.VIDEO -> {
                val cap = if (height > 0) "[height<=?$height]" else ""
                add("-f" to "bv*$cap+ba/b$cap")
                add("--merge-output-format" to "mp4/mkv")
                add("-S" to "vcodec:h264,acodec:aac")
            }
            OutputMode.ORIGINAL_AUDIO -> {
                add("-f" to "ba/b")
                add("--use-postprocessor" to "QuayAudio:when=post_process;format=original")
            }
            OutputMode.MANUAL_AUDIO -> {
                require(audioBitrate in audioFormat.bitrates) { "この音声形式では選択できないビットレートです" }
                add("-f" to "ba/b")
                // ExtractAudio can stream-copy matching codecs and ignore --audio-quality.
                // The bundled processor explicitly re-encodes even same-format inputs.
                add("--use-postprocessor" to "QuayAudio:when=post_process;format=${audioFormat.id};bitrate=$audioBitrate")
            }
        }
    }
}

object InputRules {
    fun urlFromText(text: String): String? {
        val candidate = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
            .find(text)?.value?.trimEnd('。', '、', ')', ']', '）') ?: return null
        return runCatching {
            val uri = URI(candidate)
            require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
            require(!uri.host.isNullOrBlank() && uri.userInfo == null)
            require(candidate.length <= 8192)
            candidate
        }.getOrNull()
    }

    fun expectedSha256(sums: String, filename: String = "yt-dlp"): String {
        return sums.lineSequence().map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == filename && it[0].matches(Regex("[a-fA-F0-9]{64}")) }
            ?.get(0)?.lowercase() ?: error("更新ファイルのSHA-256が見つかりません")
    }
}
