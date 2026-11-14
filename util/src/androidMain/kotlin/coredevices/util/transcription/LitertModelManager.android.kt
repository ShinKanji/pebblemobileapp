package coredevices.util.transcription

import DocumentAttachment
import android.content.Context
import android.os.Build
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.asInputStream
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.koin.mp.KoinPlatform
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Filesystem-backed [LitertModelManager]: imported `.litertlm` bundles live as plain files under
 * `filesDir/litert/`. Optional Qualcomm QNN runtime libraries for the NPU backend are expected in
 * `filesDir/qnn/` (see docs/npu-model-import.md).
 */
actual class LitertModelManager actual constructor() {
    companion object {
        private val logger = Logger.withTag("LitertModelManager")
        private const val LITERTLM_EXTENSION = ".litertlm"
    }

    private val context: Context get() = KoinPlatform.getKoin().get()
    private val litertDir: File get() = context.filesDir.resolve("litert").also { it.mkdirs() }
    private val importing = AtomicBoolean(false)

    actual val importInProgress: Boolean get() = importing.get()

    actual suspend fun importModel(source: DocumentAttachment, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        if (!source.fileName.endsWith(LITERTLM_EXTENSION, ignoreCase = true)) {
            throw IllegalArgumentException("'${source.fileName}' is not a LiteRT-LM bundle (expected a .litertlm file)")
        }
        if (!importing.compareAndSet(false, true)) {
            throw IllegalStateException("Another import is already in progress")
        }
        val tmpFile = litertDir.resolve(".import_tmp_${java.util.UUID.randomUUID()}")
        try {
            var written = 0L
            tmpFile.outputStream().use { output ->
                source.source.asInputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var bytesRead: Int
                    val total = source.size.takeIf { it > 0 }
                    var reported = 0f
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, bytesRead)
                        written += bytesRead
                        if (total != null) {
                            val fraction = (written.toDouble() / total).toFloat().coerceIn(0f, 1f)
                            if (fraction - reported >= 0.01f) {
                                reported = fraction
                                onProgress(fraction)
                            }
                        }
                    }
                }
            }
            if (written <= 0) throw IllegalArgumentException("Imported file '${source.fileName}' was empty")
            val name = uniqueName(source.fileName)
            if (!tmpFile.renameTo(litertDir.resolve(name))) {
                throw IllegalStateException("Failed to finalize import of '$name'")
            }
            onProgress(1f)
            logger.i { "Imported LiteRT-LM bundle '$name' (${written} bytes)" }
            name
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.e(e) { "LiteRT model import failed for ${source.fileName}" }
            throw e
        } finally {
            importing.set(false)
            tmpFile.delete()
        }
    }

    actual fun getModels(): List<LitertModel> {
        return litertDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(LITERTLM_EXTENSION, ignoreCase = true) }
            ?.map { file ->
                LitertModel(
                    name = file.name,
                    displayName = file.name.removeSuffix(LITERTLM_EXTENSION),
                    sizeBytes = file.length(),
                )
            } ?: emptyList()
    }

    actual fun findModel(name: String): LitertModel? {
        val file = litertDir.resolve(name)
        if (!file.isFile) return null
        return LitertModel(name = name, displayName = name.removeSuffix(LITERTLM_EXTENSION), sizeBytes = file.length())
    }

    actual fun deleteModel(name: String) {
        litertDir.resolve(name).delete()
    }

    actual fun getModelSizeBytes(name: String): Long = litertDir.resolve(name).takeIf { it.isFile }?.length() ?: 0L

    /** True on Qualcomm SoCs (Android 12+), where the LiteRT NPU (QNN) backend can run. */
    actual fun isNpuCapable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return Build.SOC_MANUFACTURER.contains("qualcomm", ignoreCase = true) ||
            Build.SOC_MODEL.startsWith("SM", ignoreCase = true)
    }

    actual fun getQnnLibraryDir(): String? {
        val dir = context.filesDir.resolve("qnn")
        if (!dir.isDirectory) return null
        return if (dir.listFiles()?.any { it.name.startsWith("libQnnHtp") } == true) dir.absolutePath else null
    }

    actual fun cleanupTempFiles(): Long {
        var freed = 0L
        litertDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(".import_tmp_")) {
                freed += file.length()
                file.delete()
            }
        }
        // Stale transcription WAVs from the LiteRT engine
        SystemFileSystem.delete(Path(SystemTemporaryDirectory, "litert_stt"), mustExist = false)
        return freed
    }

    private fun uniqueName(fileName: String): String {
        val base = fileName.substringAfterLast('/').trim().take(128).ifBlank { "model$LITERTLM_EXTENSION" }
        var candidate = base
        var i = 2
        while (litertDir.resolve(candidate).exists()) {
            candidate = base.removeSuffix(LITERTLM_EXTENSION) + "-$i$LITERTLM_EXTENSION"
            i++
        }
        return candidate
    }
}
