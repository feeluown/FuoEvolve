package org.feeluown.mobile

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

internal const val BYD_INSTRUMENT_COMMON_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_COMMON"
internal const val BYD_INSTRUMENT_GET_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_GET"
internal const val BYD_INSTRUMENT_SET_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_SET"

internal fun Activity.requestBydInstrumentPermissionsIfNeeded(requestCode: Int) {
    if (!isBydInstrumentLyricsAvailable()) return
    if (!isPermissionDeclared(BYD_INSTRUMENT_COMMON_PERMISSION)) return
    if (
        ContextCompat.checkSelfPermission(this, BYD_INSTRUMENT_COMMON_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    ActivityCompat.requestPermissions(
        this,
        arrayOf(BYD_INSTRUMENT_COMMON_PERMISSION),
        requestCode,
    )
}

@Suppress("DEPRECATION")
private fun Activity.isPermissionDeclared(permission: String): Boolean =
    runCatching { packageManager.getPermissionInfo(permission, 0) }.isSuccess
