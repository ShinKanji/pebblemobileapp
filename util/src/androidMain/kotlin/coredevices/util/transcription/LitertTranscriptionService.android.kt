package coredevices.util.transcription

import android.content.Context
import co.touchlab.kermit.Logger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.russhwolf.settings.Settings
import coredevices.util.CoreConfigFlow
import coredevices.util.writeWavHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import java.io.File
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Android actual of [LitertTranscriptionService]: runs the imported `.litertlm` bundle through
 * Google's LiteRT-LM runtime ([Engine]/[Conversation]), mirroring the official
 * litert-community/Qwen3-ASR sample app. Backend preference: NPU (QNN delegate, requires
 * user-provided runtime libraries under `filesDir/qnn/`) -> GPU -> CPU, falling back on load
 * failure.
 *
 * Streaming cancellation: `Conversation.sendMessage` is a blocking JNI call without an interrupt
 * hook, so a cancelled transcription finishes (or errors) in the background; the caller's timeout
 * is applied best-effort around the call. Engine loads are serialized by a mutex so only one
 * engine instance exists at a time.
 */
actual class LitertTranscriptionService actual constructor(
    private val coreConfigFlow: CoreConfigFlow,
    private val litertModelManager: LitertModelManager,
    @Suppress("UNUSED_PARAMETER") settings: Settings,
) {
    companion object {
        private val logger = Logger.withTag("LitertTranscriptionService")
        private const val CPU_THREADS = 4
        private const val MAX_OUTPUT_TOKENS = 512
        private const val ASR_TEXT_TAG = "<asr_text>"
        private val cacheDir = Path(SystemTemporaryDirectory, "litert_stt")

        /** Qwen3-ASR answers look like `language <name><asr_text><transcript>`; strip the metadata. */
        private fun parseAsrAnswer(raw: String): String {
            val answer = raw.trim()
            val tag = answer.indexOf(ASR_TEXT_TAG)
            return if (tag < 0) answer else answer.substring(tag + ASR_TEXT_TAG.length).trim()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val engineMutex = Mutex()
    private val transcribeMutex = Mutex()

    private var engine: Engine? = null
    private var loadedModelName: String? = null
    private var loadedBackend: String? = null
    private var loadJob: Job? = null

    actual val onInitialized = Channel<Boolean>(Channel.RENDEZVOUS)

    actual val isModelReady: Boolean get() = engine != null

    actual val lastBackendUsed: String? get() = loadedBackend

    init {
        coreConfigFlow.flow
            .map { it.sttConfig.litertModelName }
            .onEach { name -> onSelectedModelChanged(name) }
            .launchIn(scope)
    }

    private fun onSelectedModelChanged(name: String?) {
        scope.launch {
            engineMutex.withLock {
                if (name == loadedModelName) return@withLock
                closeEngineLocked()
                if (name != null) loadEngineLocked(name) else onInitialized.trySend(true)
            }
        }
    }

    private fun closeEngineLocked() {
        // Wait out an in-flight transcription before closing its engine (JNI cannot survive it).
        runBlocking { transcribeMutex.withLock { closeEngineNowLocked() } }
    }

    private fun closeEngineNowLocked() {
        engine?.let { current ->
            runCatching { current.close() }
                .onFailure { logger.w(it) { "Failed to close LiteRT engine" } }
        }
        engine = null
        loadedModelName = null
        loadedBackend = null
    }

    /** Backend candidates in preference order; the first that loads wins. */
    private fun backendCandidates(): List<Pair<String, () -> Backend>> {
        val candidates = mutableListOf<Pair<String, () -> Backend>>()
        val qnnDir = litertModelManager.getQnnLibraryDir()
        if (litertModelManager.isNpuCapable() && qnnDir != null) {
            candidates += "NPU" to { Backend.NPU(nativeLibraryDir = qnnDir) }
        }
        candidates += "GPU" to { Backend.GPU() }
        candidates += "CPU" to { Backend.CPU(threadCount = CPU_THREADS) }
        return candidates
    }

    private fun loadEngineLocked(name: String) {
        val bundle = File(bundleDirectory(), name)
        if (!bundle.isFile) {
            logger.w { "LiteRT bundle '$name' missing on disk, cannot load" }
            onInitialized.trySend(false)
            return
        }
        val audioBackend = Backend.CPU(threadCount = CPU_THREADS)
        var lastError: Throwable? = null
        for ((backendName, backendFactory) in backendCandidates()) {
            val candidate = Engine(
                EngineConfig(
                    modelPath = bundle.absolutePath,
                    backend = backendFactory(),
                    audioBackend = audioBackend,
                    cacheDir = engineCacheDirectory().absolutePath,
                )
            )
            try {
                candidate.initialize()
                // The runtime creates the audio encoder with the first conversation; do it once
                // here so the cost lands in "loading" instead of the first transcription.
                candidate.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                        maxOutputToken = MAX_OUTPUT_TOKENS,
                    )
                ).close()
                engine = candidate
                loadedModelName = name
                loadedBackend = backendName
                logger.i { "LiteRT engine loaded '$name' on backend $backendName" }
                onInitialized.trySend(true)
                return
            } catch (t: Throwable) {
                runCatching { if (candidate.isInitialized()) candidate.close() }
                lastError = t
                logger.w(t) { "LiteRT backend $backendName failed for '$name', trying next" }
            }
        }
        logger.e(lastError) { "All LiteRT backends failed for '$name'" }
        onInitialized.trySend(false)
    }

    private fun bundleDirectory(): File = File(context().filesDir, "litert").also { it.mkdirs() }

    private fun engineCacheDirectory(): File = File(context().cacheDir, "litert").also { it.mkdirs() }

    private fun context(): Context = org.koin.mp.KoinPlatform.getKoin().get()

    actual fun isLocalAvailable(): Boolean {
        val name = coreConfigFlow.value.sttConfig.litertModelName ?: return false
        return litertModelManager.findModel(name) != null
    }

    actual fun modelAvailability(): SpeechModelAvailability = when {
        loadedModelName != null -> SpeechModelAvailability.Installed
        else -> SpeechModelAvailability.NotDownloaded
    }

    actual fun earlyInit() {
        val name = coreConfigFlow.value.sttConfig.litertModelName ?: return
        if (loadedModelName == name && engine != null) return
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            engineMutex.withLock {
                if (loadedModelName == name && engine != null) return@withLock
                closeEngineLocked()
                loadEngineLocked(name)
            }
        }
    }

    actual suspend fun transcribeLocal(audio: ByteArray, sampleRate: Int, timeout: Duration?): String = withContext(Dispatchers.IO) {
        val freeMemory = try {
            getFreeMemoryMB()
        } catch (e: Exception) {
            logger.w(e) { "Failed to get free memory" }
            0L
        }
        if (freeMemory < PLATFORM_MIN_TRANSCRIPTION_MEMORY_MB) {
            logger.e { "Low free memory ($freeMemory MB), skipping LiteRT transcription" }
            throw TranscriptionException.NotEnoughMemory(modelUsed = loadedModelName)
        }
        val modelName = loadedModelName
            ?: throw TranscriptionException.TranscriptionRequiresDownload("LiteRT model not loaded")
        val wavPath = writeTempWav(audio, sampleRate)
        transcribeMutex.withLock {
            try {
                val currentEngine = engine
                    ?: throw TranscriptionException.TranscriptionRequiresDownload("LiteRT model not loaded")
                // The runtime auto-resamples any WAV to 16 kHz mono; the 5s-window bundles handle
                // longer clips internally via sliding windows with overlap seam alignment.
                // [timeout] is documented as best-effort: the blocking JNI call cannot be
                // preempted, so the router's own timeout only applies at suspension boundaries.
                val raw = currentEngine.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                        maxOutputToken = MAX_OUTPUT_TOKENS,
                    )
                ).use { conversation ->
                    conversation.sendMessage(Message.user(Contents.of(Content.AudioFile(wavPath.toString())))).toString()
                }
                parseAsrAnswer(raw).also { text ->
                    logger.d { "LiteRT ($loadedBackend) transcribed ${audio.size} bytes -> ${text.length} chars" }
                    if (text.isBlank()) logger.w { "LiteRT transcription returned a blank result" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranscriptionException) {
                throw e
            } catch (e: Exception) {
                throw TranscriptionException.TranscriptionServiceError(
                    e.message ?: "LiteRT transcription failed",
                    e,
                    modelName,
                )
            } finally {
                SystemFileSystem.delete(wavPath, mustExist = false)
            }
        }
    }

    private fun writeTempWav(audio: ByteArray, sampleRate: Int): Path {
        SystemFileSystem.createDirectories(cacheDir, mustCreate = false)
        val path = Path(cacheDir, "litert_stt_${Uuid.random()}.wav")
        SystemFileSystem.sink(path).buffered().use { sink ->
            sink.writeWavHeader(sampleRate, audio.size)
            sink.write(audio)
        }
        return path
    }
}
