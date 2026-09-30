package io.xenoah.clipdock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*

class MainActivity : Activity() {
    private val bg = Color.rgb(13, 20, 27)
    private val panel = Color.rgb(24, 35, 45)
    private val accent = Color.rgb(113, 237, 194)
    private val muted = Color.rgb(155, 177, 190)
    private val white = Color.rgb(238, 247, 250)
    private lateinit var url: EditText
    private lateinit var mode: Spinner
    private lateinit var quality: Spinner
    private lateinit var videoOptions: LinearLayout
    private lateinit var manualAudioOptions: LinearLayout
    private lateinit var audioFormat: Spinner
    private lateinit var audioBitrate: Spinner
    private lateinit var audioHint: TextView
    private var initialAudio = AudioSelection.restore(null, null, null)
    private var displayedAudioFormat = AudioFormat.MP3
    private lateinit var download: Button
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var cancel: Button
    private lateinit var version: TextView
    private lateinit var engineStatus: TextView
    private lateinit var savedList: LinearLayout
    private lateinit var auto: Switch
    private lateinit var channel: Spinner
    private val actions = ArrayList<Button>()
    private val pages = ArrayList<View>()
    private val tabs = ArrayList<Button>()
    private var currentTab = 0
    private var historySnapshot = ""
    private val observer: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stored = AppState.prefs.all
        initialAudio = AudioSelection.restore(
            if (savedInstanceState?.containsKey("mode") == true) savedInstanceState.getInt("mode") else stored["mode"] as? Int,
            savedInstanceState?.getString("audioFormat") ?: stored["audioFormat"] as? String,
            if (savedInstanceState?.containsKey("audioBitrate") == true) savedInstanceState.getInt("audioBitrate") else stored["audioBitrate"] as? Int,
        )
        val root = column().apply { setBackgroundColor(bg) }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
        val header = column().apply { setPadding(dp(24), dp(24), dp(24), dp(12)) }
        header.addView(text("YT-DLP  /  ANDROID", 11, accent).apply { letterSpacing = 0.17f })
        header.addView(text("YT Quay", 34, white, true))
        header.addView(text("見つけた動画を、手元に。", 14, muted))
        root.addView(header)
        val nav = row().apply { setPadding(dp(16), dp(6), dp(16), dp(16)) }
        listOf("保存", "履歴", "更新設定").forEachIndexed { index, title ->
            val button = button(title) { selectTab(index) }
            tabs += button
            nav.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        root.addView(nav)
        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        val main = buildDownloadPage()
        val history = buildHistoryPage()
        val settings = buildSettingsPage()
        listOf(main, history, settings).forEach {
            val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false; setPadding(dp(20), 0, dp(20), dp(24)); addView(it) }
            pages += scroll
            frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        url.setText(savedInstanceState?.getString("url") ?: AppState.prefs.getString("draftUrl", ""))
        quality.setSelection((savedInstanceState?.getInt("quality") ?: stored["quality"] as? Int ?: 0).coerceIn(0, 2))
        selectTab(savedInstanceState?.getInt("tab") ?: 0)
        handleShare(intent)
        if (!AppState.state.busy && !AppState.prefs.contains("engineVersion")) start(DownloadService.INIT)
    }

