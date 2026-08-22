package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderDetailBindingCompatibilityTest {
    @Test
    fun providerDetailUiStatesKeepStableDefaultConstructors() {
        ProviderFeatureDetailUiState().also {
            assertNull(it.feature)
            assertTrue(it.tracks.isEmpty())
            assertFalse(it.isLoading)
        }
        ProviderPlaylistDetailUiState().also {
            assertNull(it.playlist)
            assertNull(it.category)
            assertTrue(it.tracks.isEmpty())
        }
        ProviderTrackDetailUiState().also {
            assertNull(it.track)
            assertTrue(it.similarTracks.isEmpty())
            assertTrue(it.comments.isEmpty())
        }
        ProviderMediaItemDetailUiState().also {
            assertNull(it.item)
            assertTrue(it.tracks.isEmpty())
            assertTrue(it.albums.isEmpty())
        }
        ProviderVideoDetailUiState().also {
            assertNull(it.video)
            assertNull(it.payload)
            assertFalse(it.isFullscreen)
        }
    }
}
