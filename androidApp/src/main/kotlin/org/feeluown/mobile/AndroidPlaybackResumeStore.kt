package org.feeluown.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class AndroidPlaybackResumeSnapshot(
    val plan: PlaybackPlan,
    val currentTrack: MusicTrack,
    val positionMs: Long,
    val durationMs: Long,
    val playbackParts: List<PlaybackPart>,
    val currentPartIndex: Int,
) {
    fun toPlaybackState(): PlaybackState {
        val normalizedDuration = durationMs.takeIf { it > 0L } ?: currentTrack.durationMs ?: 0L
        val normalizedPosition = positionMs.coerceAtLeast(0L).let { position ->
            normalizedDuration.takeIf { it > 0L }?.let(position::coerceAtMost) ?: position
        }
        val normalizedPartIndex = currentPartIndex.takeIf { it in playbackParts.indices } ?: -1
        return PlaybackState(
            status = PlayerStatus.Paused,
            currentTrack = currentTrack,
            positionMs = normalizedPosition,
            durationMs = normalizedDuration,
            playbackParts = playbackParts,
            currentPartIndex = normalizedPartIndex,
            playbackGeneration = plan.generation,
            lyrics = currentTrack.lyrics,
        )
    }

    fun resumePlan(): PlaybackPlan? {
        val requestIndex = plan.requests.indexOfFirst { request ->
            request.track.id == currentTrack.id
        }
        if (requestIndex < 0) return null

        val remainingRequests = plan.requests.drop(requestIndex).toMutableList()
        val currentRequest = remainingRequests.firstOrNull() ?: return null
        val partIndex = currentPartIndex.takeIf { it in playbackParts.indices }
        val part = partIndex?.let(playbackParts::get)
        remainingRequests[0] = if (part != null) {
            currentRequest.copy(
                resolveTrack = part.toPlaybackTrack(currentRequest.resolveTrack),
                requestedPartIndex = partIndex,
            )
        } else {
            currentRequest
        }
        return plan.copy(requests = remainingRequests)
    }
}

internal class AndroidPlaybackResumeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AndroidPlaybackResumeSnapshot? {
        if (preferences.getInt(KEY_VERSION, 0) != CURRENT_VERSION) return null
        val planJson = preferences.getString(KEY_PLAN, null) ?: return null
        val trackJson = preferences.getString(KEY_TRACK, null) ?: return null
        return runCatching {
            val plan = planJson.toPlaybackPlan()
            if (plan.requests.isEmpty()) return@runCatching null
            val currentTrack = JSONObject(trackJson).toMusicTrack()
            val playbackParts = preferences.getString(KEY_PARTS, null)
                ?.let(::decodeParts)
                .orEmpty()
            AndroidPlaybackResumeSnapshot(
                plan = plan,
                currentTrack = currentTrack,
                positionMs = preferences.getLong(KEY_POSITION_MS, 0L),
                durationMs = preferences.getLong(KEY_DURATION_MS, 0L),
                playbackParts = playbackParts,
                currentPartIndex = preferences.getInt(KEY_PART_INDEX, -1),
            )
        }.getOrNull()
    }

    fun saveSession(plan: PlaybackPlan, state: PlaybackState) {
        val currentTrack = state.currentTrack ?: return
        preferences.edit()
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .putString(KEY_PLAN, plan.toJson())
            .putString(KEY_TRACK, currentTrack.toJsonObject().toString())
            .putString(KEY_PARTS, encodeParts(state.playbackParts))
            .putInt(KEY_PART_INDEX, state.currentPartIndex)
            .putLong(KEY_POSITION_MS, state.positionMs.coerceAtLeast(0L))
            .putLong(KEY_DURATION_MS, state.durationMs.coerceAtLeast(0L))
            .apply()
    }

    fun savePosition(positionMs: Long, durationMs: Long) {
        if (!preferences.contains(KEY_PLAN)) return
        preferences.edit()
            .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
            .putLong(KEY_DURATION_MS, durationMs.coerceAtLeast(0L))
            .apply()
    }

    /**
     * Forces pending apply() writes for the current resumable session to disk. A changing serial
     * guarantees SharedPreferences performs a disk write even when all playback values are already
     * present in memory. Call this at lifecycle boundaries such as pause before the process can be
     * killed.
     */
    fun flush() {
        if (!preferences.contains(KEY_PLAN)) return
        val nextSerial = preferences.getLong(KEY_FLUSH_SERIAL, 0L) + 1L
        preferences.edit()
            .putLong(KEY_FLUSH_SERIAL, nextSerial)
            .commit()
    }

    fun clear() {
        preferences.edit().clear().commit()
    }

    private fun encodeParts(parts: List<PlaybackPart>): String = JSONArray().apply {
        parts.forEach { part ->
            put(
                JSONObject()
                    .put("id", part.id)
                    .put("title", part.title)
                    .put("duration_ms", part.durationMs),
            )
        }
    }.toString()

    private fun decodeParts(raw: String): List<PlaybackPart> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    PlaybackPart(
                        id = item.getString("id"),
                        title = item.optString("title"),
                        durationMs = item.optLong("duration_ms", 0L).takeIf { it > 0L },
                    )
                )
            }
        }
    }

    private companion object {
        private const val CURRENT_VERSION = 1
        private const val PREFS_NAME = "fuo_playback_resume"
        private const val KEY_VERSION = "version"
        private const val KEY_PLAN = "plan"
        private const val KEY_TRACK = "track"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_PARTS = "parts"
        private const val KEY_PART_INDEX = "part_index"
        private const val KEY_FLUSH_SERIAL = "flush_serial"
    }
}

private fun PlaybackPart.toPlaybackTrack(parent: MusicTrack): MusicTrack = parent.copy(
    id = id,
    title = title.ifBlank { parent.title },
    durationMs = durationMs ?: parent.durationMs,
    providerId = id,
)
