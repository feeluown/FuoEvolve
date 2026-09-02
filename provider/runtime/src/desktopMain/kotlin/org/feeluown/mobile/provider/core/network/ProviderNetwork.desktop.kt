package org.feeluown.mobile.provider.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal actual fun createProviderHttpClient(): HttpClient = HttpClient(OkHttp) {
    installProviderClientDefaults()
    engine {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 8
        }
        preconfigured = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(dispatcher)
            .build()
    }
}

internal actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()
