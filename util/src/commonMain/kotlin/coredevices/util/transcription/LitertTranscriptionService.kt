package coredevices.util.transcription

import com.russhwolf.settings.Settings
import coredevices.util.CoreConfigFlow
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel

/**
 * On-device ASR running a user-imported LiteRT-LM bundle (`.litertlm`, e.g. Qwen3-ASR-0.6B)
 * through Google's LiteRT-LM runtime. Backend preference is NPU (Qualcomm Hexagon via the QNN
 * delegate when the user supplied QNN libraries) with GPU and CPU fallbacks.
 *
 * Android actual hosts the real engine; the iOS actual is an unavailable stub.
 */
expect class LitertTranscriptionService(
    coreConfigFlow: CoreConfigFlow,
    litertModelManager: LitertModelManager,
    settings: Settings,
) {
    /** True when a bundle is selected in [coredevices.util.STTConfig.litertModelName] and present on disk. */
    fun isLocalAvailable(): Boolean

    /** True when the engine is loaded and ready to transcribe. */
    val isModelReady: Boolean

    /** Human-readable name of the backend the last transcription ran on (NPU/GPU/CPU), or null. */
    val lastBackendUsed: String?

    val onInitialized: Channel<Boolean>

    /** Begin loading the active bundle in the background. */
    fun earlyInit()

    /**
     * Transcribe 16-bit PCM audio on the LiteRT engine. Throws [TranscriptionException] subclasses
     * on failure so the hybrid router can fall back to cloud.
     */
    suspend fun transcribeLocal(
        audio: ByteArray,
        sampleRate: Int = 16000,
        timeout: Duration? = null,
    ): String

    fun modelAvailability(): SpeechModelAvailability
}
