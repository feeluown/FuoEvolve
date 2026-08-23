package org.feeluown.mobile

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

internal const val BYD_INSTRUMENT_COMMON_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_COMMON"
internal const val BYD_INSTRUMENT_GET_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_GET"
internal const val BYD_INSTRUMENT_SET_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_SET"

private val BYD_INSTRUMENT_PERMISSIONS = arrayOf(
    BYD_INSTRUMENT_COMMON_PERMISSION,
    BYD_INSTRUMENT_GET_PERMISSION,
    BYD_INSTRUMENT_SET_PERMISSION,
)

internal fun Activity.requestBydInstrumentPermissionsIfNeeded(requestCode: Int) {
    if (!isBydInstrumentLyricsAvailable()) return
    val missingPermissions = declaredBydInstrumentPermissions()
        .filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        .toTypedArray()
    if (missingPermissions.isEmpty()) return
    ActivityCompat.requestPermissions(this, missingPermissions, requestCode)
}

@Suppress("DEPRECATION")
private fun Activity.declaredBydInstrumentPermissions(): List<String> =
    BYD_INSTRUMENT_PERMISSIONS.filter { permission ->
        runCatching { packageManager.getPermissionInfo(permission, 0) }.isSuccess
    }
