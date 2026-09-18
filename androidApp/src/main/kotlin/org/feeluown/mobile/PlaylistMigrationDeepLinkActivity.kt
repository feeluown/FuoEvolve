package org.feeluown.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Internal notification trampoline that keeps migration routing out of MainActivity intent parsing. */
class PlaylistMigrationDeepLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openMigration(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openMigration(intent)
    }

    private fun openMigration(intent: Intent?) {
        val uri = intent?.data
        val taskId = uri
            ?.takeIf { it.scheme == SCHEME && it.host == HOST }
            ?.pathSegments
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        val target = uri?.getQueryParameter(TARGET_QUERY).orEmpty()
        if (taskId.isNotEmpty() && target.isNotEmpty()) {
            openPlaylistMigrationFromNotification(taskId, target)
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
        )
        finish()
    }

    private companion object {
        const val SCHEME = "fuo"
        const val HOST = "playlist-migration"
        const val TARGET_QUERY = "target"
    }
}
