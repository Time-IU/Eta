package io.github.mangi.eta.agent.skill

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillArchiveSourceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun streamsArchiveToStagingFileAndReturnsSha256() {
        val bytes = "streamed archive bytes".encodeToByteArray()
        val target = File(temporaryFolder.root, "package.zip")
        val actual = SkillArchiveSource.materialize({ ByteArrayInputStream(bytes) }, target, SkillPackageLimits())
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual)
        assertTrue(target.readBytes().contentEquals(bytes))
    }

    @Test fun rejectsOversizedEmptyAndCancelledArchives() {
        assertFailure(SkillInstallErrorCode.ARCHIVE_TOO_LARGE, ByteArray(5), SkillPackageLimits(maxArchiveBytes = 4))
        assertFailure(SkillInstallErrorCode.INVALID_ARCHIVE, byteArrayOf(), SkillPackageLimits())
        val target = File(temporaryFolder.root, "cancelled.zip")
        val error = runCatching {
            SkillArchiveSource.materialize({ ByteArrayInputStream(ByteArray(20)) }, target, SkillPackageLimits()) { true }
        }.exceptionOrNull() as SkillArchiveExtractionException
        assertEquals(SkillInstallErrorCode.CANCELLED, error.error.code)
    }

    private fun assertFailure(code: SkillInstallErrorCode, bytes: ByteArray, limits: SkillPackageLimits) {
        val target = File(temporaryFolder.root, "archive-${System.nanoTime()}.zip")
        val error = runCatching {
            SkillArchiveSource.materialize({ ByteArrayInputStream(bytes) }, target, limits)
        }.exceptionOrNull() as SkillArchiveExtractionException
        assertEquals(code, error.error.code)
    }
}
