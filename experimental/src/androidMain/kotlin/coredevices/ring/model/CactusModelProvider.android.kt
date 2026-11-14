package coredevices.ring.model

import DocumentAttachment
import android.content.Context
import co.touchlab.kermit.Logger
import com.cactus.cactusSetTelemetryEnvironment
import coredevices.util.CommonBuildKonfig
import coredevices.util.models.promoteSingleRootDir
import coredevices.util.models.weightsVersionFor
import coredevices.util.transcription.ImportedModel
import kotlinx.io.asInputStream
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.mp.KoinPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

actual class CactusModelProvider actual constructor() : coredevices.util.transcription.CactusModelPathProvider {
    companion object {
        private val logger = Logger.withTag("CactusModelProvider")
        private const val HF_BASE = "https://huggingface.co/Cactus-Compute"
        private const val QUANTIZATION = "cq4"
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        private const val IMPORTED_MARKER = ".imported"
        private const val NPU_ENCODER_PREFIX = "npu_audio_encoder"

        // One mutex per model so an in-progress STT download doesn't head-of-line
        // block an unrelated LM resolve (or vice versa).
        private val modelMutexes = ConcurrentHashMap<String, Mutex>()
        private fun mutexFor(modelName: String): Mutex =
            modelMutexes.getOrPut(modelName) { Mutex() }
    }

    private val context: Context get() = KoinPlatform.getKoin().get()
    private val modelsDir: File get() = context.filesDir.resolve("models").also { it.mkdirs() }

    actual override suspend fun getSTTModelPath(modelName: String, version: String): String = withContext(Dispatchers.IO) {
        return@withContext resolveModelPath(modelName, version)
    }

    actual override suspend fun getLMModelPath(): String = withContext(Dispatchers.IO) {
        val modelName = CommonBuildKonfig.CACTUS_LM_MODEL_NAME
        return@withContext resolveModelPath(modelName, weightsVersionFor(modelName))
    }

    actual override fun isModelDownloaded(modelName: String): Boolean {
        val modelDir = modelsDir.resolve(modelName)
        return modelDir.exists() && modelDir.resolve("config.txt").exists()
    }

    actual override fun getDownloadedModels(): List<String> {
        return modelsDir.listFiles()
            ?.filter { it.isDirectory && it.resolve("config.txt").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    actual override fun getIncompatibleModels(): List<String> {
        val compatible = setOf(CommonBuildKonfig.CACTUS_STT_MODEL, CommonBuildKonfig.CACTUS_STT_MODEL_ENG, CommonBuildKonfig.CACTUS_LM_MODEL_NAME)
        return getDownloadedModels().filter { name ->
            !isImported(name) && modelNeedsReplacement(name, compatible, versionMatches(name), isBundled(name))
        }
    }

    private fun versionMatches(modelName: String): Boolean {
        val versionFile = modelsDir.resolve(modelName).resolve(".cactus_version")
        return versionFile.exists() &&
            versionFile.readText().trim() == weightsVersionFor(modelName)
    }

    private fun isBundled(modelName: String): Boolean =
        context.assets.list("models")?.contains("${modelName.lowercase()}-$QUANTIZATION.zip") == true

    actual override fun deleteModel(modelName: String) {
        modelsDir.resolve(modelName).deleteRecursively()
    }

    actual override fun getModelSizeBytes(modelName: String): Long {
        val dir = modelsDir.resolve(modelName)
        return if (dir.exists()) dir.walkTopDown().sumOf { it.length() } else 0L
    }

    private suspend fun resolveModelPath(modelName: String, version: String): String = mutexFor(modelName).withLock {
        val modelDir = modelsDir.resolve(modelName)

        // User-imported bundles are managed manually: no version tracking, no download.
        if (isImported(modelName)) {
            logger.d { "Model '$modelName' (imported) at: ${modelDir.absolutePath}" }
            return modelDir.absolutePath
        }

        val versionFile = modelDir.resolve(".cactus_version")

        val needsDownload = !modelDir.exists()
            || !modelDir.resolve("config.txt").exists()
            || !versionFile.exists()
            || versionFile.readText().trim() != version

        if (needsDownload) {
            downloadAndExtract(modelName, modelDir, version)
            versionFile.writeText(version)
        }

        logger.d { "Model '$modelName' at: ${modelDir.absolutePath}" }
        return modelDir.absolutePath
    }

    private fun isImported(modelName: String): Boolean =
        modelsDir.resolve(modelName).resolve(IMPORTED_MARKER).exists()

    /**
     * Extracts a zip stream into [targetDir] with ZIP-slip protection.
     * When [totalBytes] is > 0, reports bytes written via [onBytes] so callers can show progress.
     */
    private suspend fun extractZipStream(
        input: InputStream,
        targetDir: File,
        totalBytes: Long? = null,
        onBytes: (Long) -> Unit = {},
    ) {
        var written = 0L
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                val outputFile = File(targetDir, entry.name)
                // ZIP Slip protection
                if (!outputFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("ZIP entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { fos ->
                        if (totalBytes == null) {
                            zis.copyTo(fos)
                        } else {
                            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                            var bytesRead: Int
                            while (zis.read(buffer).also { bytesRead = it } != -1) {
                                fos.write(buffer, 0, bytesRead)
                                written += bytesRead
                                onBytes(written)
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private suspend fun downloadAndExtract(modelName: String, targetDir: File, version: String) = withContext(Dispatchers.IO) {
        val zipName = "${modelName.lowercase()}-$QUANTIZATION.zip"

        val tempZip = if (context.assets.list("models")?.contains(zipName) == true) {
            logger.i { "Found included model zip in assets: $zipName, extracting..." }
            val tempZip = File(context.cacheDir, "cactus_asset_$modelName.zip")
            withContext(Dispatchers.IO) {
                context.assets.open("models/$zipName").use { input ->
                    FileOutputStream(tempZip).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            tempZip
        } else {
            val url = "$HF_BASE/$modelName/resolve/$version/$zipName"
            logger.i { "Downloading model: $url" }

            val tempZip = File(context.cacheDir, "cactus_download_$modelName.zip")
            // Cancel the in-flight HTTP call if the coroutine is cancelled so a blocked
            // socket read unblocks promptly instead of hanging until readTimeout.
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val call = client.newCall(Request.Builder().url(url).build())
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()?.take(500) ?: "no body"
                        throw Exception("Download failed: HTTP ${response.code} for $url — $errorBody")
                    }

                    val body = response.body
                        ?: throw Exception("Download failed: empty response body for $url")
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    var lastLoggedPct = -1

                    body.byteStream().use { input ->
                        FileOutputStream(tempZip).use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0) {
                                    val pct = (downloadedBytes * 100 / totalBytes).toInt()
                                    if (pct / 10 > lastLoggedPct / 10) {
                                        lastLoggedPct = pct
                                        logger.d { "Download progress: $pct% ($downloadedBytes / $totalBytes)" }
                                    }
                                }
                            }
                        }
                    }
                }
                logger.i { "Download complete: ${tempZip.length()} bytes" }
                tempZip
            } catch (e: CancellationException) {
                logger.i { "Model download cancelled for $modelName" }
                throw e
            } catch (e: Exception) {
                // A cancelled coroutine cancels the OkHttp call, surfacing as IOException;
                // re-check liveness so cancellation propagates as CancellationException.
                currentCoroutineContext().ensureActive()
                logger.e(e) { "Model download failed for $modelName" }
                throw e
            } finally {
                cancelHandle?.dispose()
            }
        }

        try {
            // Clear old model if present
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            // Extract
            extractZipStream(tempZip.inputStream(), targetDir)
            promoteSingleRootDir(Path(targetDir.absolutePath))
            logger.i { "Extraction complete to ${targetDir.absolutePath}" }
        } catch (e: CancellationException) {
            logger.i { "Model download cancelled for $modelName" }
            targetDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            // A cancelled coroutine cancels the OkHttp call, surfacing as IOException;
            // re-check liveness so cancellation propagates as CancellationException.
            currentCoroutineContext().ensureActive()
            logger.e(e) { "Model download/extract failed for $modelName" }
            targetDir.deleteRecursively()
            throw e
        } finally {
            tempZip.delete()
        }
    }

    actual fun setCloudApiKey(key: String) {
        val cacheDir = getCactusCacheDir()
        val keyFile = File(cacheDir, "cloud_api_key")
        keyFile.writeText(key)
        logger.d { "Cloud API key written to ${keyFile.absolutePath}" }
    }

    actual override fun initTelemetry() {
        val cacheDir = getCactusCacheDir()
        try {
            cactusSetTelemetryEnvironment("kotlin", cacheDir.absolutePath, null)
            logger.d { "Telemetry environment set to ${cacheDir.absolutePath}" }
        } catch (e: Throwable) {
            logger.e(e) { "Failed to initialize telemetry environment" }
        }
    }

    @Serializable
    private data class ImportedMarker(val displayName: String)

    private val markerJson = Json { ignoreUnknownKeys = true }

    override suspend fun importModel(source: DocumentAttachment, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        val tmpDir = modelsDir.resolve(".import_tmp_${java.util.UUID.randomUUID()}")
        try {
            tmpDir.mkdirs()
            val totalBytes = source.size.takeIf { it > 0 }
            var reported = 0f
            extractZipStream(
                input = source.source.asInputStream(),
                targetDir = tmpDir,
                totalBytes = totalBytes,
            ) { written ->
                // totalBytes is null when the provider didn't report a size: progress stays
                // indeterminate (fraction 0 is never emitted).
                val fraction = (written.toFloat() / (totalBytes ?: 0L)).coerceIn(0f, 1f)
                if (fraction - reported >= 0.01f && fraction > 0f) {
                    reported = fraction
                    onProgress(fraction)
                }
            }
            promoteSingleRootDir(Path(tmpDir.absolutePath))
            if (!tmpDir.resolve("config.txt").exists()) {
                throw IllegalArgumentException("'${source.fileName}' is not a Cactus model bundle: config.txt missing")
            }

            val name = uniqueImportedName(source.fileName)
            val displayName = source.fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { name }
            tmpDir.resolve(IMPORTED_MARKER).writeText(markerJson.encodeToString(ImportedMarker(displayName = displayName)))
            if (!tmpDir.renameTo(modelsDir.resolve(name))) {
                throw IllegalStateException("Failed to finalize import of '$name'")
            }
            onProgress(1f)
            logger.i { "Imported model '$name' from '${source.fileName}'" }
            name
        } catch (e: CancellationException) {
            logger.i { "Model import cancelled for ${source.fileName}" }
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.e(e) { "Model import failed for ${source.fileName}" }
            throw e
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    override fun getImportedModels(): List<ImportedModel> {
        return modelsDir.listFiles()
            ?.filter { it.isDirectory && it.resolve(IMPORTED_MARKER).exists() }
            ?.map { dir ->
                val displayName = try {
                    markerJson.decodeFromString<ImportedMarker>(dir.resolve(IMPORTED_MARKER).readText()).displayName
                } catch (_: Exception) {
                    dir.name
                }
                ImportedModel(
                    name = dir.name,
                    displayName = displayName.ifBlank { dir.name },
                    sizeBytes = getModelSizeBytes(dir.name),
                    hasNpuEncoder = hasNpuEncoder(dir.name),
                )
            } ?: emptyList()
    }

    override fun hasNpuEncoder(modelName: String): Boolean {
        val dir = modelsDir.resolve(modelName)
        return dir.listFiles()?.any { it.isFile && it.name.startsWith(NPU_ENCODER_PREFIX) } == true
    }

    override fun cleanupTempFiles(): Long {
        var freed = 0L
        fun sweep(file: File) {
            if (!file.exists()) return
            val size = if (file.isDirectory) file.walkTopDown().sumOf { it.length() } else file.length()
            if (file.deleteRecursively()) freed += size
        }
        // Failed/cancelled imports
        modelsDir.listFiles()?.forEach { if (it.isDirectory && it.name.startsWith(".import_tmp_")) sweep(it) }
        // Partial model dirs (missing sentinel), never touching an in-flight download
        modelsDir.listFiles()?.forEach {
            if (it.isDirectory && it.name in modelMutexes.keys && mutexFor(it.name).isLocked) return@forEach
            if (it.isDirectory && !it.name.startsWith(".") && !it.resolve("config.txt").exists()) sweep(it)
        }
        // Stale download zips (also skip in-flight downloads)
        context.cacheDir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".zip")) return@forEach
            if (!(file.name.startsWith("cactus_download_") || file.name.startsWith("cactus_asset_"))) return@forEach
            val modelName = file.name.removePrefix("cactus_download_").removePrefix("cactus_asset_").removeSuffix(".zip")
            if (modelName in modelMutexes.keys && mutexFor(modelName).isLocked) return@forEach
            sweep(file)
        }
        // Transcription temp WAVs
        SystemFileSystem.delete(Path(SystemTemporaryDirectory, "cactus_stt"), mustExist = false)
        return freed
    }

    private fun uniqueImportedName(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_', '.')
            .take(64)
            .ifBlank { "imported_model" }
        var candidate = base
        var i = 2
        while (modelsDir.resolve(candidate).exists()) {
            candidate = "$base-$i"
            i++
        }
        return candidate
    }

    private fun getCactusCacheDir(): File {
        val dir = context.cacheDir.resolve("cactus")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
