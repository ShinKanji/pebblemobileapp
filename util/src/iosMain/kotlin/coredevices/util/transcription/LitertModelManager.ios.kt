package coredevices.util.transcription

import DocumentAttachment

/**
 * iOS stub: the LiteRT-LM engine is Android-only for now, matching [PlatformSpeechRecognizer].
 * Every operation is a no-op / reports empty.
 */
actual class LitertModelManager actual constructor() {
    actual val importInProgress: Boolean get() = false

    actual suspend fun importModel(source: DocumentAttachment, onProgress: (Float) -> Unit): String {
        throw IllegalStateException("LiteRT model import is not supported on iOS")
    }

    actual fun getModels(): List<LitertModel> = emptyList()

    actual fun findModel(name: String): LitertModel? = null

    actual fun deleteModel(name: String) {}

    actual fun getModelSizeBytes(name: String): Long = 0L

    actual fun isNpuCapable(): Boolean = false

    actual fun getQnnLibraryDir(): String? = null

    actual fun cleanupTempFiles(): Long = 0L
}
