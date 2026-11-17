package com.kanzz.editor

// =====================================
// SHARED TIMELINE CONTROLLER
// Pengendali seluruh timeline CAPCAKE
// =====================================

class SharedTimelineController {

    // Total durasi proyek
    var durationMs: Long = 0L
        private set

    // Posisi playhead
    var positionMs: Long = 0L
        private set

    // Tingkat zoom timeline
    var pixelsPerMs: Float = 0.001f
        private set

    // Posisi scroll timeline
    var offsetPx: Float = 0f
        private set

    // Lebar area timeline
    var viewportWidthPx: Float = 0f
        private set


    // =================================
    // MENGATUR DURASI PROYEK
    // =================================

    fun setDuration(duration: Long) {

        durationMs = duration.coerceAtLeast(0L)

        positionMs = positionMs.coerceIn(
            0L,
            durationMs
        )
    }


    // =================================
    // MENGATUR POSISI PLAYHEAD
    // =================================

    fun setPosition(position: Long) {

        positionMs = position.coerceIn(
            0L,
            durationMs
        )

        updateOffsetFromPosition()
    }


    // =================================
    // MENGATUR LEBAR TIMELINE
    // =================================

    fun setViewportWidth(width: Float) {

        viewportWidthPx = width.coerceAtLeast(0f)

        updateOffsetFromPosition()
    }


    // =================================
    // MENGGESER TIMELINE
    // =================================

    fun scrollBy(distancePx: Float) {

        offsetPx += distancePx

        clampOffset()
    }


    // =================================
    // MENGATUR ZOOM
    // =================================

    fun zoomAt(
        scaleFactor: Float,
        focusX: Float
    ) {

        if (pixelsPerMs <= 0f) return

        // Waktu yang berada di titik fokus
        val focusTime =
            (offsetPx + focusX) / pixelsPerMs

        // Skala baru
        pixelsPerMs =
            (pixelsPerMs * scaleFactor)
                .coerceIn(0.00005f, 0.05f)

        // Pertahankan titik fokus
        offsetPx =
            focusTime * pixelsPerMs - focusX

        clampOffset()
    }


    // =================================
    // KONVERSI WAKTU KE POSISI LAYAR
    // =================================

    fun timeToX(timeMs: Long): Float {

        return timeMs * pixelsPerMs - offsetPx
    }


    // =================================
    // KONVERSI POSISI LAYAR KE WAKTU
    // =================================

    fun xToTime(x: Float): Long {

        return (
            (offsetPx + x) / pixelsPerMs
        ).toLong().coerceIn(
            0L,
            durationMs
        )
    }


    // =================================
    // MEMUSATKAN PLAYHEAD
    // =================================

    fun updateOffsetFromPosition() {

        if (viewportWidthPx <= 0f) return

        offsetPx =
            positionMs * pixelsPerMs -
            viewportWidthPx / 2f

        clampOffset()
    }


    // =================================
    // MEMBATASI SCROLL
    // =================================

    fun clampOffset() {

        val maxOffset = (
            durationMs * pixelsPerMs -
            viewportWidthPx
        ).coerceAtLeast(0f)

        offsetPx = offsetPx.coerceIn(
            0f,
            maxOffset
        )
    }
}
