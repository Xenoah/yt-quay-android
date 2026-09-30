package io.xenoah.clipdock

import android.content.Context
import android.util.AtomicFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** All operations are serialized by DownloadService. No shell parses user input. */
object Engine {
    private var initialized = false
    private val prefs get() = AppState.prefs
    private fun binary(ctx: Context) = File(ctx.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")
    private fun previous(ctx: Context) = File(ctx.noBackupFilesDir, "engine.previous")

    /** Only our APK-bundled processor is placed in this private plugin directory. */
    fun audioPluginDirectory(ctx: Context): File {
        val root = File(ctx.noBackupFilesDir, "quay-audio-plugins")
        val module = File(root, "quay/yt_dlp_plugins/postprocessor/quay_audio.py")
        module.parentFile?.mkdirs()
        val atomic = AtomicFile(module)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            ctx.resources.openRawResource(R.raw.quay_audio).use { it.copyTo(stream) }
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        return root
    }

    fun init(ctx: Context) {
        if (initialized) return
        val target = binary(ctx)
        if (target.exists() || File(target.path + ".bak").exists()) {
            AtomicFile(target).openRead().close()
        }
        if (prefs.getBoolean("updatePending", false)) {
            if (previous(ctx).isFile) replace(previous(ctx), target)
            prefs.edit().putBoolean("updatePending", false)
                .putString("engineVersion", prefs.getString("previousVersion", "同梱版")).commit()
        }
        YoutubeDL.init(ctx)
        FFmpeg.init(ctx)
        val actual = healthCheck()
        initialized = true
        prefs.edit().putString("engineVersion", actual).apply()
        AppState.notifyChange()
    }

    private fun healthCheck(): String {
        val executor = Executors.newSingleThreadExecutor()
        val id = "health-${System.nanoTime()}"
        try {
            return executor.submit<String> {
                val request = YoutubeDLRequest(emptyList()).addOption("--version")
                val response = YoutubeDL.execute(request, id, null)
                val version = response.out.trim()
                check(response.exitCode == 0 && Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}[^\\r\\n]*").matches(version)) {
                    "yt-dlpの起動確認に失敗しました"
                }
                version
            }.get(30, TimeUnit.SECONDS)
        } finally {
            YoutubeDL.destroyProcessById(id)
            executor.shutdownNow()
        }
    }

    fun update(ctx: Context, nightly: Boolean, progress: (String) -> Unit): String {
        init(ctx)
        val repo = if (nightly) "yt-dlp-nightly-builds" else "yt-dlp"
        progress("公式リリースを確認中…")
        val json = JSONObject(fetchText("https://api.github.com/repos/yt-dlp/$repo/releases/latest"))
        val tag = json.getString("tag_name")
        val channel = if (nightly) "nightly" else "stable"
        if (tag == prefs.getString("releaseTag", "") && channel == prefs.getString("installedChannel", "")) {
            prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
            return "最新版です · ${AppState.engineVersion}"
        }
        val assets = json.getJSONArray("assets")
        fun asset(name: String): String {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == name) {
                    val url = a.getString("browser_download_url")
                    require(url.startsWith("https://github.com/yt-dlp/$repo/releases/download/")) { "更新先URLを確認できません" }
                    return url
                }
            }
            error("公式リリースに $name がありません")
        }
        progress("SHA-256を取得中…")
        val expected = InputRules.expectedSha256(fetchText(asset("SHA2-256SUMS")))
        val stage = File(ctx.noBackupFilesDir, "engine.download")
        try {
            progress("yt-dlp $tag をダウンロード中…")
            download(asset("yt-dlp"), stage, 64L * 1024 * 1024)
            val hash = MessageDigest.getInstance("SHA-256")
            stage.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            val actual = hash.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected) { "SHA-256が一致しません。更新を中止しました" }
            ZipFile(stage).use { check(it.getEntry("yt_dlp/version.py") != null && it.getEntry("__main__.py") != null) { "更新ファイルの形式が不正です" } }
            progress("整合性OK · 起動確認中…")
            val version = install(ctx, stage)
            prefs.edit().putString("releaseTag", tag).putString("installedChannel", channel)
                .putLong("lastUpdateCheck", System.currentTimeMillis()).commit()
            return "$version に更新しました"
        } finally { stage.delete() }
    }

    private fun install(ctx: Context, stage: File): String {
        val target = binary(ctx)
        replace(target, previous(ctx))
        prefs.edit().putString("previousVersion", AppState.engineVersion).putBoolean("updatePending", true).commit()
        try {
            replace(stage, target)
            val version = healthCheck()
            prefs.edit().putString("engineVersion", version).putBoolean("updatePending", false).commit()
            return version
        } catch (e: Exception) {
            replace(previous(ctx), target)
            prefs.edit().putBoolean("updatePending", false).commit()
            throw IllegalStateException("更新版が起動しないため、前の版へ戻しました: ${e.cause?.message ?: e.message}", e)
        }
    }

    fun rollback(ctx: Context): String {
        init(ctx)
        check(previous(ctx).isFile) { "戻せる版がありません。先に一度更新してください" }
        val stage = File(ctx.noBackupFilesDir, "engine.rollback")
        try {
            previous(ctx).copyTo(stage, overwrite = true)
            val version = install(ctx, stage)
            prefs.edit().remove("releaseTag").putBoolean("autoUpdate", false).commit()
            return "$version へ戻しました。自動更新をOFFにしました"
        } finally { stage.delete() }
    }

    private fun replace(source: File, target: File) {
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            source.inputStream().use { it.copyTo(stream) }
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    private fun connection(url: String): HttpURLConnection {
        var address = URL(url)
        repeat(6) {
            require(address.protocol == "https" && address.host in setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")) { "更新サーバーの接続先が不正です" }
            val c = address.openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "YTQuay/${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Accept", "application/octet-stream, application/json")
            val code = c.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = c.getHeaderField("Location") ?: error("更新サーバーの応答が不正です")
                address = URL(address, location)
                c.disconnect()
            } else {
                if (code != 200) { c.disconnect(); error("更新サーバー HTTP $code。時間を置いて再試行してください") }
                return c
            }
        }
        error("更新サーバーのリダイレクトが多すぎます")
    }

    private fun fetchText(url: String): String {
        val c = connection(url)
        try {
            return c.inputStream.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 3 * 1024 * 1024) { "更新情報が大きすぎます" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }

    private fun download(url: String, target: File, max: Long) {
        val c = connection(url)
        val started = System.nanoTime()
        try {
            c.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        check(size <= max) { "更新ファイルが大きすぎます" }
                        check(System.nanoTime() - started < TimeUnit.MINUTES.toNanos(3)) { "更新がタイムアウトしました" }
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
        } finally { c.disconnect() }
    }
}
