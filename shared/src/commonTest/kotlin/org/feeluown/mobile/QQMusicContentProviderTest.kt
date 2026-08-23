package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore

class QQMusicContentProviderTest {
    @Test
    fun factoryExposesQQMusicDiscoveryFeatures() = runTest {
        val repo = createFuoProviderRepository(InMemoryProviderCredentialStore())
        repo.updateEnabledProviders(setOf("qqmusic"))

        val ids = repo.features().filter { it.providerId == "qqmusic" }.map { it.id }.toSet()

        assertTrue("qqmusic_toplists" in ids)
        assertTrue("qqmusic_playlist_square" in ids)
        assertTrue("qqmusic_artist_square" in ids)
        assertTrue("qqmusic_new_albums" in ids)
        assertTrue("qqmusic_mv_square" in ids)
    }
}
