package io.xenoah.clipdock

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RecoveryFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun jobs(): File = temp.newFolder("jobs")
    private fun job(jobs: File): File = File(jobs, "job").apply { mkdirs() }
    private fun media(dir: File, name: String = "video.mp4"): File = File(dir, name).apply { writeText("saved media bytes") }
    private fun record(dir: File, vararg files: File) = File(dir, "completed.txt").apply {
        writeText(files.joinToString("") { it.absolutePath + "\n" })
    }
    private fun published(file: File) {
        File(file.parentFile, file.name + ".published").writeText("content://media/external/downloads/42")
        check(file.delete())
    }
    private fun recoverWithoutPublishing(jobs: File): RecoveryFiles.Result =
        RecoveryFiles.recover(jobs) { fail("Unexpected publication: $it") }

    @Test fun missingManifestPreservesMediaAcrossRepeatedRecovery() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir)
        repeat(2) {
            assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
            assertEquals("saved media bytes", file.readText())
        }
    }

    @Test fun emptyManifestPreservesMedia() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir); val manifest = record(dir)
        assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
        assertTrue(file.exists()); assertTrue(manifest.exists())
    }

    @Test fun partialManifestRecoversKnownFileButPreservesUnrecordedMediaAndMetadata() {
        val jobs = jobs(); val dir = job(jobs)
        val known = media(dir, "known.mp4"); val unknown = media(dir, "unknown.mp4")
        val manifest = record(dir, known)
        manifest.appendText(unknown.absolutePath.dropLast(3))
        assertEquals(RecoveryFiles.Result(1, 1), RecoveryFiles.recover(jobs, ::published))
        assertFalse(known.exists()); assertEquals("saved media bytes", unknown.readText())
        assertTrue(manifest.exists()); assertTrue(File(dir, known.name + ".published").exists())
        assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
    }

    @Test fun unterminatedRecordDoesNotAuthorizePublicationOrDeletion() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir)
        File(dir, "completed.txt").writeText(file.absolutePath)
        assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
        assertTrue(file.exists())
    }

    @Test fun completeRecordsBeforeTruncatedTailRemainRecoverable() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir)
        record(dir, file).appendText("/unfinished/path")
        assertEquals(RecoveryFiles.Result(1, 0), RecoveryFiles.recover(jobs, ::published))
        assertFalse(dir.exists())
    }

    @Test fun malformedExternalAndRelativeRecordsDoNotHideValidRecord() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir)
        val outside = temp.newFile("outside.mp4").apply { writeText("outside") }
        File(dir, "completed.txt").writeText("\u0000bad\n${outside.absolutePath}\nvideo.mp4\n${file.absolutePath}\n")
        assertEquals(listOf(file.canonicalFile), RecoveryFiles.completedFiles(dir))
        assertEquals(RecoveryFiles.Result(1, 0), RecoveryFiles.recover(jobs, ::published))
        assertEquals("outside", outside.readText())
    }

    @Test fun duplicateRecordsPublishOnlyOnce() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir); record(dir, file, file)
        var calls = 0
        assertEquals(RecoveryFiles.Result(1, 0), RecoveryFiles.recover(jobs) { calls++; published(it) })
        assertEquals(1, calls)
    }

    @Test fun zeroByteAndPartialFilesAreRetained() {
        val jobs = jobs(); val dir = job(jobs)
        val zero = File(dir, "empty.mp4").apply { createNewFile() }
        val part = media(dir, "video.mp4.part"); record(dir, zero)
        assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
        assertTrue(zero.exists()); assertEquals("saved media bytes", part.readText())
    }

    @Test fun nestedDirectoryAndUnknownMarkerAreNeverDeleted() {
        val jobs = jobs(); val dir = job(jobs); record(dir)
        val nested = File(dir, "nested").apply { mkdir() }; val file = media(nested)
        val unknown = media(dir, "unrelated.published")
        assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
        assertTrue(file.exists()); assertTrue(unknown.exists())
    }

    @Test fun publicationFailureKeepsSourceAndManifest() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir); val manifest = record(dir, file)
        try {
            RecoveryFiles.recover(jobs) { throw IOException("destination unavailable") }
            fail("Expected failure")
        } catch (_: IOException) { }
        assertTrue(file.exists()); assertTrue(manifest.exists())
        assertEquals(RecoveryFiles.Result(1, 0), RecoveryFiles.recover(jobs, ::published))
    }

    @Test fun retryAfterSecondPublicationFailsDoesNotRepublishFirstFile() {
        val jobs = jobs(); val dir = job(jobs)
        val first = media(dir, "first.mp4"); val second = media(dir, "second.mp4"); record(dir, first, second)
        val seen = mutableListOf<String>()
        try {
            RecoveryFiles.recover(jobs) {
                seen += it.name
                if (it.name == second.name) throw IOException("full")
                published(it)
            }
            fail("Expected failure")
        } catch (_: IOException) { }
        assertEquals(listOf("first.mp4", "second.mp4"), seen)
        assertTrue(second.exists()); assertFalse(first.exists())
        seen.clear()
        assertEquals(RecoveryFiles.Result(1, 0), RecoveryFiles.recover(jobs) { seen += it.name; published(it) })
        assertEquals(listOf("second.mp4"), seen)
    }

    @Test fun successfulPublishCleansMetadataAndSecondRecoveryIsEmpty() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir); record(dir, file)
        assertEquals(RecoveryFiles.Result(1, 0), RecoveryFiles.recover(jobs, ::published))
        assertFalse(dir.exists())
        assertEquals(RecoveryFiles.Result(0, 0), recoverWithoutPublishing(jobs))
    }

    @Test fun publishedSourceWhoseDeletionFailedIsKeptWithoutRepublishing() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir); val manifest = record(dir, file)
        val marker = File(dir, file.name + ".published").apply { writeText("content://media/external/downloads/42") }
        repeat(2) {
            assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
            assertTrue(file.exists()); assertTrue(marker.exists()); assertTrue(manifest.exists())
        }
    }

    @Test fun callbackReturningWithoutDeletingSourceNeverAuthorizesCleanup() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir); record(dir, file)
        assertEquals(RecoveryFiles.Result(1, 1), RecoveryFiles.recover(jobs) { })
        assertTrue(file.exists()); assertTrue(File(dir, "completed.txt").exists())
    }

    @Test fun symlinkedJobIsNotTraversed() {
        val jobs = jobs(); val external = temp.newFolder("external"); val file = media(external); record(external, file)
        val link = Files.createSymbolicLink(File(jobs, "linked").toPath(), external.toPath())
        assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
        assertTrue(file.exists()); assertTrue(Files.isSymbolicLink(link))
    }

    @Test fun symlinkedManifestIsNotReadOrDeleted() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir)
        val external = temp.newFile("external-manifest").apply { writeText(file.absolutePath + "\n") }
        val link = Files.createSymbolicLink(File(dir, "completed.txt").toPath(), external.toPath())
        assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
        assertTrue(file.exists()); assertTrue(external.exists()); assertTrue(Files.isSymbolicLink(link))
    }

    @Test fun symlinkedSourceIsNotPublishedOrDeleted() {
        val jobs = jobs(); val dir = job(jobs)
        val external = temp.newFile("external-media").apply { writeText("external bytes") }
        val link = Files.createSymbolicLink(File(dir, "video.mp4").toPath(), external.toPath())
        record(dir, link.toFile())
        assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
        assertEquals("external bytes", external.readText()); assertTrue(Files.isSymbolicLink(link))
    }

    @Test fun danglingMarkerSymlinkPreventsPublicationAndCleanup() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir); record(dir, file)
        val target = File(temp.root, "nonexistent-marker")
        val link = Files.createSymbolicLink(File(dir, file.name + ".published").toPath(), target.toPath())
        assertEquals(RecoveryFiles.Result(0, 1), recoverWithoutPublishing(jobs))
        assertTrue(file.exists()); assertFalse(target.exists()); assertTrue(Files.isSymbolicLink(link))
    }

    @Test fun spacesAndUnicodeAreNotTrimmedFromPaths() {
        val jobs = jobs(); val dir = job(jobs); val file = media(dir, " 動画 テスト.mp4 ")
        record(dir, file)
        assertEquals(listOf(file.canonicalFile), RecoveryFiles.completedFiles(dir))
        assertEquals(RecoveryFiles.Result(1, 0), RecoveryFiles.recover(jobs, ::published))
    }

    @Test fun emptyJobAndMetadataOnlyJobCanBeCleaned() {
        val jobs = jobs(); val empty = job(jobs)
        assertTrue(RecoveryFiles.cleanPublishedJob(empty))
        val metadataOnly = File(jobs, "metadata").apply { mkdir() }
        record(metadataOnly)
        assertTrue(RecoveryFiles.cleanPublishedJob(metadataOnly))
    }
}
