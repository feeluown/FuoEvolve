package org.feeluown.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberSupportedLinksSettingsAction(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        {
            val packageUri = Uri.parse("package:${context.packageName}")
            val primaryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            }
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            val intent = primaryIntent.takeIf { it.resolveActivity(context.packageManager) != null } ?: fallbackIntent
            runCatching { context.startActivity(intent) }
            Unit
        }
    }
}
