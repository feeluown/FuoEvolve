package org.feeluown.mobile

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidPlaybackPlanCodecTest {
    @Test
    fun legacyMinimumScoreMigratesToStrictnessPreset() {
        val legacyJson = JSONObject(plan().toJson())
        val request = legacyJson.getJSONArray("requests").getJSONObject(0)
        request.remove("replacement_strictness")
        request.put("replacement_min_score", 0.75)

        val restored = legacyJson.toString().toPlaybackPlan().requests.single()

        assertEquals(SmartReplacementStrictness.Strict, restored.smartReplacementStrictness)
        assertEquals(0.70, restored.smartReplacementMinScore)
    }

    @Test
    fun explicitStrictnessWinsOverLegacyMinimumScore() {
        val json = JSONObject(plan().toJson())
        val request = json.getJSONArray("requests").getJSONObject(0)
        request.put("replacement_strictness", SmartReplacementStrictness.Relaxed.name)
        request.put("replacement_min_score", 0.90)

        val restored = json.toString().toPlaybackPlan().requests.single()

        assertEquals(SmartReplacementStrictness.Relaxed, restored.smartReplacementStrictness)
        assertEquals(0.45, restored.smartReplacementMinScore)
    }

    private fun plan(): PlaybackPlan = PlaybackPlan(
        generation = 1,
        requests = listOf(
            PlaybackRequest(
                track = MusicTrack(
                    id = "netease:1",
                    title = "Song",
                    artists = "Artist",
                    album = "Album",
                    source = "netease",
                    sourceType = TrackSourceType.Provider,
                ),
            ),
        ),
    )
}
