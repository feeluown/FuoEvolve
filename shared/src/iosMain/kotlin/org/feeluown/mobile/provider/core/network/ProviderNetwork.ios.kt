package org.feeluown.mobile.provider.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
internal actual fun createProviderHttpClient(): HttpClient = HttpClient(Darwin) {
    installProviderClientDefaults()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentTimeMillis(): Long = time(null) * 1_000
