package org.feeluown.mobile.desktop

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess

class DesktopMpvFfmFeature : Feature {
    override fun duringSetup(access: Feature.DuringSetupAccess) {
        DesktopMpvFfmDowncalls.uniqueDescriptors.forEach { descriptor ->
            RuntimeForeignAccess.registerForDowncall(descriptor)
        }
    }
}
