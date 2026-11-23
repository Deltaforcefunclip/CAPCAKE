package com.kanzz.editor.gs

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.Locale

/**
 * Stable interchange contract for GS models.
 *
 * Keep this contract independent from a specific inference engine. A future
 * LiteRT/NCNN runtime or a model trained in GS Training Center can implement
 * [GsVisionRuntime] without changing the GS render pipeline.
 */
data class GsModelManifest(
    val schemaVersion: Int,
    val modelId: String,
    val displayName: String,
    val version: String,
    val runtime: String,
    val task: String,
    val modelFile: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val labels: List<String>,
    val preferredLabels: List<String>,
    val fallbackLabels: List<String>,
    val modelSha256: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("modelId", modelId)
        put("displayName", displayName)
        put("version", version)
        put("runtime", runtime)
        put("task", task)
        put("modelFile", modelFile)
        put("inputWidth", inputWidth)
        put("inputHeight", inputHeight)
        put("labels", JSONArray(labels))
        put("targetPolicy", JSONObject().apply {
            put("preferred", JSONArray(preferredLabels))
            put("fallback", JSONArray(fallbackLabels))
            put("fallbackOnlyWhenPreferredMissing", true)
        })
        put("modelSha256", modelSha256)
    }

    fun validate() {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Versi format model GS tidak didukung: $schemaVersion"
        }
        require(modelId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "modelId tidak valid" }
        require(version.matches(Regex("[A-Za-z0-9._-]{1,40}"))) { "Versi model tidak valid" }
        require(displayName.isNotBlank()) { "Nama model kosong" }
        require(runtime.isNotBlank()) { "Runtime model belum ditentukan" }
        require(task in SUPPORTED_TASKS) { "Task model tidak didukung: $task" }
        require(modelFile == PACKAGE_MODEL_FILE) { "Nama file model paket harus $PACKAGE_MODEL_FILE" }
        require(inputWidth in 32..2048 && inputHeight in 32..2048) { "Ukuran input model tidak valid" }
        require(labels.isNotEmpty()) { "Label model belum tersedia" }
        require(preferredLabels.isNotEmpty()) { "Target utama GS belum ditentukan" }
        require(modelSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "SHA-256 model tidak valid" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val PACKAGE_MODEL_FILE = "model.tflite"
        const val PACKAGE_MANIFEST_FILE = "manifest.json"
        val SUPPORTED_TASKS = setOf("detect", "segment", "detect_segment")

        fun fromJson(json: JSONObject): GsModelManifest {
            val policy = json.optJSONObject("targetPolicy") ?: JSONObject()
            fun strings(array: JSONArray?): List<String> =
                (0 until (array?.length() ?: 0)).mapNotNull { index ->
                    array?.optString(index)?.takeIf { it.isNotBlank() }
                }

            return GsModelManifest(
                schemaVersion = json.optInt("schemaVersion", -1),
                modelId = json.optString("modelId"),
                displayName = json.optString("displayName"),
                version = json.optString("version"),
                runtime = json.optString("runtime", "litert"),
                task = json.optString("task", "detect_segment"),
                modelFile = json.optString("modelFile", PACKAGE_MODEL_FILE),
                inputWidth = json.optInt("inputWidth", 640),
                inputHeight = json.optInt("inputHeight", 640),
                labels = strings(json.optJSONArray("labels")),
                preferredLabels = strings(policy.optJSONArray("preferred")),
                fallbackLabels = strings(policy.optJSONArray("fallback")),
                modelSha256 = json.optString("modelSha256")
            ).also { it.validate() }
        }
    }
}

/** Normalized bounding box (0..1) returned by an object detector. */
data class GsObjectDetection(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val classIndex: Int = -1
) {
    fun validate(): Boolean =
        label.isNotBlank() && confidence in 0f..1f &&
            left in 0f..1f && top in 0f..1f &&
            right in 0f..1f && bottom in 0f..1f &&
            right > left && bottom > top
}

/** Single-channel alpha mask. 255 means keep the pixel; 0 means replace it. */
data class GsObjectMask(
    val width: Int,
    val height: Int,
    val alpha: ByteArray
) {
    init {
        require(width > 0 && height > 0)
        require(alpha.size == width * height)
    }
}

/**
 * Select tanks/armored vehicles first. A weapon is only a fallback when no
 * preferred vehicle was detected in that frame.
 */
object GsTargetSelector {
    fun select(
        detections: List<GsObjectDetection>,
        preferredLabels: List<String>,
        fallbackLabels: List<String>
    ): List<GsObjectDetection> {
        val valid = detections.filter { it.validate() }
        val preferred = valid.filter { matches(it.label, preferredLabels) }
        if (preferred.isNotEmpty()) return preferred
        return valid.filter { matches(it.label, fallbackLabels) }
    }

    private fun matches(label: String, labels: List<String>): Boolean {
        val normalized = normalize(label)
        return labels.any { normalize(it) == normalized }
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
}

/** Runtime adapter boundary. The GS renderer does not depend on LiteRT directly. */
interface GsVisionModel : Closeable {
    val manifest: GsModelManifest
    fun detect(frame: Bitmap): List<GsObjectDetection>

    /** Returns null when the runtime/model has no segmentation output. */
    fun createMask(frame: Bitmap, detection: GsObjectDetection): GsObjectMask?
}

interface GsVisionRuntime {
    val runtimeId: String
    fun load(manifest: GsModelManifest, modelFile: File): GsVisionModel
}
