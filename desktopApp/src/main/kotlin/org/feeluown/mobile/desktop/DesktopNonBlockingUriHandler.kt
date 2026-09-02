package org.feeluown.mobile.desktop

import androidx.compose.ui.platform.UriHandler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Desktop URI launching may synchronously wait for the OS browser launcher (notably xdg-open on
 * Linux). Keep that work off the Compose UI dispatcher so opening OAuth/release-note links cannot
 * freeze the application window.
 */
internal class DesktopNonBlockingUriHandler(
    private val delegate: UriHandler,
    private val executor: ExecutorService = createUriExecutor(),
) : UriHandler, AutoCloseable {
    override fun openUri(uri: String) {
        executor.execute {
            runCatching { delegate.openUri(uri) }
                .onFailure { error ->
                    System.err.println(
                        "FuoEvolve: failed to open external URI: ${error.message ?: error::class.simpleName}",
                    )
                }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

private fun createUriExecutor(): ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fuoevolve-external-uri").apply { isDaemon = true }
    }