    private fun buildDownloadPage(): LinearLayout = column().apply {
        val form = card()
        form.addView(text("01  ダウンロード", 12, accent, true))
        form.addView(text("リンクを貼り付け", 23, white, true).withMargin(12))
        url = EditText(this@MainActivity).apply {
            hint = "https://…"
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = shape(bg, 12, Color.rgb(50, 68, 79))
            maxLines = 4
            minLines = 2
            contentDescription = "ダウンロードするURL"
        }
        form.addView(url, LinearLayout.LayoutParams(-1, dp(112)).apply { topMargin = dp(14) })
        form.addView(button("クリップボードから貼り付け") {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val value = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
            val parsed = InputRules.urlFromText(value)
            if (parsed == null) toast("クリップボードにURLがありません") else url.setText(parsed)
        }.withMargin(8))
        form.addView(text("保存する形式", 13, muted).withMargin(16))
        mode = spinner(OutputMode.entries.map { it.label })
        mode.contentDescription = "動画・オリジナル音声・音声の手動設定"
        mode.setSelection(initialAudio.mode.ordinal)
        form.addView(mode)
        videoOptions = column()
        videoOptions.addView(text("動画の最大画質", 13, muted).withMargin(12))
        quality = spinner(listOf("1080p · フルHD", "720p · 容量を節約", "最高画質 · 上限なし"))
        videoOptions.addView(quality)
        form.addView(videoOptions)
        manualAudioOptions = column()
        manualAudioOptions.addView(text("音声形式", 13, muted).withMargin(12))
        audioFormat = spinner(AudioFormat.entries.map { it.label })
        audioFormat.contentDescription = "手動保存する音声形式"
        audioFormat.setSelection(initialAudio.format.ordinal)
        manualAudioOptions.addView(audioFormat)
        manualAudioOptions.addView(text("目標ビットレート", 13, muted).withMargin(12))
        audioBitrate = spinner(initialAudio.format.bitrates.map { "$it kbps" })
        audioBitrate.contentDescription = "手動保存する音声の目標ビットレート"
        displayedAudioFormat = initialAudio.format
        audioBitrate.setSelection(initialAudio.format.bitrates.indexOf(initialAudio.bitrate))
        manualAudioOptions.addView(audioBitrate)
        form.addView(manualAudioOptions)
        audioHint = text("", 12, muted)
        form.addView(audioHint.withMargin(8))
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateAudioControls() }
        }
        audioFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = AudioFormat.entries.getOrElse(position) { AudioFormat.MP3 }
                if (selected != displayedAudioFormat) {
                    displayedAudioFormat = selected
                    audioBitrate.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item,
                        selected.bitrates.map { "$it kbps" }).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    audioBitrate.setSelection(selected.bitrates.indexOf(selected.defaultBitrate))
                }
            }
        }
        updateAudioControls()
        download = button("↓  保存を開始", primary = true) {
            val value = InputRules.urlFromText(url.text.toString())
            if (value == null) { url.error = "HTTP/HTTPSのURLを入力してください"; return@button }
            url.setText(value)
            val selection = selectedAudio()
            saveDraft()
            askNotificationPermission()
            start(DownloadService.DOWNLOAD, value, selection.mode.storedId,
                listOf(1080, 720, 0).getOrElse(quality.selectedItemPosition) { 1080 }, selection.format.id, selection.bitrate)
        }
        form.addView(download.withMargin(20))
        form.addView(text("保存先  Download / YTQuay\n共有メニューからもURLを受け取れます。", 12, muted).withMargin(12))
        addView(form)
        val status = card()
        statusTitle = text("URLを入れて、手元に保存。", 18, white, true)
        statusDetail = text("動画・音声を端末にダウンロード", 13, muted).apply { setTextIsSelectable(true) }
        status.addView(statusTitle)
        status.addView(statusDetail.withMargin(8))
        progress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(accent)
            indeterminateTintList = ColorStateList.valueOf(accent)
        }
        status.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(14) })
        cancel = button("キャンセル") { startService(Intent(this@MainActivity, DownloadService::class.java).setAction(DownloadService.CANCEL)) }
        status.addView(cancel.withMargin(12))
        status.addView(button("処理ログを見る") { showLogs() }.withMargin(8))
        addView(status.withMargin(16))
    }

    private fun buildHistoryPage(): LinearLayout = column().apply {
        addView(text("手元にあるファイル", 24, white, true))
        addView(text("直近100件。ファイルを開く・共有する。", 13, muted).withMargin(8))
        savedList = column()
        addView(savedList.withMargin(20))
        addView(button("ファイル管理アプリを開く") {
            runCatching { startActivity(Intent("android.intent.action.VIEW_DOWNLOADS")) }
                .onFailure { toast("ファイル管理アプリの Download/YTQuay を開いてください") }
        }.withMargin(12))
    }

    private fun buildSettingsPage(): LinearLayout = column().apply {
        val engine = card()
        engine.addView(text("02  エンジン更新", 12, accent, true))
        engine.addView(text("yt-dlp", 27, white, true).withMargin(12))
        version = text("確認中…", 18, accent)
        engine.addView(version.withMargin(6))
        engine.addView(text("APKを入れ直さず、yt-dlp本体を更新できます。", 13, muted).withMargin(12))
        engine.addView(text("更新チャンネル", 13, muted).withMargin(18))
        channel = spinner(listOf("Stable · 安定版", "Nightly · 最新の修正"))
        channel.setSelection(if (AppState.prefs.getBoolean("nightly", false)) 1 else 0)
        channel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppState.prefs.edit().putBoolean("nightly", position == 1).apply()
            }
        }
        engine.addView(channel)
        engine.addView(button("今すぐ更新する", true) { askNotificationPermission(); start(DownloadService.UPDATE) }.also { actions += it }.withMargin(16))
        engineStatus = text("公式GitHubの更新ファイルとSHA-256を確認します。", 12, muted)
        engine.addView(engineStatus.withMargin(12))
        auto = Switch(this@MainActivity).apply {
            text = "起動時に自動更新"
            setTextColor(white)
            textSize = 15f
            minHeight = dp(56)
            isChecked = AppState.prefs.getBoolean("autoUpdate", false)
            setOnCheckedChangeListener { _, checked -> AppState.prefs.edit().putBoolean("autoUpdate", checked).apply() }
        }
        engine.addView(auto.withMargin(16))
        engine.addView(text("ONの場合、24時間に1回まで確認して更新します。", 12, muted))
        engine.addView(button("前のバージョンへ戻す") { start(DownloadService.ROLLBACK) }.also { actions += it }.withMargin(18))
        addView(engine)
        val maintenance = card()
        maintenance.addView(text("保存とメンテナンス", 18, white, true))
        maintenance.addView(text("空き容量不足などで転送できなかった完成済みファイルを保存先へ回収します。", 13, muted).withMargin(8))
        maintenance.addView(button("未保存ファイルを回収") { start(DownloadService.RECOVER) }.also { actions += it }.withMargin(12))
        maintenance.addView(button("処理ログを見る") { showLogs() }.withMargin(8))
        addView(maintenance.withMargin(16))
        val about = card()
        about.addView(text("YT Quay ${BuildConfig.VERSION_NAME}", 17, white, true))
        about.addView(text("Kotlin / Android 10以降\nPython・FFmpeg・QuickJSを同梱\n1件ずつ保存 · ログイン動画とDRM動画は対象外", 13, muted).withMargin(10))
        about.addView(button("使い方・オープンソース情報") { showAbout() }.withMargin(12))
        addView(about.withMargin(16))
    }

    private fun refresh() {
        val state = AppState.state
        statusTitle.text = state.title
        statusDetail.text = state.detail
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = state.progress < 0
        progress.progress = state.progress.coerceAtLeast(0)
        cancel.visibility = if (state.cancellable) View.VISIBLE else View.GONE
        download.isEnabled = !state.busy
        download.alpha = if (state.busy) 0.45f else 1f
        actions.forEach { it.isEnabled = !state.busy; it.alpha = if (state.busy) 0.45f else 1f }
        version.text = AppState.engineVersion
        engineStatus.text = if (state.busy || state.title.contains("更新") || state.title.contains("復元") || state.title.contains("完了でき")) "${state.title}\n${state.detail}" else "公式GitHubの更新ファイルとSHA-256を確認します。"
        if (auto.isChecked != AppState.prefs.getBoolean("autoUpdate", false)) auto.isChecked = AppState.prefs.getBoolean("autoUpdate", false)
        val items = AppState.history()
        val snapshot = items.joinToString { it.uri }
        if (snapshot != historySnapshot || savedList.childCount == 0) {
            historySnapshot = snapshot
            savedList.removeAllViews()
            if (items.isEmpty()) {
                val empty = card()
                empty.addView(text("まだ保存したファイルはありません", 18, white, true))
                empty.addView(text("最初の動画を保存すると、ここに並びます。", 13, muted).withMargin(10))
                savedList.addView(empty)
            }
            items.forEach { item ->
                val c = card()
                c.addView(text(item.name, 16, white, true))
                c.addView(text("${Formatter.formatFileSize(this, item.size)}  ·  ${DateFormat.format("MM/dd HH:mm", item.time)}", 12, muted).withMargin(8))
                val r = row()
                r.addView(button("開く") { openFile(item, false) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
                r.addView(button("共有") { openFile(item, true) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
                c.addView(r.withMargin(12))
                savedList.addView(c.withMargin(12))
            }
        }
    }

    private fun selectedAudio(): AudioSelection {
        val selectedMode = OutputMode.entries.getOrElse(mode.selectedItemPosition) { OutputMode.ORIGINAL_AUDIO }
        val selectedFormat = AudioFormat.entries.getOrElse(audioFormat.selectedItemPosition) { AudioFormat.MP3 }
        val rate = if (selectedFormat == displayedAudioFormat)
            selectedFormat.bitrates.getOrElse(audioBitrate.selectedItemPosition) { selectedFormat.defaultBitrate }
        else selectedFormat.defaultBitrate
        return AudioSelection(selectedMode, selectedFormat, rate)
    }
    private fun updateAudioControls() {
        val selectedMode = OutputMode.entries.getOrElse(mode.selectedItemPosition) { OutputMode.ORIGINAL_AUDIO }
        videoOptions.visibility = if (selectedMode == OutputMode.VIDEO) View.VISIBLE else View.GONE
        manualAudioOptions.visibility = if (selectedMode == OutputMode.MANUAL_AUDIO) View.VISIBLE else View.GONE
        audioHint.visibility = if (selectedMode == OutputMode.VIDEO) View.GONE else View.VISIBLE
        audioHint.text = if (selectedMode == OutputMode.ORIGINAL_AUDIO)
            "配信元の音声を再圧縮せず保存。動画内の音声は取り出して保存します。"
        else "48kHzで再圧縮します。ビットレートは目標値です。元より音質は上がりません。MP3は最大2chです。"
    }
    private fun saveDraft() {
        val selected = selectedAudio()
        AppState.prefs.edit().putString("draftUrl", url.text.toString())
            .putInt("mode", selected.mode.storedId).putInt("quality", quality.selectedItemPosition)
            .putString("audioFormat", selected.format.id).putInt("audioBitrate", selected.bitrate).apply()
    }
    private fun start(action: String, url: String = "", mode: Int = 0, height: Int = 1080,
                      audioFormat: String = AudioFormat.MP3.id, audioBitrate: Int = AudioFormat.MP3.defaultBitrate) {
        runCatching { DownloadService.start(this, action, url, mode, height, audioFormat, audioBitrate) }
            .onFailure { toast("開始できませんでした: ${it.message}") }
    }
    private fun selectTab(index: Int) {
        currentTab = index.coerceIn(0, 2)
        pages.forEachIndexed { i, view -> view.visibility = if (i == currentTab) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, button -> button.background = shape(if (i == currentTab) accent else panel, 12); button.setTextColor(if (i == currentTab) bg else muted) }
    }
    private fun openFile(item: SavedItem, share: Boolean) {
        runCatching {
            val uri = Uri.parse(item.uri)
            contentResolver.openFileDescriptor(uri, "r")?.close() ?: error("ファイルが見つかりません")
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(item.name, uri)
            startActivity(Intent.createChooser(intent, if (share) "ファイルを共有" else "ファイルを開く"))
        }.onFailure { toast("ファイルが削除されたか、対応アプリがありません") }
    }
    private fun showLogs() {
        val log = AppState.logText().ifBlank { AppState.prefs.getString("lastResult", "まだログはありません")!! }
        val view = text(log, 12, white).apply { setPadding(dp(20), dp(16), dp(20), dp(16)); typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("処理ログ").setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("閉じる", null).setNeutralButton("コピー") { _, _ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("YT Quayログ", log)) }.show()
    }
    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("使い方・オープンソース情報")
            .setMessage("1. URLを貼り付け、形式・画質を選んで保存。\n2. 保存先はDownload/YTQuay。\n3. 取得に失敗したらyt-dlpを更新。修正が先行するNightlyも選べます。\n\nyt-dlp本体はAPKと別に更新します。Python・FFmpeg・QuickJSの変更にはAPKの更新が必要です。\n\nYT Quay: GPL-3.0-or-later\nyoutubedl-android 0.18.1: GPL-3.0\nyt-dlp: Unlicense（一部依存ライブラリは別ライセンス）\nFFmpeg / Python / QuickJS: 各同梱ライセンス参照\n\nソース一式のTHIRD_PARTY_NOTICES.mdに出典とライセンスを記載しています。")
            .setPositiveButton("閉じる", null).show()
    }
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val value = InputRules.urlFromText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        if (value != null) { url.setText(value); selectTab(0) } else toast("共有テキストにURLがありません")
        intent.action = null
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleShare(intent) }
    override fun onStart() { super.onStart(); AppState.observe(observer) }
    override fun onResume() {
        super.onResume()
        val prefs = AppState.prefs
        if (!AppState.state.busy && prefs.getBoolean("autoUpdate", false) && System.currentTimeMillis() - prefs.getLong("lastAutoAttempt", 0) >= 86_400_000L) {
            prefs.edit().putLong("lastAutoAttempt", System.currentTimeMillis()).apply()
            start(DownloadService.UPDATE)
        }
    }
    override fun onStop() {
        AppState.remove(observer)
        saveDraft()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val selected = selectedAudio()
        outState.putString("url", url.text.toString()); outState.putInt("mode", selected.mode.storedId); outState.putInt("quality", quality.selectedItemPosition); outState.putInt("tab", currentTab)
        outState.putString("audioFormat", selected.format.id); outState.putInt("audioBitrate", selected.bitrate)
        super.onSaveInstanceState(outState)
    }
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun card() = column().apply { background = shape(panel, 20); setPadding(dp(20), dp(20), dp(20), dp(20)); layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f); if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private fun button(title: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = title; textSize = 14f; isAllCaps = false; minHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10)); background = shape(if (primary) accent else Color.rgb(36, 52, 64), 12)
        setTextColor(if (primary) bg else white); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)); setPadding(dp(8), 0, dp(8), 0); backgroundTintList = ColorStateList.valueOf(muted)
    }
    private fun shape(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke) }
    private fun <T : View> T.withMargin(top: Int): T = apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
