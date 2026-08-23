package org.feeluown.mobile;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

final class BydPermissionContext extends ContextWrapper {
    private static final String INSTRUMENT_COMMON = "android.permission.BYDAUTO_INSTRUMENT_COMMON";
    private static final String INSTRUMENT_GET = "android.permission.BYDAUTO_INSTRUMENT_GET";
    private static final String INSTRUMENT_SET = "android.permission.BYDAUTO_INSTRUMENT_SET";

    BydPermissionContext(Context base) {
        super(base);
    }

    private static boolean isBydInstrumentPermission(String permission) {
        return INSTRUMENT_COMMON.equals(permission)
                || INSTRUMENT_GET.equals(permission)
                || INSTRUMENT_SET.equals(permission);
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        if (isBydInstrumentPermission(permission)) return;
        super.enforceCallingOrSelfPermission(permission, message);
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        if (isBydInstrumentPermission(permission)) return PackageManager.PERMISSION_GRANTED;
        return super.checkCallingOrSelfPermission(permission);
    }

    @Override
    public void enforcePermission(String permission, int pid, int uid, String message) {
        if (isBydInstrumentPermission(permission)) return;
        super.enforcePermission(permission, pid, uid, message);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (isBydInstrumentPermission(permission)) return PackageManager.PERMISSION_GRANTED;
        return super.checkPermission(permission, pid, uid);
    }
}
