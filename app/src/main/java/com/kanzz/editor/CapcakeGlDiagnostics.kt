package com.kanzz.editor

/**
 * Menyimpan timing GL secara terpisah untuk setiap efek agar statistik
 * Dreamy dan Motion Blur tidak tercampur dalam satu jendela pengukuran.
 */
object CapcakeGlDiagnostics {
    private data class Window(
        var startNs: Long = System.nanoTime(),
        var frames: Int = 0,
        var totalNs: Long = 0L,
        var maxNs: Long = 0L,
        var activeFrames: Int = 0
    )

    private val windows = mutableMapOf<String, Window>()
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun record(effect: String, durationNs: Long, active: Boolean) {
        val window = windows.getOrPut(effect) { Window() }
        window.frames++
        window.totalNs += durationNs
        window.maxNs = maxOf(window.maxNs, durationNs)
        if (active) window.activeFrames++

        val now = System.nanoTime()
        if (now - window.startNs >= 1_000_000_000L) {
            val avgMs = window.totalNs.toDouble() / window.frames / 1_000_000.0
            val maxMs = window.maxNs / 1_000_000.0
            entries.addLast(
                "${now / 1_000_000L}ms | GL_TIMING effect=$effect frames=${window.frames} " +
                    "active=${window.activeFrames} avgCpu=${String.format(java.util.Locale.US, "%.3f", avgMs)}ms " +
                    "maxCpu=${String.format(java.util.Locale.US, "%.3f", maxMs)}ms"
            )
            while (entries.size > 40) entries.removeFirst()
            window.startNs = now
            window.frames = 0
            window.totalNs = 0L
            window.maxNs = 0L
            window.activeFrames = 0
        }
    }

    @Synchronized
    fun snapshot(): String = entries.joinToString("\n")
}
