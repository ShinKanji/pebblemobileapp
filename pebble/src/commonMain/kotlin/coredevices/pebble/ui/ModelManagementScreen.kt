package coredevices.pebble.ui

import DocumentAttachment
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.ui.M3Dialog
import coredevices.util.CoreConfigHolder
import coredevices.util.models.CactusSTTMode
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.models.inProgressSlug
import coredevices.util.models.inProgress
import coredevices.util.models.ModelInfo
import coredevices.util.models.ModelManager
import coredevices.util.models.RecommendedModel
import coredevices.util.transcription.LitertModel
import coredevices.util.transcription.LitertModelManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import rememberOpenDocumentLauncher
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class ModelManagementScreenViewModel(
    private val modelManager: ModelManager,
    private val coreConfigHolder: CoreConfigHolder,
    private val settings: Settings,
    private val litertModelManager: LitertModelManager? = null,
) : ViewModel() {
    private val _downloadedModels = MutableStateFlow(modelManager.getDownloadedModelSlugs())
    val downloadedModels = _downloadedModels.asStateFlow()
    val modelDownloadState = modelManager.modelDownloadStatus

    init {
        viewModelScope.launch {
            modelDownloadState.drop(1).collect {
                if (!it.inProgress) {
                    refreshDownloadedModels()
                }
            }
        }
    }

    val currentSTTModel = coreConfigHolder.config.map { it.sttConfig.modelName }.onEach {
        Logger.d("Current STT model changed to $it")
    }.stateIn(
        viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.Lazily,
        initialValue = coreConfigHolder.config.value.sttConfig.modelName
    )

    val currentLitertModel = coreConfigHolder.config.map { it.sttConfig.litertModelName }.stateIn(
        viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.Lazily,
        initialValue = coreConfigHolder.config.value.sttConfig.litertModelName
    )

    private val _litertModels = MutableStateFlow(litertModelManager?.getModels() ?: emptyList())
    val litertModels = _litertModels.asStateFlow()

    /** True when the device can run the LiteRT NPU (QNN) backend. */
    val litertNpuCapable: Boolean get() = litertModelManager?.isNpuCapable() == true

    sealed interface AvailableModelsState {
        object Loading : AvailableModelsState
        data class Success(val models: List<ModelInfo>) : AvailableModelsState
        data class Error(val message: String) : AvailableModelsState
    }

    private val _availableSTTModels =
        MutableStateFlow<AvailableModelsState>(AvailableModelsState.Loading)
    val availableSTTModels = _availableSTTModels.asStateFlow()
    private val _availableLanguageModels =
        MutableStateFlow<AvailableModelsState>(AvailableModelsState.Loading)
    val availableLanguageModels = _availableLanguageModels.asStateFlow()

    init {
        refreshAvailableModels()
    }

    fun refreshDownloadedModels() {
        _downloadedModels.value = modelManager.getDownloadedModelSlugs()
    }

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessages = _snackbarMessages.asSharedFlow()

    fun deleteModel(slug: String) {
        modelManager.deleteModel(slug)
        refreshDownloadedModels()
        refreshAvailableModels()
        // With no STT models left, local speech recognition can't run, so fall
        // back to cloud-only and let the user know.
        if (modelManager.getDownloadedSTTModelSlugs().isEmpty() &&
            coreConfigHolder.config.value.sttConfig.mode.usesLocalCactus()
        ) {
            coreConfigHolder.update(
                coreConfigHolder.config.value.copy(
                    sttConfig = coreConfigHolder.config.value.sttConfig.copy(
                        mode = CactusSTTMode.RemoteOnly
                    )
                )
            )
            _snackbarMessages.tryEmit("Speech recognition switched to Cloud Only")
        }
    }

    fun downloadSTTModel(info: ModelInfo) {
        viewModelScope.launch {
            modelManager.downloadSTTModel(info, allowMetered = true)
            modelDownloadState.drop(1)
                .filterIsInstance<ModelDownloadStatus.Idle>()
                .firstOrNull()
            setCurrentSTTModel(info.slug)
            refreshDownloadedModels()
        }
    }

    fun cancelDownload() {
        modelManager.cancelDownload()
    }

    private val _importProgress = MutableStateFlow<Float?>(null)
    val importProgress = _importProgress.asStateFlow()

    fun importModel(source: DocumentAttachment) {
        if (_importProgress.value != null) return
        viewModelScope.launch {
            _importProgress.value = 0f
            try {
                if (source.fileName.endsWith(".litertlm", ignoreCase = true)) {
                    val manager = litertModelManager
                        ?: throw IllegalStateException("The LiteRT engine is not available on this device")
                    val name = manager.importModel(source) { progress ->
                        _importProgress.value = progress
                    }
                    setCurrentLitertModel(name)
                    refreshLitertModels()
                    _snackbarMessages.tryEmit("LiteRT model imported")
                } else {
                    val name = modelManager.importModel(source) { progress ->
                        _importProgress.value = progress
                    } ?: throw IllegalStateException("Model import is not supported on this platform")
                    setCurrentSTTModel(name)
                    refreshDownloadedModels()
                    refreshAvailableModels()
                    _snackbarMessages.tryEmit("Model imported")
                }
            } catch (e: Exception) {
                Logger.e(e) { "Model import failed" }
                _snackbarMessages.tryEmit("Import failed: ${e.message ?: "Unknown error"}")
            } finally {
                _importProgress.value = null
            }
        }
    }

    fun cleanupTempFiles() {
        viewModelScope.launch {
            // File scans are cheap; no dispatcher hop needed (Dispatchers.IO is JVM-only and
            // this ViewModel compiles for iOS too).
            val freed = (litertModelManager?.cleanupTempFiles() ?: 0L) + modelManager.cleanupTempFiles()
            _snackbarMessages.tryEmit(
                if (freed > 0) "Freed ${freed / (1024 * 1024)} MB of temporary files"
                else "No temporary files to clean"
            )
        }
    }

    fun downloadLanguageModel(info: ModelInfo) {
        viewModelScope.launch {
            modelManager.downloadLanguageModel(info, allowMetered = true)
            refreshDownloadedModels()
        }
    }

    fun setCurrentSTTModel(slug: String) {
        val newConfig = coreConfigHolder.config.value.copy(
            sttConfig = coreConfigHolder.config.value.sttConfig.copy(
                modelName = slug,
                // Picking a Cactus model switches the local engine back to Cactus.
                litertModelName = null,
            )
        )
        coreConfigHolder.update(newConfig)
    }

    /** Selecting a LiteRT bundle switches on-device transcription to the LiteRT engine. */
    fun setCurrentLitertModel(name: String) {
        if (litertModelManager == null) return
        val newConfig = coreConfigHolder.config.value.copy(
            sttConfig = coreConfigHolder.config.value.sttConfig.copy(
                litertModelName = name
            )
        )
        coreConfigHolder.update(newConfig)
    }

    fun deleteLitertModel(name: String) {
        litertModelManager?.deleteModel(name)
        refreshLitertModels()
        if (coreConfigHolder.config.value.sttConfig.litertModelName == name) {
            coreConfigHolder.update(
                coreConfigHolder.config.value.copy(
                    sttConfig = coreConfigHolder.config.value.sttConfig.copy(litertModelName = null)
                )
            )
        }
    }

    private fun refreshLitertModels() {
        _litertModels.value = litertModelManager?.getModels() ?: emptyList()
    }

    fun refreshAvailableModels() {
        viewModelScope.launch {
            _availableSTTModels.value = try {
                val models = modelManager.getAvailableSTTModels()
                val recommendedSTTModelSlug = modelManager.getRecommendedSTTModel()
                AvailableModelsState.Success(
                    if (!settings.showDebugOptions()) {
                        models.filter {
                            it.slug == recommendedSTTModelSlug.modelSlug || it.slug in downloadedModels.value
                        }
                    } else {
                        models
                    }
                )
            } catch (e: Exception) {
                AvailableModelsState.Error(e.message ?: "Unknown error")
            }
        }
        viewModelScope.launch {
            _availableLanguageModels.value = try {
                AvailableModelsState.Success(modelManager.getAvailableLanguageModels())
            } catch (e: Exception) {
                AvailableModelsState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun getRecommendedSTTModel(): RecommendedModel {
        return modelManager.getRecommendedSTTModel()
    }

    fun getRecommendedLanguageModel(): String {
        return modelManager.getRecommendedLanguageModel()
    }
}

@Composable
fun ModelDownloadPromptDialog(
    isLite: Boolean,
    downloadSizeInMb: Int,
    onGetRecommended: () -> Unit,
    onDismiss: () -> Unit,
) {
    M3Dialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,

            )
        },
        title = { Text("Download Required") },
        verticalButtons = {
            TextButton(
                onClick = onGetRecommended,
            ) {
                if (isLite) {
                    Text("Download lite model: ${downloadSizeInMb}MB")
                } else {
                    Text("Download model (${downloadSizeInMb}MB)")
                }
            }
            TextButton(
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        }
    ) {
        Text(
            """
                To use offline speech recognition, you need to download a model first.
                Data charges may apply, Wi-Fi is recommended.
            """.trimIndent(),
        )
        if (isLite) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Your device may struggle with larger models, a reduced accuracy model will be used.")
        }
    }
}

