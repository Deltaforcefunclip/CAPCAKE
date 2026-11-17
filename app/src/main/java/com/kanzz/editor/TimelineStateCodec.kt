package com.kanzz.editor

import org.json.JSONArray
import org.json.JSONObject

/** Versioned persistence for editable video and audio timeline data. */
object TimelineStateCodec {
    private const val VERSION = 1

    fun encodeVideo(manager: VideoClipManager): String {
        val root = JSONObject().put("version", VERSION)
        val clips = JSONArray()
        manager.clips.forEach { clip ->
            clips.put(JSONObject().apply {
                put("id", clip.id)
                put("sourceUri", clip.sourceUri)
                put("sourceStartMs", clip.sourceStartMs)
                put("durationMs", clip.durationMs)
                put("volume", clip.volume.toDouble())
                put("speed", clip.speed.toDouble())
                put("opacity", clip.opacity.toDouble())
                put("filterName", clip.filterName)
                put("brightness", clip.brightness.toDouble())
                put("contrast", clip.contrast.toDouble())
                put("saturation", clip.saturation.toDouble())
            })
        }
        val transitions = JSONArray()
        manager.transitions.forEach { transition ->
            transitions.put(JSONObject().apply {
                put("id", transition.id)
                put("leftClipId", transition.leftClipId)
                put("rightClipId", transition.rightClipId)
                put("type", transition.type.name)
                put("durationMs", transition.durationMs)
            })
        }
        root.put("clips", clips)
        root.put("transitions", transitions)
        return root.toString()
    }

    fun restoreVideo(json: String?, manager: VideoClipManager) {
        manager.clips.clear()
        manager.transitions.clear()
        if (json.isNullOrBlank()) return
        try {
            val root = JSONObject(json)
            val clips = root.optJSONArray("clips") ?: JSONArray()
            for (i in 0 until clips.length()) {
                val item = clips.getJSONObject(i)
                manager.clips.add(VideoClip(
                    id = item.optString("id"),
                    sourceUri = item.getString("sourceUri"),
                    sourceStartMs = item.optLong("sourceStartMs"),
                    durationMs = item.optLong("durationMs"),
                    volume = item.optDouble("volume", 1.0).toFloat(),
                    speed = item.optDouble("speed", 1.0).toFloat(),
                    opacity = item.optDouble("opacity", 1.0).toFloat(),
                    filterName = item.optString("filterName", "None"),
                    brightness = item.optDouble("brightness", 0.0).toFloat(),
                    contrast = item.optDouble("contrast", 1.0).toFloat(),
                    saturation = item.optDouble("saturation", 1.0).toFloat()
                ))
            }
            val transitions = root.optJSONArray("transitions") ?: JSONArray()
            for (i in 0 until transitions.length()) {
                val item = transitions.getJSONObject(i)
                val type = runCatching {
                    VideoTransition.Type.valueOf(item.optString("type", "NONE"))
                }.getOrDefault(VideoTransition.Type.NONE)
                manager.transitions.add(VideoTransition(
                    id = item.optString("id"),
                    leftClipId = item.getString("leftClipId"),
                    rightClipId = item.getString("rightClipId"),
                    type = type,
                    durationMs = item.optLong("durationMs", 500L)
                ))
            }
            manager.recalculateTimeline()
        } catch (_: Exception) {
            manager.clips.clear()
            manager.transitions.clear()
        }
    }

    fun encodeAudio(manager: AudioClipManager): String {
        val root = JSONObject().put("version", VERSION)
        val clips = JSONArray()
        manager.clips.forEach { clip ->
            val laneIndex = manager.lanes.indexOfFirst { lane -> lane.clips.any { it.id == clip.id } }
            clips.put(JSONObject().apply {
                put("laneIndex", laneIndex)
                put("id", clip.id)
                put("startTimeMs", clip.startTimeMs)
                put("durationMs", clip.durationMs)
                put("filePath", clip.filePath)
                put("sourceStartMs", clip.sourceStartMs)
                put("name", clip.name)
                put("volume", clip.volume.toDouble())
            })
        }
        root.put("clips", clips)
        return root.toString()
    }

    fun restoreAudio(json: String?, manager: AudioClipManager) {
        manager.clear()
        if (json.isNullOrBlank()) return
        try {
            val clips = JSONObject(json).optJSONArray("clips") ?: JSONArray()
            for (i in 0 until clips.length()) {
                val item = clips.getJSONObject(i)
                val clip = AudioClip(
                    id = item.optString("id"),
                    startTimeMs = item.optLong("startTimeMs"),
                    durationMs = item.optLong("durationMs"),
                    filePath = item.getString("filePath"),
                    sourceStartMs = item.optLong("sourceStartMs"),
                    name = item.optString("name", "Audio"),
                    volume = item.optDouble("volume", 1.0).toFloat()
                )
                val laneIndex = item.optInt("laneIndex", -1)
                if (laneIndex >= 0) {
                    while (manager.lanes.size <= laneIndex) manager.lanes.add(AudioLane())
                    val lane = manager.lanes[laneIndex]
                    if (lane.clips.none { existing ->
                            clip.startTimeMs < existing.endTimeMs &&
                                clip.endTimeMs > existing.startTimeMs
                        }) {
                        lane.clips.add(clip)
                        manager.clips.add(clip)
                    } else {
                        manager.addClip(clip)
                    }
                } else {
                    manager.addClip(clip)
                }
            }
        } catch (_: Exception) {
            manager.clear()
        }
    }
}
