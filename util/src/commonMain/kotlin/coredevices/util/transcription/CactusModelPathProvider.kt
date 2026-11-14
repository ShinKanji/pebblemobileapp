package coredevices.util.transcription

import DocumentAttachment
import coredevices.util.CommonBuildKonfig
import coredevices.util.models.weightsVersionFor

/** A user-imported model bundle found on disk (marked by a `.imported` file inside its directory). */
data class ImportedModel(
    val name: String,
    val displayName: String,
    val sizeBytes: Long,
    val hasNpuEncoder: Boolean,
)

interface CactusModelPathProvider {
    suspend fun getSTTModelPath(
        modelName: String = CommonBuildKonfig.CACTUS_STT_MODEL,
        version: String = weightsVersionFor(modelName),
    ): String
    suspend fun getLMModelPath(): String
    fun isModelDownloaded(modelName: String): Boolean
    fun getDownloadedModels(): List<String>
    fun getIncompatibleModels(): List<String>
    fun deleteModel(modelName: String)
    fun getModelSizeBytes(modelName: String): Long
    fun initTelemetry()

    /**
     * Import a user-provided model bundle (zip stream) into the app's model storage.
     * Returns the model name it was stored under. Default: unsupported on this platform.
     */
    suspend fun importModel(
        source: DocumentAttachment,
        onProgress: (Float) -> Unit = {},
    ): String = error("Model import is not supported on this platform")

    /** User-imported models, i.e. model dirs carrying a `.imported` marker. */
    fun getImportedModels(): List<ImportedModel> = emptyList()

    /** True if the model bundle ships an NPU-compiled encoder (e.g. `npu_audio_encoder*`). */
    fun hasNpuEncoder(modelName: String): Boolean = false

    /** Remove leftover temp files (failed imports, stale download zips, old transcription WAVs). Returns bytes freed. */
    fun cleanupTempFiles(): Long = 0L
}
