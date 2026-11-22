package com.kanzz.editor

import android.content.Context
import java.io.File

/** Copies bundled model assets to a filesystem path readable by ncnn. */
object RifeModelInstaller {
    fun install(context: Context): File {
        val assetRoot = "models/rife-v4.6"
        val destination = File(context.filesDir, assetRoot).apply { mkdirs() }
        fun copyTree(assetPath: String, target: File) {
            val entries = context.assets.list(assetPath) ?: emptyArray()
            if (entries.isEmpty()) {
                if (!target.exists()) context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                target.mkdirs()
                entries.forEach { name -> copyTree("$assetPath/$name", File(target, name)) }
            }
        }
        copyTree(assetRoot, destination)
        return destination
    }
}
