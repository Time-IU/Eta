package io.github.mangi.eta.ui.voice

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.offline.OfflineAsrModelStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/** Explicit one-tap selection persists only settings; encrypted cloud keys remain untouched. */
@Composable
internal fun OfflineAsrSwitch(store: SpeechSettingsStore) {
    if (store.settings.asr == AsrProvider.SHERPA_NCNN) return
    val selectOffline = {
        store.edit(store.settings.copy(asr = AsrProvider.SHERPA_NCNN))
        store.save(validate = {}, persistCredentials = false)
    }
    OfflineAsrAction(
        text = stringResource(R.string.speech_asr_use_offline),
        enabled = store.loaded && !store.saving,
        onClick = selectOffline,
    )
}

/** No fixed-height text rows: details wrap outside the short house-style dropdown. */
@Composable
private fun OfflineAsrAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiButton(text = text, enabled = enabled, onClick = onClick, modifier = modifier)
    } else {
        TextButton(text = text, enabled = enabled, onClick = onClick, modifier = modifier)
    }
}

private fun OfflineAsrModelStore.ModelDescriptor.shortLabel(): String = when (id) {
    OfflineAsrModelStore.SMALL_ID -> "Small"
    OfflineAsrModelStore.STANDARD_ID -> "Standard"
    else -> label
}

