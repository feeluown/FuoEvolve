package org.feeluown.mobile.nucleus

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun pinnedWindowsRuntimeCanBePreloadedWithoutChangingDllSearchPath() {
        if (!System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)) return
        val runtimeDir = System.getenv("FUOEVOLVE_NUCLEUS_LIBMPV_RUNTIME_DIR")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return
        val runtimeFiles = runtimeDir.listFiles().orEmpty().filter(File::isFile)
        val filesByName = runtimeFiles.associateBy { file -> file.name.lowercase() }
        val loadPlan = windowsMpvRuntimeLoadPlan(runtimeFiles.map(File::getName))
        assertTrue(loadPlan.isNotEmpty(), "Pinned Windows runtime is missing libmpv")

        loadPlan.dropLast(1).forEach { name ->
            val dependency = filesByName.getValue(name.lowercase())
            runCatching { System.load(dependency.absolutePath) }
        }
        val libmpv = filesByName.getValue(loadPlan.last().lowercase())
        System.load(libmpv.absolutePath)
    }
}
