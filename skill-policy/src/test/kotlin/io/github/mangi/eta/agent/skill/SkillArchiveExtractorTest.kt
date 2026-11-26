package io.github.mangi.eta.agent.skill

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillArchiveExtractorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun extractsValidArchiveWithNestedFiles() {
        val destination = File(temporaryFolder.root, "valid-out")
        val archive = archive("repo/SKILL.md" to "name: guide".encodeToByteArray(), "repo/references/a.md" to byteArrayOf(1, 2))
        SkillArchiveExtractor(SkillPackageLimits()).extract(archive, destination)
        assertEquals("name: guide", File(destination, "repo/SKILL.md").readText())
        assertTrue(File(destination, "repo/references/a.md").readBytes().contentEquals(byteArrayOf(1, 2)))
    }

    @Test fun rejectsTraversalAndCaseOrUnicodeEquivalentDuplicatePaths() {
        assertCode(SkillInstallErrorCode.UNSAFE_ENTRY_PATH, "../escape" to byteArrayOf(1))
        assertCode(
            SkillInstallErrorCode.DUPLICATE_ENTRY,
            "Guide.txt" to byteArrayOf(1), "guide.TXT" to byteArrayOf(2),
        )
        assertCode(
            SkillInstallErrorCode.DUPLICATE_ENTRY,
            "Café.txt" to byteArrayOf(1), "Cafe\u0301.txt" to byteArrayOf(2),
        )
    }

    @Test fun rejectsFileDirectoryCollisionsAndEnforcesDepthAndEntryLimits() {
        assertCode(
            SkillInstallErrorCode.DUPLICATE_ENTRY,
            "folder" to byteArrayOf(1), "folder/child.txt" to byteArrayOf(2),
        )
        assertCode(
            SkillInstallErrorCode.ENTRY_PATH_TOO_DEEP,
            "a/b/c.txt" to byteArrayOf(1),
            limits = SkillPackageLimits(maxPathDepth = 2),
        )
        assertCode(
            SkillInstallErrorCode.TOO_MANY_ENTRIES,
            "a" to byteArrayOf(1), "b" to byteArrayOf(2),
            limits = SkillPackageLimits(maxEntries = 1),
        )
    }

    @Test fun enforcesSingleFileAndAggregateExtractionByteLimits() {
        assertCode(
            SkillInstallErrorCode.ENTRY_TOO_LARGE,
            "large.bin" to ByteArray(5),
            limits = SkillPackageLimits(maxSingleFileBytes = 4),
        )
        assertCode(
            SkillInstallErrorCode.EXTRACTED_CONTENT_TOO_LARGE,
            "a.bin" to ByteArray(3), "b.bin" to ByteArray(3),
            limits = SkillPackageLimits(maxExtractedBytes = 5),
        )
    }

    @Test fun cancellationAndEmptyArchiveAreReportedPrecisely() {
        val empty = archive()
        val target = File(temporaryFolder.root, "empty-out")
        val error = runCatching { SkillArchiveExtractor(SkillPackageLimits()).extract(empty, target) }.exceptionOrNull()
        assertEquals(SkillInstallErrorCode.INVALID_ARCHIVE, (error as SkillArchiveExtractionException).error.code)

        val archive = archive("cancel.txt" to byteArrayOf(1))
        val cancelledTarget = File(temporaryFolder.root, "cancel-out")
        val cancelled = runCatching {
            SkillArchiveExtractor(SkillPackageLimits()).extract(archive, cancelledTarget) { true }
        }.exceptionOrNull() as SkillArchiveExtractionException
        assertEquals(SkillInstallErrorCode.CANCELLED, cancelled.error.code)
        assertFalse(File(cancelledTarget, "cancel.txt").exists())
    }


    private fun assertCode(
        expected: SkillInstallErrorCode,
        vararg entries: Pair<String, ByteArray>,
        limits: SkillPackageLimits = SkillPackageLimits(),
    ) {
        val archive = archive(*entries)
        val destination = File(temporaryFolder.root, "out-${System.nanoTime()}")
        val error = runCatching { SkillArchiveExtractor(limits).extract(archive, destination) }.exceptionOrNull()
        assertTrue("expected SkillArchiveExtractionException, got $error", error is SkillArchiveExtractionException)
        assertEquals(expected, (error as SkillArchiveExtractionException).error.code)
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return File(temporaryFolder.root, "archive-${System.nanoTime()}.zip").apply { writeBytes(output.toByteArray()) }
    }
}
