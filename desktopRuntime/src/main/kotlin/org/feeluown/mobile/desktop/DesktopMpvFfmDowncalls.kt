package org.feeluown.mobile.desktop

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout

internal data class DesktopFfmDowncall(
    val symbol: String,
    val descriptor: FunctionDescriptor,
)

internal object DesktopMpvFfmDowncalls {
    private val registered = mutableListOf<DesktopFfmDowncall>()

    private fun downcall(symbol: String, descriptor: FunctionDescriptor): DesktopFfmDowncall =
        DesktopFfmDowncall(symbol, descriptor).also(registered::add)

    val mpvCreate = downcall(
        "fuo_mpv_create",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG),
    )
    val mpvInitialize = downcall(
        "fuo_mpv_initialize",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
    )
    val mpvSetOption = downcall(
        "fuo_mpv_set_option",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )
    val mpvSetProperty = downcall(
        "fuo_mpv_set_property",
        mpvSetOption.descriptor,
    )
    val mpvGetProperty = downcall(
        "fuo_mpv_get_property",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )
    val mpvCommand = downcall(
        "fuo_mpv_command",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
    )
    val mpvObserveProperty = downcall(
        "fuo_mpv_observe_property",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    )
    val mpvWaitObservedEvent = downcall(
        "fuo_mpv_wait_observed_event",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )
    val mpvWakeup = downcall(
        "fuo_mpv_wakeup",
        FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG),
    )
    val mpvDestroy = downcall("fuo_mpv_destroy", mpvWakeup.descriptor)
    val mpvErrorString = downcall(
        "fuo_mpv_error_string",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )

    val mpvCreateSoftwareRenderContext = downcall(
        "fuo_mpv_create_software_render_context",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
    )
    val mpvCreateOpenGlRenderContext = downcall(
        "fuo_mpv_create_opengl_render_context",
        FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
        ),
    )
    val mpvOpenGlRenderContextDisplayKind = downcall(
        "fuo_mpv_opengl_render_context_display_kind",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
    )
    val mpvUpdateRenderContext = downcall(
        "fuo_mpv_update_render_context",
        mpvCreateSoftwareRenderContext.descriptor,
    )
    val mpvCreateOpenGlRenderTarget = downcall(
        "fuo_mpv_create_opengl_render_target",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    val mpvOpenGlRenderTargetFramebuffer = downcall(
        "fuo_mpv_opengl_render_target_framebuffer",
        mpvOpenGlRenderContextDisplayKind.descriptor,
    )
    val mpvRenderOpenGl = downcall(
        "fuo_mpv_render_opengl",
        FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
    )
    val mpvReportSwap = downcall("fuo_mpv_report_swap", mpvWakeup.descriptor)
    val mpvDestroyOpenGlRenderTarget = downcall(
        "fuo_mpv_destroy_opengl_render_target",
        mpvWakeup.descriptor,
    )
    val mpvCreateD3D11RenderTarget = downcall(
        "fuo_mpv_create_d3d11_render_target",
        FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
        ),
    )
    val mpvD3D11RenderTargetSharedHandle = downcall(
        "fuo_mpv_d3d11_render_target_shared_handle",
        mpvCreateSoftwareRenderContext.descriptor,
    )
    val mpvRenderD3D11 = downcall(
        "fuo_mpv_render_d3d11",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
    )
    val mpvDestroyD3D11RenderTarget = downcall(
        "fuo_mpv_destroy_d3d11_render_target",
        mpvWakeup.descriptor,
    )
    val mpvFreeOpenGlRenderContext = downcall(
        "fuo_mpv_free_opengl_render_context",
        mpvWakeup.descriptor,
    )
    val mpvCreateIoSurfaceRenderContext = downcall(
        "fuo_mpv_create_iosurface_render_context",
        mpvCreateSoftwareRenderContext.descriptor,
    )
    val mpvCreateIoSurfaceRenderTarget = downcall(
        "fuo_mpv_create_iosurface_render_target",
        mpvCreateD3D11RenderTarget.descriptor,
    )
    val mpvIoSurfaceRenderTargetPointer = downcall(
        "fuo_mpv_iosurface_render_target_pointer",
        mpvCreateSoftwareRenderContext.descriptor,
    )
    val mpvRenderIoSurface = downcall(
        "fuo_mpv_render_iosurface",
        mpvRenderD3D11.descriptor,
    )
    val mpvDestroyIoSurfaceRenderTarget = downcall(
        "fuo_mpv_destroy_iosurface_render_target",
        mpvRenderOpenGl.descriptor,
    )
    val mpvFreeIoSurfaceRenderContext = downcall(
        "fuo_mpv_free_iosurface_render_context",
        mpvWakeup.descriptor,
    )
    val mpvRenderSoftware = downcall(
        "fuo_mpv_render_software",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )
    val mpvFreeRenderContext = downcall("fuo_mpv_free_render_context", mpvWakeup.descriptor)

    val d3D11TextureCreate = downcall(
        "fuo_d3d11_texture_create",
        mpvCreateOpenGlRenderTarget.descriptor,
    )
    val d3D11TextureSharedHandle = downcall(
        "fuo_d3d11_texture_shared_handle",
        mpvCreateSoftwareRenderContext.descriptor,
    )
    val d3D11TextureUpload = downcall(
        "fuo_d3d11_texture_upload",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )
    val d3D11TextureDestroy = downcall("fuo_d3d11_texture_destroy", mpvWakeup.descriptor)

    val all: List<DesktopFfmDowncall>
        get() = registered.toList()
}
