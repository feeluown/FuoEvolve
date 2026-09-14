package org.feeluown.mobile.nucleus;

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge;
import dev.nucleusframework.window.tao.ffi.NativeTaoEglBridge;

/** JVM-visible bridge for Tao's native handles used by the libmpv renderer. */
public final class NucleusVideoNativeInterop {
    private NucleusVideoNativeInterop() {}

    public static long[] nativeLinuxHandles(long taoHandle) {
        return NativeTaoBridge.nativeLinuxHandles(taoHandle);
    }

    public static long taoGetProcAddressFunctionPointer() {
        return NativeTaoEglBridge.nativeGetProcAddrFunctionPointer();
    }
}
