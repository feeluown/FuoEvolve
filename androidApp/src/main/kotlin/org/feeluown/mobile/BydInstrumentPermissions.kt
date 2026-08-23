package org.feeluown.mobile

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val BYD_INSTRUMENT_COMMON_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_COMMON"
internal const val BYD_INSTRUMENT_GET_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_GET"
internal const val BYD_INSTRUMENT_SET_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_SET"

internal object BydInstrumentPermissionState {
    private val mutableGranted = MutableStateFlow(false)
    val granted: StateFlow<Boolean> = mutableGranted.asStateFlow()

    fun refresh(context: Context): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            BYD_INSTRUMENT_COMMON_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        mutableGranted.value = granted
        return granted
    }

    fun update(granted: Boolean) {
        mutableGranted.value = granted
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun ComponentActivity.requestBydInstrumentPermissionsIfNeeded(requestCode: Int) {
    if (!isBydInstrumentLyricsAvailable()) return
    if (!isPermissionDeclared(BYD_INSTRUMENT_COMMON_PERMISSION)) return

    val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        BydInstrumentPermissionState.update(
            granted || BydInstrumentPermissionState.refresh(this),
        )
    }

    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            BydInstrumentPermissionState.refresh(this@requestBydInstrumentPermissionsIfNeeded)
        }
    })

    if (BydInstrumentPermissionState.refresh(this)) return
    permissionLauncher.launch(BYD_INSTRUMENT_COMMON_PERMISSION)
}

@Suppress("DEPRECATION")
private fun ComponentActivity.isPermissionDeclared(permission: String): Boolean =
    runCatching { packageManager.getPermissionInfo(permission, 0) }.isSuccess
