package io.xenoah.clipdock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var active = false
    @Volatile private var processId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastProgress = 0L
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "ダウンロード・更新", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { cancel(); return START_NOT_STICKY }
        if (active || intent == null) { if (!active) stopSelf(); return START_NOT_STICKY }
        active = true
        cancelled.set(false)
        AppState.clearLog()
        AppState.emit(UiState(true, "準備中…", "エンジンを準備しています"))
        startForeground(NOTIFICATION, notification(AppState.state), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTQuay:transfer").apply { acquire(6 * 60 * 60 * 1000L) }
        worker.execute {
            try {
                MediaFiles.cleanPending(this)
                when (intent.action) {
                    DOWNLOAD -> download(intent)
                    UPDATE -> {
                        val result = Engine.update(this, intent.getBooleanExtra("nightly", false)) { message ->
                            status("yt-dlpを更新中", message)
                        }
                        finish("更新完了", result)
                    }
                    ROLLBACK -> finish("復元完了", Engine.rollback(this))
                    RECOVER -> finish("保存先への転送完了", "${MediaFiles.recover(this)} 個のファイルを回収しました")
                    else -> {
                        status("初期設定中", "初回はPython・FFmpegの展開に少し時間がかかります")
                        Engine.init(this)
                        finish("準備できました", "yt-dlp ${AppState.engineVersion}")
                    }
                }
            } catch (e: Exception) {
                AppState.log(e.stackTraceToString())
                if (cancelled.get() || e is YoutubeDL.CanceledException) finish("キャンセルしました", "別のURLを入力して開始できます")
                else finish("処理を完了できませんでした", friendlyError(e))
            } finally {
                processId = null
                active = false
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun download(intent: Intent) {
        Engine.init(this)
        checkNotCancelled()
        val url = InputRules.urlFromText(intent.getStringExtra("url").orEmpty()) ?: error("HTTP/HTTPSのURLを入力してください")
        val mode = OutputMode.entries.getOrElse(intent.getIntExtra("mode", 0)) { OutputMode.VIDEO }
        val height = intent.getIntExtra("height", 1080).let { if (it in listOf(0, 720, 1080)) it else 1080 }
        val dir = File(MediaFiles.jobsDir(this), UUID.randomUUID().toString()).apply { mkdirs() }
        val request = YoutubeDLRequest(url)
        DownloadSpec(url, mode, height).options().forEach { (option, value) ->
            if (value == null) request.addOption(option) else request.addOption(option, value)
        }
        request.addOption("--no-simulate")
        request.addOption("-o", File(dir, "%(title).100s [%(id)s].%(ext)s").absolutePath)
        request.addCommands(listOf("--print-to-file", "after_move:filepath", File(dir, "completed.txt").absolutePath))
        val id = UUID.randomUUID().toString()
        processId = id
        status("ダウンロード中", "動画情報を取得しています…", cancellable = true)
        checkNotCancelled()
        YoutubeDL.execute(request, id, true) { progress, _, line ->
            AppState.log(line)
            val now = System.currentTimeMillis()
            if (now - lastProgress > 400 && !cancelled.get()) {
                lastProgress = now
                val post = line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") || line.startsWith("[VideoRemuxer]")
                status(if (post) "ファイルを仕上げています" else "ダウンロード中", line.takeLast(240), if (progress < 0 || post) -1 else progress.toInt().coerceIn(0, 100), !post)
            }
        }
        processId = null
        checkNotCancelled()
        val files = MediaFiles.completedFiles(dir)
        check(files.isNotEmpty()) { "ダウンロード済みファイルが見つかりません。ログを確認してください" }
        status("保存中", "Download/YTQuay へ転送しています…")
        var name = ""
        files.forEach { name = MediaFiles.publish(this, it).name }
        dir.deleteRecursively()
        finish("保存しました", name)
    }

    private fun checkNotCancelled() { if (cancelled.get()) throw YoutubeDL.CanceledException() }
    private fun cancel() {
        if (!active || !AppState.state.cancellable) return
        cancelled.set(true)
        status("キャンセル中…", "ダウンロードを停止しています")
        val id = processId
        if (id != null) Thread { repeat(5) { if (YoutubeDL.destroyProcessById(id)) return@Thread; Thread.sleep(100) } }.start()
    }
    private fun status(title: String, detail: String, progress: Int = -1, cancellable: Boolean = false) {
        val s = UiState(true, title, detail, progress, cancellable)
        AppState.emit(s)
        manager.notify(NOTIFICATION, notification(s))
    }
    private fun finish(title: String, detail: String) {
        AppState.emit(UiState(false, title, detail))
        AppState.log("$title: $detail")
        AppState.prefs.edit().putString("lastResult", "$title\n$detail").apply()
    }
    private fun friendlyError(e: Exception): String {
        val message = (e.message ?: e.cause?.message ?: "不明なエラー").trim()
        return when {
            message.contains("No space", true) || message.contains("ENOSPC") -> "空き容量が足りません。空きを作り、更新設定の「未保存ファイルを回収」を実行してください。"
            message.contains("403") && message.contains("sign", true).not() -> "接続先がリクエストを拒否しました (403)。yt-dlpの更新／Nightly版を試してください。詳細はログを確認できます。"
            message.contains("Sign in", true) || message.contains("login", true) -> "ログインが必要な動画です。この版はログイン情報の取り込みに対応していません。"
            else -> message.takeLast(700)
        }
    }
    private fun notification(s: UiState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download)
            .setContentTitle(s.title).setContentText(s.detail.take(120)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, s.progress.coerceAtLeast(0), s.progress < 0)
        if (s.cancellable) {
            val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "キャンセル", cancel).build())
        }
        return builder.build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled.set(true)
        processId?.let { id -> Thread { YoutubeDL.destroyProcessById(id) }.start() }
        AppState.emit(UiState(false, "Androidの実行時間制限で停止しました", "アプリを開いて再試行してください"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        worker.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "clipdock_transfer"
        private const val NOTIFICATION = 42
        const val DOWNLOAD = "io.xenoah.clipdock.DOWNLOAD"
        const val UPDATE = "io.xenoah.clipdock.UPDATE"
        const val ROLLBACK = "io.xenoah.clipdock.ROLLBACK"
        const val RECOVER = "io.xenoah.clipdock.RECOVER"
        const val INIT = "io.xenoah.clipdock.INIT"
        const val CANCEL = "io.xenoah.clipdock.CANCEL"
        fun start(ctx: Context, action: String, url: String = "", mode: Int = 0, height: Int = 1080) {
            if (AppState.state.busy) return
            val intent = Intent(ctx, DownloadService::class.java).setAction(action)
                .putExtra("url", url).putExtra("mode", mode).putExtra("height", height)
                .putExtra("nightly", AppState.prefs.getBoolean("nightly", false))
            ctx.startForegroundService(intent)
        }
    }
}
