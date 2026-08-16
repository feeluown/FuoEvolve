package org.feeluown.mobile

import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val PLATFORM_SETTINGS_PREFERENCES = "fuo_evolve_platform_settings"
private const val PREDICTIVE_BACK_ENABLED_KEY = "predictive_back_enabled"

object AndroidPredictiveBackPreference {
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private val mutableEnabled = mutableStateOf(true)
    val enabled: State<Boolean>
        get() = mutableEnabled

    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        val preferences = context.applicationContext.getSharedPreferences(
            PLATFORM_SETTINGS_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        mutableEnabled.value = preferences.getBoolean(PREDICTIVE_BACK_ENABLED_KEY, true)
        initialized = true
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        mutableEnabled.value = enabled
        context.applicationContext.getSharedPreferences(
            PLATFORM_SETTINGS_PREFERENCES,
            Context.MODE_PRIVATE,
        ).edit()
            .putBoolean(PREDICTIVE_BACK_ENABLED_KEY, enabled)
            .apply()
    }
}

@Composable
internal actual fun rememberPredictiveBackPreference(): PredictiveBackPreference {
    val context = LocalContext.current.applicationContext
    remember(context) {
        AndroidPredictiveBackPreference.initialize(context)
        Unit
    }
    val enabled by AndroidPredictiveBackPreference.enabled
    return PredictiveBackPreference(
        isSupported = AndroidPredictiveBackPreference.isSupported,
        enabled = enabled,
        onEnabledChange = { AndroidPredictiveBackPreference.setEnabled(context, it) },
    )
}

@Composable
internal actual fun PlatformLegacyBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}
