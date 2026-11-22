package com.kanzz.editor

/**
 * Menyimpan timing GL agar ikut tampil pada dialog Diagnostik CAPCAKE,
 * bukan hanya Android Logcat yang tidak ikut disalin pengguna.
 */
object CapcakeGlDiagnostics {
    private val entries = ArrayDeque<String>()
    private var windowStartNs = System.nanoTime()
    private var frameCount = 0
    private var totalNs = 0L
    private var maxNs = 0L
    private var activeFrames = 0

    @Synchronized
    fun record(effect: String, durationNs: Long, active: Boolean) {
        frameCount++
        totalNs += durationNs
        maxNs = maxOf(maxNs, durationNs)
        if (active) activeFrames++
        val now = System.nanoTime()
        if (now - windowStartNs >= 1_000_000_000L) {
            val avgMs = totalNs.toDouble() / frameCount / 1_000_000.0
            val maxMs = maxNs / 1_000_000.0
            entries.addLast(
                "${now / 1_000_000L}ms | GL_TIMING effect=$effect frames=$frameCount " +
                    "active=$activeFrames avgCpu=${String.format(java.util.Locale.US, "%.3f", avgMs)}ms " +
                    "maxCpu=${String.format(java.util.Locale.US, "%.3f", maxMs)}ms"
            )
            while (entries.size > 40) entries.removeFirst()
            windowStartNs = now
            frameCount = 0
            totalNs = 0L
            maxNs = 0L
            activeFrames = 0
        }
    }

    @Synchronized
    fun snapshot(): String = entries.joinToString("\n")
}
