package com.kanzz.editor.gs

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Stores portable .gspkg files.
 *
 * Package format:
 *   manifest.json
 *   model.tflite
 *
 * Only these two entries are accepted. This prevents ZIP path traversal and
 * makes model packages easy to export from a separate training environment.
 */
class GsModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "gs/models")
    private val preferences = appContext.getSharedPreferences("gs_model_store", Context.MODE_PRIVATE)

    data class InstalledModel(
        val folderName: String,
        val manifest: GsModelManifest,
        val modelFile: File
    )

    fun listModels(): List<InstalledModel> {
        if (!root.exists()) return emptyList()
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".import-") }
            ?.mapNotNull { directory ->
                runCatching {
                    val manifestFile = File(directory, GsModelManifest.PACKAGE_MANIFEST_FILE)
                    val modelFile = File(directory, GsModelManifest.PACKAGE_MODEL_FILE)
                    require(manifestFile.isFile && modelFile.isFile)
                    val manifest = GsModelManifest.fromJson(JSONObject(manifestFile.readText()))
                    require(sha256(modelFile) == manifest.modelSha256.lowercase())
                    InstalledModel(directory.name, manifest, modelFile)
                }.getOrNull()
            }
            ?.sortedByDescending { it.manifest.version }
            ?.toList()
            ?: emptyList()
    }

    fun getActiveModel(): InstalledModel? {
        val folderName = preferences.getString(KEY_ACTIVE_MODEL, null) ?: return null
        return listModels().firstOrNull { it.folderName == folderName }
    }

    fun setActiveModel(folderName: String) {
        require(listModels().any { it.folderName == folderName }) {
            "Model GS tidak ditemukan atau file model tidak valid."
        }
        preferences.edit().putString(KEY_ACTIVE_MODEL, folderName).apply()
    }

    fun importPackage(input: InputStream, activate: Boolean = true): InstalledModel {
        root.mkdirs()
        val staging = File(root, ".import-${System.nanoTime()}").apply { mkdirs() }
        val manifestFile = File(staging, GsModelManifest.PACKAGE_MANIFEST_FILE)
        val modelFile = File(staging, GsModelManifest.PACKAGE_MODEL_FILE)
        var manifestSeen = false
        var modelSeen = false

        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Folder tidak diizinkan di dalam paket model GS." }
                    when (entry.name) {
                        GsModelManifest.PACKAGE_MANIFEST_FILE -> {
                            require(!manifestSeen) { "manifest.json ganda." }
                            copyEntry(zip, manifestFile, MAX_MANIFEST_BYTES)
                            manifestSeen = true
                        }
                        GsModelManifest.PACKAGE_MODEL_FILE -> {
                            require(!modelSeen) { "model.tflite ganda." }
                            copyEntry(zip, modelFile, MAX_MODEL_BYTES)
                            modelSeen = true
                        }
                        else -> throw IllegalArgumentException("Isi paket tidak dikenal: ${entry.name}")
                    }
                    zip.closeEntry()
                }
            }

            require(manifestSeen && modelSeen) { "Paket harus berisi manifest.json dan model.tflite." }
            val manifest = GsModelManifest.fromJson(JSONObject(manifestFile.readText()))
            require(sha256(modelFile) == manifest.modelSha256.lowercase()) {
                "Checksum model tidak cocok. File mungkin rusak."
            }

            val folderName = "${manifest.modelId}_${manifest.version}"
            require(folderName.matches(Regex("[A-Za-z0-9._-]{1,130}"))) { "Nama folder model tidak valid." }
            val destination = File(root, folderName)
            require(!destination.exists()) { "Model versi ini sudah terpasang: ${manifest.displayName} ${manifest.version}" }
            check(staging.renameTo(destination)) { "Gagal menyimpan model GS." }

            val installed = InstalledModel(
                folderName = folderName,
                manifest = manifest,
                modelFile = File(destination, GsModelManifest.PACKAGE_MODEL_FILE)
            )
            if (activate) setActiveModel(folderName)
            return installed
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun exportPackage(folderName: String, output: OutputStream) {
        val installed = listModels().firstOrNull { it.folderName == folderName }
            ?: throw IllegalArgumentException("Model GS tidak ditemukan.")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(GsModelManifest.PACKAGE_MANIFEST_FILE))
            zip.write(installed.manifest.toJson().toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(GsModelManifest.PACKAGE_MODEL_FILE))
            installed.modelFile.inputStream().buffered().use { input -> input.copyTo(zip) }
            zip.closeEntry()
            zip.finish()
        }
    }

    private fun copyEntry(input: InputStream, destination: File, maxBytes: Long) {
        var copied = 0L
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= maxBytes) { "Ukuran file dalam paket model terlalu besar." }
                output.write(buffer, 0, count)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_ACTIVE_MODEL = "active_model_folder"
        private const val MAX_MANIFEST_BYTES = 1024L * 1024L
        private const val MAX_MODEL_BYTES = 512L * 1024L * 1024L
    }
}
