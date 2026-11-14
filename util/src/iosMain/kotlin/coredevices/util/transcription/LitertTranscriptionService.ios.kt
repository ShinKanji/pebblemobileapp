package coredevices.util.transcription

import com.russhwolf.settings.Settings
import coredevices.util.CoreConfigFlow
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration

/** iOS stub: the LiteRT-LM engine is Android-only for now (see [PlatformSpeechRecognizer]). */
actual class LitertTranscriptionService actual constructor(
    coreConfigFlow: CoreConfigFlow,
    litertModelManager: LitertModelManager,
    settings: Settings,
) {
    actual val onInitialized = Channel<Boolean>(Channel.RENDEZVOUS)

    actual val isModelReady: Boolean get() = false

    actual val lastBackendUsed: String? get() = null

    actual fun isLocalAvailable(): Boolean = false

    actual fun modelAvailability(): SpeechModelAvailability = SpeechModelAvailability.NotDownloaded

    actual fun earlyInit() {
        onInitialized.trySend(false)
    }

    actual suspend fun transcribeLocal(audio: ByteArray, sampleRate: Int, timeout: Duration?): String {
        throw TranscriptionException.TranscriptionServiceUnavailable(modelUsed = null)
    }
}
