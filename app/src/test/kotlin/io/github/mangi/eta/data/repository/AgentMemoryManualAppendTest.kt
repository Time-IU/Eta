package io.github.mangi.eta.data.repository

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only a fresh TemporaryFolder; never initializes or reads the user repository singleton. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryManualAppendTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private fun store() = AgentMemoryStore(temporaryFolder.newFolder())
    private fun append(store: AgentMemoryStore, text: String): AgentMemoryWriteResult =
        store.mutate(AgentMemoryMutation.Append(store.snapshot().revision, text))

    @Test fun manualAppendAccepts3501ChineseCharactersVerbatim() {
        val store = store()
        val text = "汉".repeat(3501)
        val result = append(store, text) as AgentMemoryWriteResult.Success
        assertEquals(text, result.snapshot.content)
        assertEquals(10503, result.snapshot.byteSize)
        assertEquals(result.snapshot, store.snapshot())
    }

    @Test fun utf8AppendAcceptsOneByteBelowAndExactlyOneMiB() {
        val store = store()
        val text = "汉".repeat((AgentMemoryStore.MAX_FILE_BYTES - 1) / 3)
        val below = append(store, text) as AgentMemoryWriteResult.Success
        assertEquals(AgentMemoryStore.MAX_FILE_BYTES - 1, below.snapshot.byteSize)
        // Existing nonempty body gets one separator byte. Empty append is a no-op.
        val exact = append(store, "") as AgentMemoryWriteResult.Success
        assertEquals(below.snapshot, exact.snapshot)
        val emptyStore = store()
        val atLimit = text + "a"
        val full = append(emptyStore, atLimit) as AgentMemoryWriteResult.Success
        assertEquals(AgentMemoryStore.MAX_FILE_BYTES, full.snapshot.byteSize)
        assertEquals(atLimit, emptyStore.snapshot().content)
    }

    @Test fun oneByteAboveLimitFailsWithoutCreatingMemoryFile() {
        val store = store()
        val before = store.snapshot()
        val failure = assertThrows(AgentMemoryException::class.java) {
            append(store, "a".repeat(AgentMemoryStore.MAX_FILE_BYTES + 1))
        }
        assertEquals("MEMORY_TOO_LARGE", failure.code)
        assertEquals(before, store.snapshot())
    }

    @Test fun mergedAppendExactlyAtLimitSucceedsThenOverLimitPreservesFile() {
        val store = store()
        append(store, "汉".repeat((AgentMemoryStore.MAX_FILE_BYTES - 4) / 3))
        // 1,048,572 + newline + three UTF-8 bytes = 1 MiB.
        val full = append(store, "汉") as AgentMemoryWriteResult.Success
        assertEquals(AgentMemoryStore.MAX_FILE_BYTES, full.snapshot.byteSize)
        val failure = assertThrows(AgentMemoryException::class.java) { append(store, "x") }
        assertEquals("MEMORY_TOO_LARGE", failure.code)
        assertEquals(full.snapshot, store.snapshot())
    }

    @Test fun staleRevisionConflictsWithoutWritingAndFreshRevisionCanRetry() {
        val store = store()
        val stale = store.snapshot()
        val first = append(store, "其他操作") as AgentMemoryWriteResult.Success
        val conflict = store.mutate(AgentMemoryMutation.Append(stale.revision, "  手动原文\n"))
            as AgentMemoryWriteResult.Conflict
        assertEquals(first.snapshot, conflict.snapshot)
        assertEquals(first.snapshot, store.snapshot())
        val retry = store.mutate(AgentMemoryMutation.Append(conflict.snapshot.revision, "  手动原文\n"))
            as AgentMemoryWriteResult.Success
        assertEquals("其他操作\n  手动原文\n", retry.snapshot.content)
    }
}
