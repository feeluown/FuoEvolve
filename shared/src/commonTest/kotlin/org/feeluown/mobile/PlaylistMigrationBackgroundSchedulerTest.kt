package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistMigrationBackgroundSchedulerTest {
    private val source = MigrationPlaylist("source", "收藏", "netease")
    private val sourceTrack = MigrationTrack("netease:1", "第一首", "歌手", providerId = "netease")
    private val targetTrack = MigrationTrack("qq:1", "第一首", "歌手", providerId = "qqmusic")

    @Test
    fun conversionStageStopsBeforeReviewAndWriting() {
        assertTrue(PlaylistMigrationBackgroundStage.Conversion.accepts(MigrationPhase.Loading))
        assertTrue(PlaylistMigrationBackgroundStage.Conversion.accepts(MigrationPhase.Matching))
        assertFalse(PlaylistMigrationBackgroundStage.Conversion.accepts(MigrationPhase.Review))
        assertFalse(PlaylistMigrationBackgroundStage.Conversion.accepts(MigrationPhase.Destination))
        assertFalse(PlaylistMigrationBackgroundStage.Conversion.accepts(MigrationPhase.Writing))

        assertFalse(PlaylistMigrationBackgroundStage.Writing.accepts(MigrationPhase.Review))
        assertFalse(PlaylistMigrationBackgroundStage.Writing.accepts(MigrationPhase.Destination))
        assertTrue(PlaylistMigrationBackgroundStage.Writing.accepts(MigrationPhase.Writing))
    }

    @Test
    fun reviewCompletionOpensExactTaskReview() {
        val task = PlaylistMigrationTask(
            id = "migration-123",
            source = source,
            targetProviderId = "qqmusic",
            phase = MigrationPhase.Review,
            entries = listOf(
                MigrationEntry(
                    position = 0,
                    source = sourceTrack,
                    selected = targetTrack,
                    status = MigrationTrackStatus.Matched,
                ),
            ),
            sourceLoaded = true,
        )

        val progress = task.backgroundProgress(PlaylistMigrationBackgroundStage.Conversion)

        assertEquals("migration-123", progress.taskId)
        assertEquals("找歌完成，等待确认", progress.title)
        assertEquals(PlaylistMigrationBackgroundStage.Conversion, progress.stage)
        assertEquals(PlaylistMigrationOpenTarget.Review, progress.openTarget)
        assertTrue(progress.terminal)
    }

    @Test
    fun writingCopyUsesSuccessfullyWrittenCountWhileSystemProgressIncludesFailures() {
        val task = PlaylistMigrationTask(
            id = "migration-456",
            source = source,
            targetProviderId = "qqmusic",
            phase = MigrationPhase.Writing,
            entries = listOf(
                MigrationEntry(
                    position = 0,
                    source = sourceTrack,
                    selected = targetTrack,
                    status = MigrationTrackStatus.Added,
                ),
                MigrationEntry(
                    position = 1,
                    source = sourceTrack.copy(id = "netease:2"),
                    selected = targetTrack.copy(id = "qq:2"),
                    status = MigrationTrackStatus.Failed,
                ),
            ),
            sourceLoaded = true,
            destination = MigrationPlaylist("target", "收藏", "qqmusic"),
        )

        val progress = task.backgroundProgress(PlaylistMigrationBackgroundStage.Writing)

        assertEquals("已迁移 1 / 2", progress.detail)
        assertEquals(2, progress.completed)
        assertEquals(2, progress.total)
        assertFalse(progress.terminal)
    }

    @Test
    fun completedWritingOpensExactTaskResult() {
        val task = PlaylistMigrationTask(
            id = "migration-789",
            source = source,
            targetProviderId = "qqmusic",
            phase = MigrationPhase.Complete,
            entries = listOf(
                MigrationEntry(
                    position = 0,
                    source = sourceTrack,
                    selected = targetTrack,
                    status = MigrationTrackStatus.Added,
                ),
            ),
            sourceLoaded = true,
            destination = MigrationPlaylist("target", "收藏", "qqmusic"),
        )

        val progress = task.backgroundProgress(PlaylistMigrationBackgroundStage.Writing)

        assertEquals(PlaylistMigrationOpenTarget.Result, progress.openTarget)
        assertTrue(progress.terminal)
    }
}
