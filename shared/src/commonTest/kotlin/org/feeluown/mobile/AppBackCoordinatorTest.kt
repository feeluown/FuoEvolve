package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppBackCoordinatorTest {
    @Test
    fun transientTargetsAreDismissedInPriorityOrder() {
        val navigator = AppNavigator()
        val firstActive = MutableStateFlow(true)
        val secondActive = MutableStateFlow(true)
        val dismissed = mutableListOf<String>()
        var routeCloseCount = 0
        val coordinator = AppBackCoordinator(
            navigator = navigator,
            transientTargets = listOf(
                AppBackTarget(firstActive, { firstActive.value }) { dismissed += "first" },
                AppBackTarget(secondActive, { secondActive.value }) { dismissed += "second" },
            ),
            closeRoute = {
                routeCloseCount += 1
                true
            },
        )

        assertTrue(coordinator.hasTransientBackNow)
        assertTrue(coordinator.onBack())
        assertEquals(listOf("first"), dismissed)
        assertEquals(0, routeCloseCount)
    }

    @Test
    fun routeHandlerRunsOnlyAfterTransientUiIsGone() {
        val navigator = AppNavigator().also { it.navigate(AppRoute.Search) }
        val transientActive = MutableStateFlow(false)
        var closedRoute: AppRoute? = null
        val coordinator = AppBackCoordinator(
            navigator = navigator,
            transientTargets = listOf(
                AppBackTarget(transientActive, { transientActive.value }) {},
            ),
            closeRoute = { route ->
                closedRoute = route
                true
            },
        )

        assertFalse(coordinator.hasTransientBackNow)
        assertTrue(coordinator.onBack())
        assertEquals(AppRoute.Search, closedRoute)
    }

    @Test
    fun homeCanLeaveBackUnconsumed() {
        val coordinator = AppBackCoordinator(
            navigator = AppNavigator(),
            transientTargets = emptyList(),
            closeRoute = { false },
        )

        assertFalse(coordinator.hasTransientBackNow)
        assertFalse(coordinator.onBack())
    }
}