/** Mimetypes accepted by the model import picker; zips are often reported as generic binary. */
private val IMPORT_MIME_TYPES = listOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)

@Composable
fun ModelManagementScreen(
    navBarNav: NavBarNav,
    topBarParams: TopBarParams,
    downloadStatus: State<ModelDownloadStatus>,
    downloadedModels: State<List<String>>,
    availableSTTModels: State<ModelManagementScreenViewModel.AvailableModelsState>,
    currentSTTModel: State<String?>,
    recommendedSTTModelSlug: String,
    importProgress: State<Float?>,
    onImportModel: (DocumentAttachment) -> Unit,
    onCleanupTempFiles: () -> Unit,
    litertModels: State<List<LitertModel>>,
    currentLitertModel: State<String?>,
    litertNpuCapable: Boolean,
    onSelectLitertModel: (String) -> Unit,
    onDeleteLitertModel: (String) -> Unit,
    onDownloadSTTModel: (ModelInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onSetCurrentSTTModel: (String) -> Unit,
) {
    LaunchedEffect(Unit) {
        topBarParams.title("Manage Models")
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    val downloadingModelSlug by remember {
        derivedStateOf { downloadStatus.value.inProgressSlug }
    }
    val pickModelFile = rememberOpenDocumentLauncher { attachments ->
        attachments?.firstOrNull()?.let { onImportModel(it) }
    }
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        Scaffold { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(contentType = "import") {
                    val importing = importProgress.value != null
                    if (importing) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                        ) {
                            Text("Importing model…")
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { importProgress.value ?: 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Button(onClick = { pickModelFile(IMPORT_MIME_TYPES) }) {
                            Text("Import model from file")
                        }
                        Text(
                            "Cactus zip bundle or LiteRT-LM .litertlm file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when (val state = availableSTTModels.value) {
                    ModelManagementScreenViewModel.AvailableModelsState.Loading -> {
                        item(contentType = "loading") {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        }
                    }

                    is ModelManagementScreenViewModel.AvailableModelsState.Error -> {
                        item(contentType = "error") {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    is ModelManagementScreenViewModel.AvailableModelsState.Success -> {
                        val reorderedModels = state.models.sortedByDescending {
                            when {
                                it.slug == recommendedSTTModelSlug -> 3
                                it.slug == currentSTTModel.value && currentLitertModel.value == null -> 2
                                downloadedModels.value.contains(it.slug) -> 1
                                else -> 0
                            }
                        }
                        items(reorderedModels, key = { it.slug }) { model ->
                            val isRecommended = model.slug == recommendedSTTModelSlug
                            ModelListItem(
                                modifier = Modifier.animateItem(),
                                model = model,
                                isRecommended = isRecommended,
                                downloadState = when {
                                    downloadedModels.value.contains(model.slug) -> DownloadState.Downloaded
                                    downloadingModelSlug == model.slug -> {
                                        when (val status = downloadStatus.value) {
                                            is ModelDownloadStatus.Downloading -> {
                                                DownloadState.Downloading(status.progress)
                                            }
                                            else -> DownloadState.Downloading(null)
                                        }
                                    }
                                    else -> DownloadState.NotDownloaded
                                },
                                isSelected = currentSTTModel.value == model.slug && currentLitertModel.value == null,
                                onDownload = if (downloadingModelSlug == null) {
                                    { onDownloadSTTModel(model) }
                                } else {
                                    null
                                },
                                onDownloadCancel = {
                                    onCancelDownload()
                                },
                                onDelete = { onDeleteModel(model.slug) },
                                onSelect = { onSetCurrentSTTModel(model.slug) }
                            )
                        }
                    }
                }
                if (litertModels.value.isNotEmpty()) {
                    item(contentType = "litert-header") {
                        Text(
                            "LiteRT-LM models (Qwen3-ASR, NPU/GPU/CPU)",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    items(litertModels.value, key = { "litert_${it.name}" }) { model ->
                        val active = currentLitertModel.value == model.name
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectLitertModel(model.name) },
                            headlineContent = { Text(model.displayName) },
                            supportingContent = {
                                val tags = buildList {
                                    add(if (active) "Active engine" else "Tap to use")
                                    add(if (litertNpuCapable) "NPU/GPU/CPU backends" else "CPU/GPU backends")
                                }
                                Text(tags.joinToString(" · "))
                            },
                            leadingContent = {
                                RadioButton(selected = active, onClick = { onSelectLitertModel(model.name) })
                            },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(onClick = { onDeleteLitertModel(model.name) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    Text(
                                        "${model.sizeBytes / (1024 * 1024)} MB",
                                        modifier = Modifier.offset(y = (-8).dp),
                                    )
                                }
                            },
                        )
                    }
                }
                item(contentType = "cleanup") {
                    TextButton(onClick = onCleanupTempFiles) {
                        Text("Clean up temporary files")
                    }
                }
            }
        }
    }
}

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data class Downloading(val progress: Float? = null) : DownloadState
    data object Downloaded : DownloadState
}

@Composable
private fun ModelListItem(
    modifier: Modifier = Modifier,
    model: ModelInfo,
    isRecommended: Boolean,
    downloadState: DownloadState,
    isSelected: Boolean,
    onDownload: (() -> Unit)?,
    onDownloadCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = downloadState == DownloadState.Downloaded) {
                onSelect()
            },
        overlineContent = { if (isRecommended) { Text("Recommended for your device") } },
        headlineContent = { Text(model.displayName ?: model.slug) },
        supportingContent = {
            val tags = buildList {
                if (model.imported) add("Imported")
                if (model.hasNpuEncoder) add("NPU accelerated")
                model.intendedTask?.let { add(it) }
            }
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "))
        },
        leadingContent = {
            RadioButton(
                selected = isSelected && downloadState == DownloadState.Downloaded,
                onClick = onSelect,
                enabled = downloadState == DownloadState.Downloaded
            )
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when(downloadState) {
                    is DownloadState.Downloading -> {
                        Box(contentAlignment = Alignment.Center) {
                            if (downloadState.progress != null) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .size(26.dp),
                                    strokeWidth = 2.dp,
                                    progress = { downloadState.progress },
                                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .size(26.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            IconButton(
                                onClick = onDownloadCancel,
                            ) {
                                Icon(Icons.Default.Cancel, contentDescription = "Download")
                            }
                        }
                    }
                    DownloadState.NotDownloaded -> {
                        IconButton(
                            onClick = { onDownload?.invoke() },
                            enabled = onDownload != null
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download")
                        }
                    }
                    DownloadState.Downloaded -> {
                        IconButton(
                            onClick = onDelete
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Text("${model.sizeInMB} MB", modifier = Modifier.offset(y = (-8).dp))
            }
        }
    )
}

@Composable
fun ModelManagementScreen(
    navBarNav: NavBarNav,
    topBarParams: TopBarParams,
) {
    val viewModel = koinViewModel<ModelManagementScreenViewModel>()
    val downloadedModels = viewModel.downloadedModels.collectAsState()
    val availableSTTModels = viewModel.availableSTTModels.collectAsState()
    val currentSTTModel = viewModel.currentSTTModel.collectAsState()
    val downloadStatus = viewModel.modelDownloadState.collectAsState()
    val importProgress = viewModel.importProgress.collectAsState()
    val litertModels = viewModel.litertModels.collectAsState()
    val currentLitertModel = viewModel.currentLitertModel.collectAsState()
    val recommendedSTTModel = remember { viewModel.getRecommendedSTTModel() }

    LaunchedEffect(viewModel, topBarParams) {
        viewModel.snackbarMessages.collect { topBarParams.showSnackbar(it) }
    }

    ModelManagementScreen(
        navBarNav = navBarNav,
        topBarParams = topBarParams,
        downloadStatus = downloadStatus,
        downloadedModels = downloadedModels,
        availableSTTModels = availableSTTModels,
        currentSTTModel = currentSTTModel,
        recommendedSTTModelSlug = recommendedSTTModel.modelSlug,
        importProgress = importProgress,
        onImportModel = viewModel::importModel,
        onCleanupTempFiles = viewModel::cleanupTempFiles,
        litertModels = litertModels,
        currentLitertModel = currentLitertModel,
        litertNpuCapable = viewModel.litertNpuCapable,
        onSelectLitertModel = viewModel::setCurrentLitertModel,
        onDeleteLitertModel = viewModel::deleteLitertModel,
        onDownloadSTTModel = viewModel::downloadSTTModel,
        onCancelDownload = viewModel::cancelDownload,
        onDeleteModel = viewModel::deleteModel,
        onSetCurrentSTTModel = viewModel::setCurrentSTTModel,
    )
}

@Preview
@Composable
fun ModelManagementScreenPreview() {
    PreviewWrapper {
        ModelManagementScreen(
            navBarNav = NoOpNavBarNav,
            topBarParams = WrapperTopBarParams,
            downloadStatus = mutableStateOf(ModelDownloadStatus.Downloading(modelSlug = "whisper-base")),
            downloadedModels = mutableStateOf(listOf("whisper-small")),
            availableSTTModels = mutableStateOf(
                ModelManagementScreenViewModel.AvailableModelsState.Success(
                    listOf(
                        ModelInfo(createdAt = Clock.System.now(), slug = "parakeet-tdt-0.6b-v3"),
                        ModelInfo(createdAt = Clock.System.now(), slug = "parakeet-tdt-0.6b-v2", intendedTask = "Higher accuracy for English")
                    )
                )
            ),
            currentSTTModel = mutableStateOf("whisper-small"),
            recommendedSTTModelSlug = "whisper-medium-pro",
            importProgress = mutableStateOf<Float?>(null),
            onImportModel = {},
            onCleanupTempFiles = {},
            litertModels = mutableStateOf(emptyList()),
            currentLitertModel = mutableStateOf(null),
            litertNpuCapable = false,
            onSelectLitertModel = {},
            onDeleteLitertModel = {},
            onDownloadSTTModel = {},
            onCancelDownload = {},
            onDeleteModel = {},
            onSetCurrentSTTModel = {},
        )
    }
}

@Preview
@Composable
fun ModelManagementScreenPromptDialogPreview() {
    PreviewWrapper {
        ModelDownloadPromptDialog(
            isLite = false,
            onGetRecommended = {},
            onDismiss = {},
            downloadSizeInMb = 100,
        )
    }
}

@Preview
@Composable
fun ModelManagementScreenPromptDialogLitePreview() {
    PreviewWrapper {
        ModelDownloadPromptDialog(
            isLite = true,
            onGetRecommended = {},
            onDismiss = {},
            downloadSizeInMb = 100,
        )
    }
}