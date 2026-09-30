package io.xenoah.clipdock

import java.io.File
import java.nio.file.Files

/** Filesystem-only recovery rules. Unknown data is never disposable metadata. */
object RecoveryFiles {
    private const val MANIFEST = "completed.txt"
    private const val PUBLISHED = ".published"

    data class Result(val publishedCount: Int, val retainedJobCount: Int)

    private fun completedPaths(dir: File): List<File> {
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return emptyList()
        val manifest = File(dir, MANIFEST)
        if (!manifest.isFile || Files.isSymbolicLink(manifest.toPath())) return emptyList()
        val root = dir.canonicalFile
        // yt-dlp appends a newline after each path. An unterminated record may be
        // a crash/ENOSPC fragment, so leave its file for a later/manual recovery.
        val records = manifest.readText(Charsets.UTF_8).substringBeforeLast('\n', "")
        return records.split('\n').mapNotNull { path ->
            runCatching {
                val file = File(path)
                if (!file.isAbsolute || Files.isSymbolicLink(file.toPath())) return@runCatching null
                file.canonicalFile.takeIf { it.parentFile == root && it.name != MANIFEST }
            }.getOrNull()
        }.distinct()
    }

    fun completedFiles(dir: File): List<File> = completedPaths(dir).filter {
        val marker = File(dir, it.name + PUBLISHED)
        it.isFile && it.length() > 0 && !marker.exists() && !Files.isSymbolicLink(marker.toPath())
    }

    /** Remove only recognized metadata, and only when no data remains. Never recurse. */
    fun cleanPublishedJob(dir: File): Boolean {
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return false
        val markers = completedPaths(dir).map { it.name + PUBLISHED }.toSet()
        val entries = dir.listFiles() ?: return false
        if (entries.any {
                !it.isFile || Files.isSymbolicLink(it.toPath()) ||
                    (it.name != MANIFEST && it.name !in markers)
            }) return false
        // Keep the manifest if a marker cannot be removed, so the next attempt
        // can still identify the remaining metadata.
        if (!entries.filter { it.name != MANIFEST }.all { it.delete() }) return false
        val manifest = File(dir, MANIFEST)
        if (manifest.exists() && !manifest.delete()) return false
        return dir.delete()
    }

    fun recover(jobsDir: File, publish: (File) -> Unit): Result {
        var published = 0
        var retained = 0
        jobsDir.listFiles()?.filter { it.isDirectory || Files.isSymbolicLink(it.toPath()) }?.forEach { dir ->
            completedFiles(dir).forEach { file -> publish(file); published++ }
            if (!cleanPublishedJob(dir)) retained++
        }
        return Result(published, retained)
    }
}