@Composable
internal fun OfflineAsrConnectionSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember(context) { mutableStateOf<List<OfflineAsrModelStore.ModelDescriptor>>(emptyList()) }
    var licenses by remember(context) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var statuses by remember(context) { mutableStateOf<Map<String, OfflineAsrModelStore.ModelStatus>>(emptyMap()) }
    var selectedId by remember(context) { mutableStateOf<String?>(null) }
    // Target is only an installation draft; never present it as the recognizer's selection.
    var targetId by remember(context) { mutableStateOf<String?>(null) }
    var selectionConfirmed by remember(context) { mutableStateOf(true) }
    var busy by remember(context) { mutableStateOf(false) }
    var operation by remember(context) { mutableStateOf<String?>(null) }
    var scopeJob by remember(context) { mutableStateOf<Job?>(null) }
    var generation by remember(context) { mutableStateOf(0) }
    var progress by remember(context) { mutableStateOf<OfflineAsrModelStore.Progress?>(null) }
    var message by remember(context) { mutableStateOf<String?>(null) }
    var pendingImportId by remember(context) { mutableStateOf<String?>(null) }

    // Called only on initial load or an explicit refresh, never from composition.
    suspend fun refresh() {
        statuses = emptyMap()
        val inventory = withContext(Dispatchers.IO) {
            val catalog = OfflineAsrModelStore.availableModels(context)
            val sources = context.assets.open("offline_asr/sources.json").use {
                JSONObject(it.bufferedReader().readText()).getJSONArray("models")
            }
            val licenseMap = (0 until sources.length()).associate { index ->
                val source = sources.getJSONObject(index)
                source.getString("id") to source.getString("license")
            }
            Triple(catalog, OfflineAsrModelStore.selectedModelId(context), licenseMap)
        }
        models = inventory.first
        selectedId = inventory.second
        licenses = inventory.third
        if (models.none { it.id == targetId }) targetId = selectedId
        // inspect hashes payloads (up to both packs); backend dispatches this to IO.
        val checked = models.associate { it.id to OfflineAsrModelStore.inspect(context, it.id) }
        statuses = checked
    }

    // Each operation has its own cancellable child; the composition scope remains reusable.
    fun startOperation(label: String, action: suspend ((OfflineAsrModelStore.Progress) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        operation = label
        message = null
        progress = null
        val token = ++generation
        scopeJob = scope.launch {
            try {
                action { update ->
                    // Backend callbacks are on IO. Mutate Compose snapshots only on Main;
                    // token prevents queued callbacks from an ended/cancelled job resurfacing.
                    scope.launch(Dispatchers.Main.immediate) {
                        if (generation == token && busy) progress = update
                    }
                }
            } catch (cancelled: CancellationException) {
                message = context.getString(R.string.speech_asr_model_cancelled)
                throw cancelled
            } catch (error: Exception) {
                // Do not echo provider paths/URIs or private backend diagnostics into UI.
                message = context.getString(R.string.speech_asr_model_operation_failed)
            } finally {
                ++generation
                busy = false
                operation = null
                progress = null
                scopeJob = null
            }
        }
    }

    fun install(id: String, providerUri: Uri? = null) {
        val label = context.getString(
            if (providerUri == null) R.string.speech_asr_model_downloading else R.string.speech_asr_model_importing,
        )
        startOperation(label) { onProgress ->
            val result = if (providerUri == null) {
                OfflineAsrModelStore.download(context, id, onProgress)
            } else {
                // Actual SAF stream, opened by the backend on IO. The manager owns/uses it
                // and closes it on success, failure AND cancellation, including blocked reads.
                OfflineAsrModelStore.importModel(
                    context, id,
                    open = { requireNotNull(context.contentResolver.openInputStream(providerUri)) },
                    onProgress = onProgress,
                )
            }
            statuses = statuses + (id to result)
            message = context.getString(R.string.speech_asr_model_installed)
            // Installation alone MUST NOT commit a different recognizer model.
        }
    }

    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { providerUri ->
        val id = pendingImportId
        pendingImportId = null
        if (providerUri != null && id != null && !busy) install(id, providerUri)
    }
    LaunchedEffect(context) {
        startOperation(context.getString(R.string.speech_asr_model_checking)) { refresh() }
    }

    fun statusLabel(id: String?): String = when (statuses[id]?.state) {
        OfflineAsrModelStore.State.MISSING -> context.getString(R.string.speech_asr_model_missing)
        OfflineAsrModelStore.State.INSTALLED -> context.getString(R.string.speech_asr_model_ready)
        OfflineAsrModelStore.State.INVALID -> context.getString(R.string.speech_asr_model_invalid)
        null -> context.getString(R.string.speech_asr_model_unchecked)
    }
    val target = models.firstOrNull { it.id == targetId }
    val active = models.firstOrNull { it.id == selectedId }
    val targetStatus = statuses[targetId]
    val awaitingPicker = pendingImportId != null
    val canAct = !busy && !awaitingPicker

    SpeechGroupCard(title = stringResource(R.string.speech_asr_model_manager)) {
        Column {
            SpeechNote(
                if (!selectionConfirmed) stringResource(R.string.speech_asr_model_selection_uncertain)
                else stringResource(
                    R.string.speech_asr_model_active,
                    active?.shortLabel() ?: stringResource(R.string.speech_asr_model_unchecked),
                    statusLabel(selectedId),
                ),
            )
            if (active != null && statuses[selectedId]?.installed == false) {
                SpeechNote(stringResource(R.string.speech_asr_model_unavailable))
            }
            if (models.isNotEmpty()) {
                val labels = models.map { it.shortLabel() }
                val selectedIndex = models.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
                val choose: (Int) -> Unit = { index ->
                    if (canAct) models.getOrNull(index)?.let { targetId = it.id; message = null }
                }
                if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                    CouiDropdownPreference(
                        title = stringResource(R.string.speech_asr_model_target), items = labels,
                        selectedIndex = selectedIndex, enabled = canAct,
                        onSelectedIndexChange = choose,
                    )
                } else {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.speech_asr_model_target), items = labels,
                        selectedIndex = selectedIndex, enabled = canAct,
                        onSelectedIndexChange = choose,
                    )
                }
            }
            if (target != null) {
                SpeechNote(stringResource(R.string.speech_asr_model_target_status, target.shortLabel(), statusLabel(target.id)))
                SpeechNote(stringResource(
                    R.string.speech_asr_model_size,
                    String.format(Locale.ROOT, "%.2f", target.totalBytes / 1_048_576.0), target.totalBytes,
                ))
                SpeechNote(stringResource(R.string.speech_asr_model_format, target.sampleRate, target.featureDim))
                SpeechNote(stringResource(
                    R.string.speech_asr_model_license,
                    licenses[target.id] ?: stringResource(R.string.speech_asr_model_unchecked),
                ))
                SpeechNote(stringResource(R.string.speech_asr_model_native_note))
                if (targetStatus?.state == OfflineAsrModelStore.State.INVALID) {
                    SpeechNote(stringResource(R.string.speech_asr_model_invalid_note))
                }
                OfflineAsrAction(
                    text = stringResource(R.string.speech_asr_model_download),
                    enabled = canAct && targetStatus?.state == OfflineAsrModelStore.State.MISSING,
                    onClick = { install(target.id) },
                )
                OfflineAsrAction(
                    text = stringResource(R.string.speech_asr_model_import),
                    enabled = canAct && targetStatus?.state == OfflineAsrModelStore.State.MISSING,
                    onClick = {
                        pendingImportId = target.id
                        try {
                            importZip.launch(arrayOf("application/zip", "application/octet-stream"))
                        } catch (error: Exception) {
                            pendingImportId = null
                            message = context.getString(R.string.speech_asr_model_picker_failed)
                        }
                    },
                )
                OfflineAsrAction(
                    text = stringResource(R.string.speech_asr_model_use),
                    enabled = canAct && targetStatus?.installed == true,
                    onClick = {
                        startOperation(context.getString(R.string.speech_asr_model_saving)) {
                            // Revalidate only on explicit Use, not on each recomposition.
                            val checked = OfflineAsrModelStore.inspect(context, target.id)
                            statuses = statuses + (target.id to checked)
                            if (!checked.installed) {
                                message = context.getString(R.string.speech_asr_model_unavailable)
                                return@startOperation
                            }
                            try {
                                OfflineAsrModelStore.selectModel(context, target.id)
                                val confirmed = withContext(Dispatchers.IO) {
                                    OfflineAsrModelStore.selectedModelId(context).also { check(it == target.id) }
                                }
                                selectedId = confirmed
                                selectionConfirmed = true
                                message = context.getString(R.string.speech_asr_model_selected)
                            } catch (cancelled: CancellationException) {
                                selectionConfirmed = false
                                throw cancelled
                            } catch (error: Exception) {
                                selectionConfirmed = false
                                message = context.getString(R.string.speech_asr_model_selection_uncertain)
                            }
                        }
                    },
                )
            }
            operation?.let { SpeechNote(it) }
            progress?.let { update ->
                // Raw counters are never estimated from the ZIP/container size.
                val percent = if (update.totalBytes > 0) BigDecimal.valueOf(update.bytesCompleted)
                    .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(update.totalBytes), 1, RoundingMode.DOWN)
                    .toPlainString() else "0.0"
                SpeechNote(stringResource(R.string.speech_asr_model_progress, update.bytesCompleted, update.totalBytes, percent))
                if (update.bytesCompleted == update.totalBytes) {
                    SpeechNote(stringResource(R.string.speech_asr_model_verifying))
                }
            }
            if (busy) {
                OfflineAsrAction(
                    text = stringResource(R.string.speech_asr_model_cancel), enabled = true,
                    onClick = { scopeJob?.cancel() },
                )
            }
            OfflineAsrAction(
                text = stringResource(R.string.speech_asr_model_refresh), enabled = canAct,
                onClick = { startOperation(context.getString(R.string.speech_asr_model_checking)) { refresh() } },
            )
            message?.let { SpeechNote(it) }
            SpeechNote(stringResource(R.string.speech_asr_model_choice_note))
            SpeechNote(stringResource(R.string.speech_asr_model_zip_note))
            SpeechNote(stringResource(R.string.speech_asr_model_zip_files))
            SpeechNote(stringResource(R.string.speech_asr_model_unsupported))
        }
    }
}
