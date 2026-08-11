package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

class KotlinProviderRepositoryFeatureTest {
    @Test
    fun hidesRedundantNeteaseTopArtistsFeature() = runTest {
        val client = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine { addHandler { respond("{\"code\":200}") } }
            },
        )
        val repository = KotlinProviderRepository(
            http = client,
            credentials = InMemoryProviderCredentialStore(),
        )

        val features = repository.features()

        assertTrue(features.any { it.id == "netease_artist_square" })
        assertFalse(features.any { it.id == "netease_top_artists" })
        client.close()
    }
}
