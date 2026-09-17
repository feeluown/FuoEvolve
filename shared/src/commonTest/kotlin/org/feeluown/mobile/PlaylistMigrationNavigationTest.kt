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
}
