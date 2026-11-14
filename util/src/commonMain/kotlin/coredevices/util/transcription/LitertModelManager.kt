package coredevices.util.transcription

import DocumentAttachment

/** A user-imported LiteRT-LM model bundle (`.litertlm` file) found in the app's litert storage. */
data class LitertModel(
    val name: String,
    val displayName: String,
    val sizeBytes: Long,
)

/**
 * Manages user-imported LiteRT-LM ASR bundles (e.g. `qwen3_asr_0.6b_5s_i8.litertlm`).
 *
 * Bundles are single files stored under the app's litert directory; no unpacking is needed.
 * The Android actual talks to the filesystem, the iOS actual is a no-op stub (the LiteRT
 * engine is Android-only for now, matching [PlatformSpeechRecognizer]).
 */
expect class LitertModelManager() {
    /** Import a `.litertlm` bundle picked via the system file picker. Returns the stored model name. */
    suspend fun importModel(
        source: DocumentAttachment,
        onProgress: (Float) -> Unit = {},
    ): String

    /** All imported LiteRT-LM bundles. */
    fun getModels(): List<LitertModel>

    fun findModel(name: String): LitertModel?

    fun deleteModel(name: String)

    fun getModelSizeBytes(name: String): Long

    /** True when the device SoC is one the LiteRT NPU backend can target (Qualcomm Snapdragon). */
    fun isNpuCapable(): Boolean

    /**
     * Directory with Qualcomm AI Engine Direct (QNN) runtime libraries to hand to
     * [com.google.ai.edge.litertlm.Backend.NPU], or null when the user hasn't provided them.
     */
    fun getQnnLibraryDir(): String?

    /** Remove partial imports and other temp leftovers. Returns bytes freed. */
    fun cleanupTempFiles(): Long

    /** Whether a `.litertlm` bundle is currently being imported. */
    val importInProgress: Boolean
}
