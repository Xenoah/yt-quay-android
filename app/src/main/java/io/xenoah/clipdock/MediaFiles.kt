package io.xenoah.clipdock

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

object MediaFiles {
    fun jobsDir(ctx: Context) = File(ctx.noBackupFilesDir, "downloads").apply { mkdirs() }
    fun completedFiles(dir: File): List<File> {
        val manifest = File(dir, "completed.txt")
        if (!manifest.isFile) return emptyList()
        return manifest.readLines().distinct().map { File(it).canonicalFile }.filter {
            it.parentFile == dir.canonicalFile && it.isFile && it.length() > 0 && !File(dir, it.name + ".published").exists()
        }
    }

    fun cleanPending(ctx: Context) {
        val value = AppState.prefs.getString("pendingMediaUri", null) ?: return
        runCatching { ctx.contentResolver.delete(Uri.parse(value), null, null) }
        AppState.prefs.edit().remove("pendingMediaUri").commit()
    }

    fun publish(ctx: Context, file: File): SavedItem {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YTQuay")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("保存先を作成できません")
        AppState.prefs.edit().putString("pendingMediaUri", uri.toString()).commit()
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("保存先を開けません")
            val item = SavedItem(file.name, uri.toString(), mime, file.length(), System.currentTimeMillis())
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            AppState.addHistory(item)
            AppState.prefs.edit().remove("pendingMediaUri").commit()
            File(file.parentFile, file.name + ".published").writeText(uri.toString())
            file.delete()
            return item
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            AppState.prefs.edit().remove("pendingMediaUri").commit()
            throw e
        }
    }

    fun recover(ctx: Context): Int {
        cleanPending(ctx)
        var count = 0
        jobsDir(ctx).listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            completedFiles(dir).forEach { publish(ctx, it); count++ }
            if (completedFiles(dir).isEmpty()) dir.deleteRecursively()
        }
        return count
    }
}
