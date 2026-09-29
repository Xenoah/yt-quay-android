package io.xenoah.clipdock

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

class ClipDockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
    }
}

data class UiState(val busy: Boolean = false, val title: String = "URLを入れて、手元に保存。", val detail: String = "動画・音声を端末にダウンロード", val progress: Int = -1, val cancellable: Boolean = false)
data class SavedItem(val name: String, val uri: String, val mime: String, val size: Long, val time: Long)

object AppState {
    lateinit var context: Context
        private set
    val prefs get() = context.getSharedPreferences("clipdock", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile var state = UiState()
        private set
    private val lines = ArrayDeque<String>()
    val engineVersion get() = prefs.getString("engineVersion", "同梱版")!!
    fun init(ctx: Context) { context = ctx.applicationContext }
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    fun emit(s: UiState) { state = s; notifyChange() }
    fun notifyChange() { handler.post { listeners.forEach { it() } } }
    @Synchronized fun log(line: String) {
        if (line.isBlank()) return
        lines.addLast(line.take(2000))
        while (lines.size > 100) lines.removeFirst()
    }
    @Synchronized fun logText(): String = lines.joinToString("\n")
    @Synchronized fun clearLog() { lines.clear() }
    @Synchronized fun history(): List<SavedItem> = runCatching {
        val a = JSONArray(prefs.getString("history", "[]"))
        (0 until a.length()).map { i ->
            val j = a.getJSONObject(i)
            SavedItem(j.getString("name"), j.getString("uri"), j.getString("mime"), j.getLong("size"), j.getLong("time"))
        }
    }.getOrDefault(emptyList())
    @Synchronized fun addHistory(item: SavedItem) {
        val a = JSONArray()
        (listOf(item) + history()).take(100).forEach { i ->
            a.put(JSONObject().put("name", i.name).put("uri", i.uri).put("mime", i.mime).put("size", i.size).put("time", i.time))
        }
        prefs.edit().putString("history", a.toString()).commit()
    }
}
