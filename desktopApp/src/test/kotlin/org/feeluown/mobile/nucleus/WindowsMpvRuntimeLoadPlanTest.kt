package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsMpvRuntimeLoadPlanTest {
    @Test
    fun loadsSiblingDependenciesBeforeLibmpvAndSkipsBridge() {
        assertEquals(
            listOf(
                "libgcc_s_seh-1.dll",
                "vulkan-1.dll",
                "libmpv-2.dll",
            ),
            windowsMpvRuntimeLoadPlan(
                listOf(
                    "fuoevolve_mpv_jni.dll",
                    "libmpv-2.dll",
                    "vulkan-1.dll",
                    "libgcc_s_seh-1.dll",
                    "README.txt",
                ),
            ),
        )
    }

    @Test
    fun matchesSupportedLibmpvNamesCaseInsensitively() {
        assertEquals(
            listOf("VULKAN-1.DLL", "MPV-2.DLL"),
            windowsMpvRuntimeLoadPlan(
                listOf("FUOEVOLVE_MPV_JNI.DLL", "MPV-2.DLL", "VULKAN-1.DLL"),
            ),
        )
    }

    @Test
    fun returnsEmptyPlanWhenPackagedLibmpvIsMissing() {
        assertEquals(
            emptyList(),
            windowsMpvRuntimeLoadPlan(listOf("fuoevolve_mpv_jni.dll", "vulkan-1.dll")),
        )
    }
}
