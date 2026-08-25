package org.feeluown.mobile

/** App-level navigation capability used by playback without depending on provider-detail owners. */
fun interface TrackNavigationPort {
    fun open(track: MusicTrack)
}

fun createTrackNavigationPort(navigator: AppNavigator): TrackNavigationPort = TrackNavigationPort { track ->
    navigator.navigate(AppRoute.TrackDetail(track.toNavigationTrack()))
}

/**
 * Narrow app-level invalidation capability for cross-feature mutations.
 *
 * Feature owners request the refresh they need without retaining a direct dependency on Home.
 * The composition root supplies Home lazily so constructing collaborators never initializes Home
 * recursively.
 */
interface HomeRefreshPort {
    fun refreshMine()
    fun refreshAll()
}

fun createHomeRefreshPort(home: () -> HomeFeatureController): HomeRefreshPort = object : HomeRefreshPort {
    override fun refreshMine() {
        home().refreshMine()
    }

    override fun refreshAll() {
        home().refreshHome(HomeSection.Recommend)
        home().refreshHome(HomeSection.Music)
        home().refreshMine()
    }
}
