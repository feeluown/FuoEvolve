package org.feeluown.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Requests the legacy external-storage permission only when saving an update on Android 7–9. */
internal class AppUpdateSavePermissionActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            continueSavingUpdate()
        } else {
            (application as FuoEvolveApplication).appViewModel.showFeedback(
                "未授予存储权限，无法保存安装包到下载文件夹",
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P || hasWriteExternalStoragePermission()) {
            continueSavingUpdate()
            finish()
            return
        }
        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun continueSavingUpdate() {
        (application as FuoEvolveApplication).appUiGraph.settings.saveAppUpdateToDownloads()
    }

    private fun hasWriteExternalStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
}
