package io.github.mangi.eta.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class EtaDatabaseMigrationTest {
    @Test
    fun migration6To28PreservesDataAndMovesCompleteConversationContext() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "migration-${UUID.randomUUID()}.db"
        createVersion6Database(context, databaseName)

        val migration16To17WithMcpData = Migration(16, 17) { database ->
            EtaDatabase.MIGRATION_16_17.migrate(database)
            database.execSQL(
                "INSERT INTO mcp_servers (id, name, url, enabled, protocol_mode, " +
                    "authorization_type, tools_json, enabled_tool_names_json, created_at, " +
                    "sort_order, last_refreshed_at, last_protocol_version) VALUES " +
                    "('mcp-1', 'MCP', 'http://127.0.0.1:8787/mcp', 1, 'auto', 'none', " +
                    "'[]', '[]', 1, 0, 2, '2026-07-28')"
            )
        }

        val database = Room.databaseBuilder(context, EtaDatabase::class.java, databaseName)
            .addMigrations(
                EtaDatabase.MIGRATION_6_7,
                EtaDatabase.MIGRATION_7_8,
                EtaDatabase.MIGRATION_8_9,
                EtaDatabase.MIGRATION_9_10,
                EtaDatabase.MIGRATION_10_11,
                EtaDatabase.MIGRATION_11_12,
                EtaDatabase.MIGRATION_12_13,
                EtaDatabase.MIGRATION_13_14,
                EtaDatabase.MIGRATION_14_15,
                EtaDatabase.MIGRATION_15_16,
                migration16To17WithMcpData,
                EtaDatabase.MIGRATION_17_18,
                EtaDatabase.MIGRATION_18_19,
                EtaDatabase.MIGRATION_19_20,
                EtaDatabase.MIGRATION_20_21,
                EtaDatabase.MIGRATION_21_22,
                EtaDatabase.MIGRATION_22_23,
                EtaDatabase.MIGRATION_23_24,
                EtaDatabase.MIGRATION_24_25,
                EtaDatabase.MIGRATION_25_26,
                EtaDatabase.MIGRATION_26_27,
                EtaDatabase.MIGRATION_27_28,
            )
            .build()
        try {
            val result = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().runtimeResults().single()
            }
            assertEquals("", result.contextSnapshotJson)
            assertEquals("chat", result.operation)
            val archive = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().archivedRuns().single().run
            }
            val conversations = runBlocking(Dispatchers.IO) {
                database.conversationDao().conversations()
            }
            val retainedCheckpoint = runBlocking(Dispatchers.IO) {
                database.conversationDao().contextCheckpoint("conv-1")
            }
            val oversizedCheckpoint = runBlocking(Dispatchers.IO) {
                database.conversationDao().contextCheckpoint("conv-oversized")
            }
            val clearedLegacyHistory = database.openHelper.readableDatabase
                .query("SELECT history_json FROM conversations WHERE id = 'conv-oversized'")
                .use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                }
            val provider = runBlocking(Dispatchers.IO) {
                database.providerDao().providerById("provider-1")!!.toDomain()
            }
            val migratedMessage = runBlocking(Dispatchers.IO) {
                database.conversationDao().messages().single()
            }
            val inFlightRuns = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().inFlightRuns()
            }
            val mcpServers = runBlocking(Dispatchers.IO) {
                database.mcpServerDao().servers()
            }

            assertEquals("保留的结果", result.content)
            assertEquals("[]", result.transcriptJson)
            assertEquals("保留的归档", archive.content)
            assertEquals("[]", archive.transcriptJson)
            assertEquals("[]", archive.userImagePreviewsJson)
            assertEquals(
                setOf("conv-1", "conv-enabled", "conv-custom-empty", "conv-oversized"),
                conversations.mapTo(mutableSetOf()) { it.id },
            )
            assertEquals(
                "[{\"role\":\"user\",\"content\":\"保留上下文\"}]",
                retainedCheckpoint?.historyJson,
            )
            assertEquals("[\"${"x".repeat(140_000)}\"]", oversizedCheckpoint?.historyJson)
            assertEquals(oversizedCheckpoint?.historyJson, oversizedCheckpoint?.journalJson)
            assertEquals("[]", clearedLegacyHistory)
            assertEquals("[]", conversations.first { it.id == "conv-1" }.appliedRuntimeRunIdsJson)
            assertEquals("", conversations.first { it.id == "conv-1" }.roleplayJson)
            assertEquals("", conversations.first { it.id == "conv-1" }.revisionsJson)
            assertEquals(emptyList<CharacterEntity>(), runBlocking(Dispatchers.IO) { database.characterDao().characters() })
            assertEquals("off", conversations.first { it.id == "conv-1" }.reasoningEffort)
            assertEquals(null, conversations.first { it.id == "conv-1" }.modelId)
            assertEquals(false, conversations.first { it.id == "conv-1" }.isPinned)
            assertEquals("default", conversations.first { it.id == "conv-enabled" }.reasoningEffort)
            assertEquals(null, runBlocking(Dispatchers.IO) { database.conversationDao().state() })
            assertEquals(listOf("built-in", "manual"), provider.models.map { it.modelId })
            assertEquals(false, provider.hostedWebSearchEnabled)
            assertEquals(ProviderAuthModes.API_KEY, provider.authMode)
            assertEquals("fixture-existing-api-key", provider.apiKey)
            assertEquals(false, migratedMessage.isEdited)
            assertEquals(emptyList<RuntimeInFlightRunWithEvents>(), inFlightRuns)
            assertEquals(listOf("mcp-1"), mcpServers.map { it.id })
            assertEquals(null, mcpServers.single().toolsExpireAt)
            assertEquals(null, mcpServers.single().toolsTruncationReason)

            // 验证超大 MCP 工具目录通过分块表存储与恢复，且删除服务器后触发器自动清理分块。
            val hugeToolsJson = "[\"${"x".repeat(40_000)}\"]"
            runBlocking(Dispatchers.IO) {
                database.mcpServerDao().insert(
                    mcpServers.single().copy(
                        id = "mcp-oversized",
                        toolsJson = hugeToolsJson,
                        toolsTruncationReason = "tool_limit",
                    ),
                )
                val restored = database.mcpServerDao().serverById("mcp-oversized")!!
                assertEquals(hugeToolsJson, restored.toolsJson)
                assertEquals("tool_limit", restored.toolsTruncationReason)
                assertEquals(1, database.mcpServerDao().delete("mcp-oversized"))
                assertEquals(
                    0,
                    database.mcpServerDao().textChunks("mcp_servers", "mcp-oversized", "tools", 10, 0).size,
                )
            }
            assertEquals(null, provider.models.first().contextWindowOverride)
            assertEquals(null, provider.models.first().reasoningOverride)
            assertEquals(null, provider.models.first().reasoningCapabilitiesOverride)
            assertEquals(
                listOf(ModelSource.CATALOG, ModelSource.MANUAL),
                provider.models.map { it.source },
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun upstreamVersion22KeepsBoundModelAndGainsTaskAndUsageTables() = verifyLegacyUpgrade(22)

    @Test
    fun custom5Version24KeepsPinsAndModelBinding() = verifyLegacyUpgrade(24)

    @Test
    fun custom5Version27KeepsAllExistingMetricsAndAddsIncomingSchema() = verifyLegacyUpgrade(27)

    @Test
    fun backupPinAliasAndBothMetricFamiliesRoundTripWithoutLosingFields() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val imported = json.decodeFromString<ConversationEntity>(
            """{"id":"backup","title":"Kept","thinkingEnabled":false,"createdAt":1,"updatedAt":2,"pinned":true,"categoryId":"category","parentConversationId":"parent","parentMessageId":"fork","branchRootId":"root"}""",
        )
        assertEquals(true, imported.isPinned)
        assertEquals("category", imported.categoryId)
        assertEquals("fork", imported.parentMessageId)
        assertEquals(imported, json.decodeFromString<ConversationEntity>(json.encodeToString(ConversationEntity.serializer(), imported)))
        val message = ConversationMessageEntity(id = "message", conversationId = "backup", sortIndex = 0,
            type = "assistant", content = "Kept", cacheWriteTokens = 12, compactionArchiveId = "archive",
            stepCount = 2, llmMs = 200, toolMs = 30, ttftMs = 10, ttftSteps = 2,
            decodeMs = 190, decodeTokens = 20, requestDurationMs = 240,
            systemPromptTokens = 30, toolDefinitionTokens = 40, conversationTokens = 50,
            firstTokenMs = 11, toolDurationMs = 31, requestCount = 2, createdAt = 123)
        assertEquals(message, json.decodeFromString<ConversationMessageEntity>(
            json.encodeToString(ConversationMessageEntity.serializer(), message)))
    }

    @Test
    fun identityProjectionAndLoadedUpsertPreserveCategoryAndForkMetadata() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        val database = EtaDatabase.get(context)
        try {
            val dao = database.conversationDao()
            val category = ConversationCategoryEntity("category-a", "Category A", 0, 1)
            dao.upsertCategory(category)
            dao.upsertCategory(category.copy(id = "category-b", name = "Category B"))
            val original = ConversationEntity(id = "persisted", title = "Original", thinkingEnabled = false,
                createdAt = 11, updatedAt = 12, isPinned = true, categoryId = category.id,
                parentConversationId = "parent", parentMessageId = "fork-message", branchRootId = "root")
            dao.insertConversations(listOf(original))
            fun assertIdentity(identity: ConversationIdentity, expectedCategory: String?) {
                assertEquals(expectedCategory, identity.categoryId)
                assertEquals("parent", identity.parentConversationId)
                assertEquals("fork-message", identity.parentMessageId)
                assertEquals("root", identity.branchRootId)
                assertEquals(11L, identity.createdAt)
                assertEquals(true, identity.isPinned)
            }
            assertIdentity(dao.conversationIdentities().single(), "category-a")
            val initial = io.github.mangi.eta.ui.model.AgentChatHomeUiState(
                messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = false,
                reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.OFF,
                isConversationLoaded = true)
            val content = io.github.mangi.eta.ui.app.AgentConversationPersistence.Content(initial, "Rewritten", 13, true)
            // A loaded rewrite reconstructs ConversationEntity from the actual DAO identity projection.
            io.github.mangi.eta.ui.app.AgentConversationStore.saveChanges(
                context, original.id, mapOf(original.id to content), emptyMap())
            assertIdentity(dao.conversationIdentities().single(), "category-a")
            assertEquals("Rewritten", dao.conversationEntityRow(original.id)!!.title)
            // Explicit category moves must survive an upsert in the same transaction.
            io.github.mangi.eta.ui.app.AgentConversationStore.saveChanges(
                context, original.id, mapOf(original.id to content.copy(title = "Moved", updatedAt = 14)),
                mapOf(original.id to content), categoryIds = mapOf(original.id to "category-b"))
            assertIdentity(dao.conversationIdentities().single(), "category-b")
            // Preserved IDs represent category changes committed separately from a stale UI snapshot.
            io.github.mangi.eta.ui.app.AgentConversationStore.saveChanges(
                context, original.id, mapOf(original.id to content.copy(title = "Preserved", updatedAt = 15)),
                emptyMap(), categoryIds = emptyMap(), preservedCategoryIds = setOf(original.id))
            assertIdentity(dao.conversationIdentities().single(), "category-b")
            // Unloaded rows only update their small metadata surface, never drop fork fields/messages.
            val retainedMessage = ConversationMessageEntity(id = "retained-message", conversationId = original.id,
                sortIndex = 0, type = "assistant", content = "Retained partial history", cacheWriteTokens = 7,
                compactionArchiveId = "archive-kept", requestCount = 2)
            dao.insertMessages(listOf(retainedMessage))
            val unloaded = content.copy(title = "Unloaded rename", updatedAt = 16, isConversationLoaded = false)
            io.github.mangi.eta.ui.app.AgentConversationStore.saveChanges(
                context, original.id, mapOf(original.id to unloaded), emptyMap(),
                partiallyLoadedConversationIds = setOf(original.id), categoryIds = emptyMap())
            assertIdentity(dao.conversationIdentities().single(), null)
            assertEquals("Unloaded rename", dao.conversationEntityRow(original.id)!!.title)
            assertEquals(listOf(retainedMessage), dao.messages())
            assertEquals(emptyMap<String, String>(), io.github.mangi.eta.ui.app.AgentConversationStore.load(context).categoryIds)
            // Backups use the real entity serializer; header staging must retain categories too.
            val entity = dao.conversationEntityRow(original.id)!!.copy(categoryId = category.id)
            val document = io.github.mangi.eta.data.repository.EtaBackupDocument(
                exportedAt = 123, conversations = listOf(entity), conversationCategories = listOf(category))
            val json = io.github.mangi.eta.data.repository.EtaBackupJsonStreams.json
            val bytes = json.encodeToString(io.github.mangi.eta.data.repository.EtaBackupDocument.serializer(), document)
                .toByteArray(Charsets.UTF_8)
            io.github.mangi.eta.data.repository.EtaBackupJsonStreams.stage(context, bytes.inputStream()).use { staged ->
                assertEquals(listOf(category), staged.header.conversationCategories)
                val restored = mutableListOf<ConversationEntity>()
                staged.conversations { restored += it }
                assertEquals(listOf(entity), restored)
                dao.insertConversations(restored)
                assertIdentity(dao.conversationIdentities().single(), category.id)
                assertEquals(listOf(retainedMessage), dao.messages())
                assertEquals(mapOf(original.id to category.id),
                    io.github.mangi.eta.ui.app.AgentConversationStore.load(context).categoryIds)
            }
        } finally {
            EtaDatabase.closeForTests()
            context.deleteDatabase("eta.db")
        }
    }

    private fun historicalMigrations() = listOf(
        EtaDatabase.MIGRATION_6_7, EtaDatabase.MIGRATION_7_8, EtaDatabase.MIGRATION_8_9,
        EtaDatabase.MIGRATION_9_10, EtaDatabase.MIGRATION_10_11, EtaDatabase.MIGRATION_11_12,
        EtaDatabase.MIGRATION_12_13, EtaDatabase.MIGRATION_13_14, EtaDatabase.MIGRATION_14_15,
        EtaDatabase.MIGRATION_15_16, EtaDatabase.MIGRATION_16_17, EtaDatabase.MIGRATION_17_18,
        EtaDatabase.MIGRATION_18_19, EtaDatabase.MIGRATION_19_20, EtaDatabase.MIGRATION_20_21,
        EtaDatabase.MIGRATION_21_22, EtaDatabase.MIGRATION_22_23, EtaDatabase.MIGRATION_23_24,
        EtaDatabase.MIGRATION_24_25, EtaDatabase.MIGRATION_25_26, EtaDatabase.MIGRATION_26_27,
    )

    private fun verifyLegacyUpgrade(sourceVersion: Int) {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "legacy-${UUID.randomUUID()}.db"
        createVersion6Database(context, databaseName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(sourceVersion) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Expected version 6 fixture")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(6, oldVersion)
                    historicalMigrations().filter { it.endVersion <= sourceVersion }.forEach { it.migrate(db) }
                    db.execSQL("UPDATE conversations SET model_id = 'bound-model' WHERE id = 'conv-1'")
                    if (sourceVersion >= 24) db.execSQL("UPDATE conversations SET is_pinned = 1 WHERE id = 'conv-1'")
                    if (sourceVersion >= 27) {
                        db.execSQL("UPDATE conversation_messages SET cache_write_tokens = 31, compaction_archive_id = 'kept-archive', " +
                            "step_count = 4, llm_ms = 1234, tool_ms = 99, ttft_ms = 28, ttft_steps = 2, " +
                            "decode_ms = 1100, decode_tokens = 80 WHERE id = 'message-1'")
                    }
                }
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { it.writableDatabase }
        val database = Room.databaseBuilder(context, EtaDatabase::class.java, databaseName)
            .addMigrations(*(historicalMigrations() + EtaDatabase.MIGRATION_27_28).toTypedArray())
            .build()
        try {
            runBlocking(Dispatchers.IO) {
                val dao = database.conversationDao()
                val conversation = dao.conversationMetadataRows().first { it.id == "conv-1" }
                assertEquals("保留的对话", conversation.title)
                assertEquals("bound-model", conversation.modelId)
                assertEquals(sourceVersion >= 24, conversation.isPinned)
                assertEquals("", conversation.lastModelUsageJson)
                assertEquals("conv-1", conversation.branchRootId)
                assertEquals(null, conversation.categoryId)
                val message = dao.messages().single()
                assertEquals("旧消息", message.content)
                assertEquals(0L, message.createdAt)
                assertEquals(null, message.requestDurationMs)
                if (sourceVersion >= 27) {
                    assertEquals(31, message.cacheWriteTokens)
                    assertEquals("kept-archive", message.compactionArchiveId)
                    assertEquals(4, message.stepCount)
                    assertEquals(1234L, message.llmMs)
                    assertEquals(99L, message.toolMs)
                    assertEquals(28L, message.ttftMs)
                    assertEquals(2, message.ttftSteps)
                    assertEquals(1100L, message.decodeMs)
                    assertEquals(80, message.decodeTokens)
                }
                assertEquals(emptyList<AgentTaskEntity>(), database.agentTaskDao().tasks())
                assertEquals(0, database.tokenUsageDao().recordCount())
                assertEquals(emptyList<ConversationCategoryEntity>(), dao.categories())
                dao.upsertCategory(ConversationCategoryEntity("category-1", "Retained category", 0, 1))
                dao.setConversationCategory("conv-1", "category-1")
                dao.setConversationBranch("conv-1", "parent-1", "fork-message", "tree-root")
                val branched = dao.conversationMetadataRow("conv-1")!!
                assertEquals("category-1", branched.categoryId)
                assertEquals("parent-1", branched.parentConversationId)
                assertEquals("fork-message", branched.parentMessageId)
                assertEquals("tree-root", branched.branchRootId)
                assertEquals(sourceVersion >= 24, branched.isPinned)
                dao.setConversationPinned("conv-1", true)
                dao.updateConversationTitle("conv-1", "Renamed without unpinning", 123)
                assertEquals(true, dao.conversationMetadataRow("conv-1")!!.isPinned)
                assertEquals(true, dao.conversationIdentities().first { it.id == "conv-1" }.isPinned)
                assertEquals(true, dao.conversationIndex().first { it.id == "conv-1" }.isPinned)
                val task = AgentTaskEntity(id = "task-1", name = "Task", prompt = "Summarize", triggerJson = "{}",
                    enabled = false, nextRunAt = null, cooldownSeconds = 900, maxRuns = 10,
                    runCount = 2, lastStatus = "completed", createdAt = 1, updatedAt = 2)
                database.agentTaskDao().insertTask(task)
                assertEquals(listOf(task), database.agentTaskDao().tasks())
                assertEquals("fixture-existing-api-key", database.providerDao().providerById("provider-1")!!.toDomain().apiKey)
            }
            assertEquals(ETA_DB_SCHEMA_VERSION, database.openHelper.readableDatabase.version)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun incompatibleForkVersion24FailsClosedWithoutDeletingUserData() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "foreign-fork-${UUID.randomUUID()}.db"
        createVersion6Database(context, databaseName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Expected version 6 fixture")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // The incoming fork's v24 did not have custom.5's v22..27 schema.
                    historicalMigrations().filter { it.endVersion <= 21 }.forEach { it.migrate(db) }
                    db.execSQL("ALTER TABLE conversations ADD COLUMN last_model_usage_json TEXT NOT NULL DEFAULT ''")
                    db.execSQL("CREATE TABLE retained_fork_tasks (id TEXT PRIMARY KEY, payload TEXT)")
                    db.execSQL("INSERT INTO retained_fork_tasks VALUES ('task-1', 'keep-fork-task')")
                }
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { it.writableDatabase }
        val database = Room.databaseBuilder(context, EtaDatabase::class.java, databaseName)
            .addMigrations(*(historicalMigrations() + EtaDatabase.MIGRATION_27_28).toTypedArray()).build()
        try {
            org.junit.Assert.assertThrows(RuntimeException::class.java) { database.openHelper.writableDatabase }
        } finally { database.close() }
        val reader = SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Must not recreate user database")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Must not upgrade")
            }).build()
        try {
            FrameworkSQLiteOpenHelperFactory().create(reader).use { helper ->
                val db = helper.readableDatabase
                assertEquals(24, db.version)
                db.query("SELECT title FROM conversations WHERE id = 'conv-1'").use {
                    org.junit.Assert.assertTrue(it.moveToFirst()); assertEquals("保留的对话", it.getString(0))
                }
                db.query("SELECT payload FROM retained_fork_tasks WHERE id = 'task-1'").use {
                    org.junit.Assert.assertTrue(it.moveToFirst()); assertEquals("keep-fork-task", it.getString(0))
                }
            }
        } finally { context.deleteDatabase(databaseName) }
    }

    @Test
    fun migration27To28AddsUsageDetailColumnsWithoutDroppingOldMetrics() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "migration-27-28-${UUID.randomUUID()}.db"
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(27) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, is_pinned INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("INSERT INTO conversations (id, is_pinned) VALUES ('kept', 1)")
                    db.execSQL("CREATE TABLE conversation_messages (id TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL, " +
                        "cache_write_tokens INTEGER, compaction_archive_id TEXT, step_count INTEGER, llm_ms INTEGER)")
                    db.execSQL("INSERT INTO conversation_messages VALUES ('message', 'old body', 20, 'archive', 4, 800)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        try {
            FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
                val database = helper.writableDatabase
                EtaDatabase.MIGRATION_27_28.migrate(database)
                val columns = database.query("PRAGMA table_info(conversation_messages)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
                }
                val expected = setOf("system_prompt_tokens", "tool_definition_tokens", "conversation_tokens",
                    "first_token_ms", "tool_duration_ms", "request_count", "request_duration_ms", "created_at")
                assertEquals(expected, columns.intersect(expected))
                database.query("SELECT content, cache_write_tokens, compaction_archive_id, step_count, llm_ms, created_at FROM conversation_messages").use {
                    org.junit.Assert.assertTrue(it.moveToFirst())
                    assertEquals("old body", it.getString(0)); assertEquals(20, it.getInt(1))
                    assertEquals("archive", it.getString(2)); assertEquals(4, it.getInt(3))
                    assertEquals(800L, it.getLong(4)); assertEquals(0L, it.getLong(5))
                }
                database.query("SELECT is_pinned, branch_root_id FROM conversations WHERE id = 'kept'").use {
                    org.junit.Assert.assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0)); assertEquals("kept", it.getString(1))
                }
            }
        } finally { context.deleteDatabase(databaseName) }
    }

    private fun createVersion6Database(
        context: Context,
        databaseName: String,
    ) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        VERSION_6_SCHEMA.forEach(db::execSQL)
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-enabled', '启用推理', 1, '[]', 4, 4)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-1', '保留的对话', 0, " +
                                "'[{\"role\":\"user\",\"content\":\"保留上下文\"}]', 1, 1)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-bug-empty', '新对话', 0, '[]', 2, 2)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-custom-empty', '用户命名', 0, '[]', 3, 3)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?)",
                            arrayOf<Any>(
                                "conv-oversized",
                                "超长上下文",
                                0,
                                "[\"${"x".repeat(140_000)}\"]",
                                5,
                                5,
                            ),
                        )
                        db.execSQL(
                            "INSERT INTO conversation_state (id, selected_conversation_id) " +
                                "VALUES ('main', 'conv-bug-empty')"
                        )
                        db.execSQL(
                            "INSERT INTO conversation_messages " +
                                "(id, conversation_id, sort_index, type, content, images_json, " +
                                "image_count, tools_json) VALUES " +
                                "('message-1', 'conv-1', 0, 'user', '旧消息', '[]', 0, '[]')"
                        )
                        db.execSQL(
                            "INSERT INTO model_providers " +
                                "(id, type, name, base_url, api_key, is_enabled, is_built_in, sort_order, " +
                                "system_prompt, custom_headers_json, custom_body_json, created_at, endpoint_mode, anthropic_version) " +
                                "VALUES ('provider-1', 'openai_compatible', 'Provider', 'https://example.com/v1', 'fixture-existing-api-key', 1, 0, 0, " +
                                "NULL, '[]', '[]', 1, 'chat_completions', '2023-06-01')"
                        )
                        db.execSQL(providerModelInsert("built-in-id", "built-in", 1, 0))
                        db.execSQL(providerModelInsert("manual-id", "manual", 0, 1))
                        db.execSQL(providerModelInsert("blank-id", "", 0, 2))
                        db.execSQL(
                            "INSERT INTO runtime_results " +
                                "(run_id, handoff_id, handoff_source, handoff_payload, " +
                                "dismiss_entry_surface, ok, content, error, reasoning_content, created_at) " +
                                "VALUES ('run-1', 'handoff-1', 'test', '{}', 0, 1, '保留的结果', NULL, '', 1)"
                        )
                        db.execSQL(
                            "INSERT INTO runtime_archive_runs " +
                                "(archive_run_id, run_id, handoff_id, handoff_source, handoff_payload, " +
                                "dismiss_entry_surface, ok, content, error, reasoning_content, created_at) " +
                                "VALUES ('archive-1', 'run-1', 'handoff-1', 'test', '{}', 0, 1, " +
                                "'保留的归档', NULL, '', 1)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }
            )
            .build()
        FrameworkSQLiteOpenHelperFactory()
            .create(configuration)
            .also { helper ->
                helper.writableDatabase
                helper.close()
            }
    }

    private companion object {
        fun providerModelInsert(id: String, modelId: String, builtIn: Int, sortOrder: Int): String =
            "INSERT INTO provider_models " +
                "(id, provider_id, model_id, display_name, is_enabled, is_built_in, sort_order, owned_by, " +
                "context_window, input_modalities_json, output_modalities_json, attachment, tool_call, reasoning, " +
                "structured_output, supports_temperature, custom_headers_json, custom_body_json, created_at) " +
                "VALUES ('$id', 'provider-1', '$modelId', 'Model', 1, $builtIn, $sortOrder, NULL, NULL, " +
                "'[\"text\"]', '[\"text\"]', NULL, NULL, NULL, NULL, NULL, '[]', '[]', 1)"

        val VERSION_6_SCHEMA = listOf(
            "CREATE TABLE conversations (id TEXT NOT NULL, title TEXT NOT NULL, thinking_enabled INTEGER NOT NULL, history_json TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE conversation_messages (id TEXT NOT NULL, conversation_id TEXT NOT NULL, sort_index INTEGER NOT NULL, type TEXT NOT NULL, content TEXT NOT NULL, images_json TEXT NOT NULL, render_markdown INTEGER, context_tokens INTEGER, input_tokens INTEGER, output_tokens INTEGER, reasoning_tokens INTEGER, cached_tokens INTEGER, elapsed_seconds INTEGER, tool_name TEXT, tool_status TEXT, arguments_summary TEXT, result_summary TEXT, image_count INTEGER NOT NULL, tools_json TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_conversation_messages_conversation_id ON conversation_messages(conversation_id)",
            "CREATE UNIQUE INDEX index_conversation_messages_conversation_id_sort_index ON conversation_messages(conversation_id, sort_index)",
            "CREATE TABLE conversation_state (id TEXT NOT NULL, selected_conversation_id TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE model_providers (id TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, base_url TEXT NOT NULL, api_key TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_built_in INTEGER NOT NULL, sort_order INTEGER NOT NULL, system_prompt TEXT, custom_headers_json TEXT NOT NULL, custom_body_json TEXT NOT NULL, created_at INTEGER NOT NULL, endpoint_mode TEXT NOT NULL, anthropic_version TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE provider_models (id TEXT NOT NULL, provider_id TEXT NOT NULL, model_id TEXT NOT NULL, display_name TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_built_in INTEGER NOT NULL, sort_order INTEGER NOT NULL, owned_by TEXT, context_window INTEGER, input_modalities_json TEXT NOT NULL, output_modalities_json TEXT NOT NULL, attachment INTEGER, tool_call INTEGER, reasoning INTEGER, structured_output INTEGER, supports_temperature INTEGER, custom_headers_json TEXT NOT NULL, custom_body_json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(provider_id) REFERENCES model_providers(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_provider_models_provider_id ON provider_models(provider_id)",
            "CREATE INDEX index_provider_models_provider_id_sort_order ON provider_models(provider_id, sort_order)",
            "CREATE TABLE runtime_results (run_id TEXT NOT NULL, handoff_id TEXT NOT NULL, handoff_source TEXT NOT NULL, handoff_payload TEXT NOT NULL, dismiss_entry_surface INTEGER NOT NULL, ok INTEGER NOT NULL, content TEXT NOT NULL, error TEXT, reasoning_content TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(run_id))",
            "CREATE TABLE runtime_archive_runs (archive_run_id TEXT NOT NULL, run_id TEXT NOT NULL, handoff_id TEXT NOT NULL, handoff_source TEXT NOT NULL, handoff_payload TEXT NOT NULL, dismiss_entry_surface INTEGER NOT NULL, ok INTEGER NOT NULL, content TEXT NOT NULL, error TEXT, reasoning_content TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(archive_run_id))",
            "CREATE TABLE runtime_archive_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, archive_run_id TEXT NOT NULL, sort_index INTEGER NOT NULL, event_json TEXT NOT NULL, FOREIGN KEY(archive_run_id) REFERENCES runtime_archive_runs(archive_run_id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_runtime_archive_events_archive_run_id ON runtime_archive_events(archive_run_id)",
            "CREATE UNIQUE INDEX index_runtime_archive_events_archive_run_id_sort_index ON runtime_archive_events(archive_run_id, sort_index)",
            "CREATE TABLE skill_registry (skill_id TEXT NOT NULL, enabled INTEGER NOT NULL, source TEXT NOT NULL, install_state TEXT NOT NULL, PRIMARY KEY(skill_id))",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'bd87dd0053b011246cba304c35316f07')",
        )
    }
}
