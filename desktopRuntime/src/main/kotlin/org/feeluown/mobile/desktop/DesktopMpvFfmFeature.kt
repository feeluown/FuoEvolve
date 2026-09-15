package org.feeluown.mobile.desktop

import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess

class DesktopMpvFfmFeature : Feature {
    override fun duringSetup(access: Feature.DuringSetupAccess) {
        (DesktopMpvFfmDowncalls.all + DesktopAudioCaptureFfmDowncalls.all).forEach { downcall ->
            RuntimeForeignAccess.registerForDowncall(downcall.descriptor)
        }
    }
}
