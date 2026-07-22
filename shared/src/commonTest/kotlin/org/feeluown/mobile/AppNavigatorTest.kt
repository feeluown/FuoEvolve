package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavigatorTest {
    @Test
    fun navigationOwnsAnOrderedBackStack() {
        val navigator = AppNavigator()

        navigator.navigate(AppRoute.Search)
        navigator.navigate(AppRoute.Track)

        assertEquals(listOf(AppRoute.Home, AppRoute.Search, AppRoute.Track), navigator.backStack.value)
        assertTrue(navigator.pop(AppRoute.Track))
        assertEquals(AppRoute.Search, navigator.currentRoute)
        assertTrue(navigator.pop())
        assertEquals(listOf(AppRoute.Home), navigator.backStack.value)
        assertFalse(navigator.pop())
    }

    @Test
    fun poppingAParentAlsoRemovesItsChildRoutes() {
        val navigator = AppNavigator()
        navigator.navigate(AppRoute.Settings)
        navigator.navigate(AppRoute.DebugLogs)

        navigator.pop(AppRoute.Settings)

        assertEquals(listOf(AppRoute.Home), navigator.backStack.value)
    }
}
