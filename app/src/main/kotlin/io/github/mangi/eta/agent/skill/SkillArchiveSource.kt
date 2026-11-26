package io.github.mangi.eta.agent.skill

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Bounded, cancellable archive materialization shared by install and inspect flows. */
internal object SkillArchiveSource {
    fun materialize(
        openStream: () -> InputStream,
        target: File,
        limits: SkillPackageLimits,
        isCancelled: () -> Boolean = { false },
    ): String {
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        checkCancelled(isCancelled)
        openStream().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limits.maxArchiveBytes) {
                        fail(SkillInstallErrorCode.ARCHIVE_TOO_LARGE, "ZIP 压缩包超过 ${limits.maxArchiveBytes} 字节限制")
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        if (total == 0L) fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 为空")
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) fail(SkillInstallErrorCode.CANCELLED, "Skill 安装已取消")
    }

    private fun fail(code: SkillInstallErrorCode, message: String): Nothing =
        throw SkillArchiveExtractionException(SkillInstallError(code, message))
}
