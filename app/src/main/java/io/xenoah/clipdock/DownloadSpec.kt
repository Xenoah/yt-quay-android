package io.xenoah.clipdock

import java.net.URI

enum class OutputMode(val label: String) {
    VIDEO("動画"), AUDIO("音声のみ"), MP3("MP3 · 192 kbps")
}

data class DownloadSpec(val url: String, val mode: OutputMode, val height: Int) {
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
            OutputMode.AUDIO -> {
                add("-f" to "ba/b")
                add("-x" to null)
                add("--audio-format" to "best")
            }
            OutputMode.MP3 -> {
                add("-f" to "ba/b")
                add("-x" to null)
                add("--audio-format" to "mp3")
                add("--audio-quality" to "192K")
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
