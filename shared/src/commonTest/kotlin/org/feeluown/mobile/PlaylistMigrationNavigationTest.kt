package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistMigrationNavigationTest {
    @Test
    fun migrationIsASecondaryRouteAndReturningPreservesHome() {
        val navigator = AppNavigator()

        navigator.navigate(AppRoute.PlaylistMigration)
        assertEquals(AppRoute.PlaylistMigration, navigator.currentEntry)
        assertEquals(listOf(AppRoute.Home, AppRoute.PlaylistMigration), navigator.backStack.value)

        assertTrue(navigator.pop())
        assertEquals(AppRoute.Home, navigator.currentEntry)
        assertFalse(navigator.pop())
    }

    @Test
    fun repeatedlyOpeningMigrationDoesNotDuplicateRoute() {
        val navigator = AppNavigator()

        navigator.navigate(AppRoute.PlaylistMigration)
        navigator.navigate(AppRoute.PlaylistMigration)

        assertEquals(listOf(AppRoute.Home, AppRoute.PlaylistMigration), navigator.backStack.value)
        assertTrue(navigator.pop(AppRoute.PlaylistMigration))
        assertEquals(AppRoute.Home, navigator.currentEntry)
    }

    @Test
    fun migrationDetailCarriesTaskAndTargetAndBelongsToMigrationRouteKind() {
        val navigator = AppNavigator()
        val detail = AppRoute.PlaylistMigrationDetail(
            taskId = "migration-123",
            target = PlaylistMigrationOpenTarget.Review,
        )

        navigator.navigate(detail)

        assertEquals(detail, navigator.currentEntry)
        assertEquals(AppRoute.PlaylistMigration, navigator.currentRoute)
        assertTrue(navigator.contains(AppRoute.PlaylistMigration))
        assertTrue(navigator.pop(AppRoute.PlaylistMigration))
        assertEquals(AppRoute.Home, navigator.currentEntry)
    }

    @Test
    fun differentMigrationTaskTargetsRemainDistinctNavigationEntries() {
        val navigator = AppNavigator()
        val review = AppRoute.PlaylistMigrationDetail("migration-123", PlaylistMigrationOpenTarget.Review)
        val result = AppRoute.PlaylistMigrationDetail("migration-456", PlaylistMigrationOpenTarget.Result)

        navigator.navigate(review)
        navigator.navigate(result)

        assertEquals(listOf(AppRoute.Home, review, result), navigator.backStack.value)
        assertEquals(result, navigator.currentEntry)
    }
}
