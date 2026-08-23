package org.feeluown.mobile;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

final class BydPermissionContext extends ContextWrapper {
    BydPermissionContext(Context base) {
        super(base);
    }

    private static boolean isBydAutoPermission(String permission) {
        return permission != null && permission.startsWith("android.permission.BYDAUTO_");
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        if (isBydAutoPermission(permission)) return;
        super.enforceCallingOrSelfPermission(permission, message);
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        if (isBydAutoPermission(permission)) return PackageManager.PERMISSION_GRANTED;
        return super.checkCallingOrSelfPermission(permission);
    }

    @Override
    public void enforcePermission(String permission, int pid, int uid, String message) {
        if (isBydAutoPermission(permission)) return;
        super.enforcePermission(permission, pid, uid, message);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (isBydAutoPermission(permission)) return PackageManager.PERMISSION_GRANTED;
        return super.checkPermission(permission, pid, uid);
    }
}
