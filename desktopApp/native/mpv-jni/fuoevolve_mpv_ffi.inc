/* Stable C ABI consumed through Java FFM. The legacy JNI exports remain in this translation unit
 * temporarily for source compatibility, but desktop Kotlin code no longer calls them. */
#include <stdarg.h>

#if defined(_WIN32)
#define FUO_FFI_EXPORT __declspec(dllexport)
#else
#define FUO_FFI_EXPORT __attribute__((visibility("default")))
#endif

#define FUO_FFI_EVENT_BUFFER_FALLBACK "queue-overflow"

static int fuo_ffi_write_text(char *buffer, size_t capacity, const char *format, ...) {
    if (buffer == NULL || capacity == 0u || format == NULL) return 0;
    va_list arguments;
    va_start(arguments, format);
    int written = vsnprintf(buffer, capacity, format, arguments);
    va_end(arguments);
    if (written < 0) return 0;
    if ((size_t)written >= capacity) {
        size_t fallback_length = strlen(FUO_FFI_EVENT_BUFFER_FALLBACK);
        if (fallback_length + 1u > capacity) return 0;
        memcpy(buffer, FUO_FFI_EVENT_BUFFER_FALLBACK, fallback_length + 1u);
        return (int)fallback_length;
    }
    return written;
}

FUO_FFI_EXPORT int64_t fuo_mpv_create(void) {
    setlocale(LC_NUMERIC, "C");
    return (int64_t)(intptr_t)mpv_create();
}

FUO_FFI_EXPORT int fuo_mpv_initialize(int64_t handle_value) {
    mpv_handle *handle = (mpv_handle *)(intptr_t)handle_value;
    return handle == NULL ? MPV_ERROR_INVALID_PARAMETER : mpv_initialize(handle);
}

FUO_FFI_EXPORT int fuo_mpv_set_option(
    int64_t handle_value,
    const char *name,
    const char *value
) {
    mpv_handle *handle = (mpv_handle *)(intptr_t)handle_value;
    if (handle == NULL || name == NULL || value == NULL) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_set_option_string(handle, name, value);
}

FUO_FFI_EXPORT int fuo_mpv_set_property(
    int64_t handle_value,
    const char *name,
    const char *value
) {
    mpv_handle *handle = (mpv_handle *)(intptr_t)handle_value;
    if (handle == NULL || name == NULL || value == NULL) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_set_property_string(handle, name, value);
}

/* Returns 0 for no value, a positive byte length on success, or the negative required byte length
 * when the caller-provided buffer is too small. */
FUO_FFI_EXPORT int fuo_mpv_get_property(
    int64_t handle_value,
    const char *name,
    char *buffer,
    size_t capacity
) {
    mpv_handle *handle = (mpv_handle *)(intptr_t)handle_value;
    if (handle == NULL || name == NULL) return 0;
    char *value = mpv_get_property_string(handle, name);
    if (value == NULL) return 0;
    size_t length = strlen(value);
    if (buffer == NULL || capacity <= length) {
        mpv_free(value);
        return length > (size_t)INT32_MAX ? INT32_MIN + 1 : -(int)length;
    }
    memcpy(buffer, value, length + 1u);
    mpv_free(value);
    return (int)length;
}

FUO_FFI_EXPORT int fuo_mpv_command(int64_t handle_value, const char *const *args) {
    mpv_handle *handle = (mpv_handle *)(intptr_t)handle_value;
    if (handle == NULL || args == NULL) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_command(handle, args);
}

FUO_FFI_EXPORT int fuo_mpv_observe_property(
    int64_t handle_value,
    uint64_t reply_userdata,
    const char *name
) {
    mpv_handle *handle = (mpv_handle *)(intptr_t)handle_value;
    if (handle == NULL || reply_userdata == 0u || name == NULL) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_observe_property(handle, reply_userdata, name, MPV_FORMAT_STRING);
}

FUO_FFI_EXPORT int fuo_mpv_wait_observed_event(
    int64_t handle_value,
    double timeout_seconds,
    char *buffer,
    size_t capacity
) {
    (void)timeout_seconds;
    mpv_handle *handle = (mpv_handle *)(intptr_t)handle_value;
    if (handle == NULL || buffer == NULL || capacity == 0u) return 0;

    const mpv_event *event = mpv_wait_event(handle, -1.0);
    if (event == NULL || event->event_id == MPV_EVENT_NONE) return 0;

    switch (event->event_id) {
        case MPV_EVENT_SHUTDOWN:
            fuo_observed_pending_entry_id = -1;
            fuo_observed_active_entry_id = -1;
            return fuo_ffi_write_text(buffer, capacity, "shutdown");
        case MPV_EVENT_START_FILE: {
            const mpv_event_start_file *start = (const mpv_event_start_file *)event->data;
            if (start == NULL) return 0;
            fuo_observed_pending_entry_id = start->playlist_entry_id;
            fuo_observed_active_entry_id = -1;
            return fuo_ffi_write_text(
                buffer,
                capacity,
                "start:%lld",
                (long long)start->playlist_entry_id
            );
        }
        case MPV_EVENT_FILE_LOADED: {
            int64_t current_entry_id = -1;
            if (
                fuo_observed_pending_entry_id >= 0 &&
                fuo_mpv_current_playlist_entry_id(handle, &current_entry_id) &&
                current_entry_id == fuo_observed_pending_entry_id
            ) {
                fuo_observed_active_entry_id = current_entry_id;
                fuo_observed_pending_entry_id = -1;
            }
            return fuo_ffi_write_text(buffer, capacity, "loaded");
        }
        case MPV_EVENT_PLAYBACK_RESTART:
            return fuo_ffi_write_text(buffer, capacity, "restart");
        case MPV_EVENT_END_FILE: {
            const mpv_event_end_file *end = (const mpv_event_end_file *)event->data;
            if (end == NULL) return 0;
            if (end->playlist_entry_id == fuo_observed_pending_entry_id) {
                fuo_observed_pending_entry_id = -1;
            }
            if (end->playlist_entry_id == fuo_observed_active_entry_id) {
                fuo_observed_active_entry_id = -1;
            }
            return fuo_ffi_write_text(
                buffer,
                capacity,
                "end:%lld:%d:%d",
                (long long)end->playlist_entry_id,
                end->reason,
                end->error
            );
        }
        case MPV_EVENT_PROPERTY_CHANGE: {
            int64_t current_entry_id = -1;
            if (
                fuo_observed_active_entry_id < 0 ||
                !fuo_mpv_current_playlist_entry_id(handle, &current_entry_id) ||
                current_entry_id != fuo_observed_active_entry_id
            ) {
                return 0;
            }
            const mpv_event_property *property = (const mpv_event_property *)event->data;
            const char *value = "";
            if (
                property != NULL &&
                property->format == MPV_FORMAT_STRING &&
                property->data != NULL
            ) {
                const char *native_value = *(char **)property->data;
                if (native_value != NULL) value = native_value;
            }
            return fuo_ffi_write_text(
                buffer,
                capacity,
                "property:%llu:%s",
                (unsigned long long)event->reply_userdata,
                value
            );
        }
        case MPV_EVENT_QUEUE_OVERFLOW:
            fuo_observed_activate_current_after_overflow(handle);
            return fuo_ffi_write_text(buffer, capacity, "queue-overflow");
        default:
            return 0;
    }
}

FUO_FFI_EXPORT void fuo_mpv_wakeup(int64_t handle_value) {
    mpv_handle *handle = (mpv_handle *)(intptr_t)handle_value;
    if (handle != NULL) mpv_wakeup(handle);
}

FUO_FFI_EXPORT void fuo_mpv_destroy(int64_t handle_value) {
    mpv_handle *handle = (mpv_handle *)(intptr_t)handle_value;
    if (handle != NULL) mpv_terminate_destroy(handle);
}

FUO_FFI_EXPORT int fuo_mpv_error_string(int error, char *buffer, size_t capacity) {
    const char *value = mpv_error_string(error);
    if (value == NULL || buffer == NULL || capacity == 0u) return 0;
    size_t length = strlen(value);
    if (capacity <= length) return -(int)length;
    memcpy(buffer, value, length + 1u);
    return (int)length;
}

FUO_FFI_EXPORT int64_t fuo_mpv_create_software_render_context(int64_t handle_value) {
    return (int64_t)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateSoftwareRenderContext(
        NULL,
        NULL,
        (jlong)handle_value
    );
}

FUO_FFI_EXPORT int64_t fuo_mpv_create_opengl_render_context(
    int64_t handle_value,
    int direct_hardware,
    int native_display_kind,
    int64_t native_display_value,
    int64_t tao_get_proc_address_value
) {
    return (int64_t)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateOpenGlRenderContext(
        NULL,
        NULL,
        (jlong)handle_value,
        direct_hardware ? JNI_TRUE : JNI_FALSE,
        (jint)native_display_kind,
        (jlong)native_display_value,
        (jlong)tao_get_proc_address_value
    );
}

FUO_FFI_EXPORT int fuo_mpv_opengl_render_context_display_kind(int64_t render_context_value) {
    return (int)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeOpenGlRenderContextDisplayKind(
        NULL,
        NULL,
        (jlong)render_context_value
    );
}

FUO_FFI_EXPORT int64_t fuo_mpv_update_render_context(int64_t render_context_value) {
    return (int64_t)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeUpdateRenderContext(
        NULL,
        NULL,
        (jlong)render_context_value
    );
}

FUO_FFI_EXPORT int64_t fuo_mpv_create_opengl_render_target(int width, int height) {
    return (int64_t)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateOpenGlRenderTarget(
        NULL,
        NULL,
        (jint)width,
        (jint)height
    );
}

FUO_FFI_EXPORT int fuo_mpv_opengl_render_target_framebuffer(int64_t render_target_value) {
    return (int)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeOpenGlRenderTargetFramebuffer(
        NULL,
        NULL,
        (jlong)render_target_value
    );
}

FUO_FFI_EXPORT void fuo_mpv_render_opengl(int64_t render_context_value, int64_t render_target_value) {
    Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeRenderOpenGl(
        NULL,
        NULL,
        (jlong)render_context_value,
        (jlong)render_target_value
    );
}

FUO_FFI_EXPORT void fuo_mpv_report_swap(int64_t render_context_value) {
    Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeReportSwap(
        NULL,
        NULL,
        (jlong)render_context_value
    );
}

FUO_FFI_EXPORT void fuo_mpv_destroy_opengl_render_target(int64_t render_target_value) {
    Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeDestroyOpenGlRenderTarget(
        NULL,
        NULL,
        (jlong)render_target_value
    );
}

FUO_FFI_EXPORT int64_t fuo_mpv_create_d3d11_render_target(
    int64_t render_context_value,
    int width,
    int height
) {
    return (int64_t)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateD3D11RenderTarget(
        NULL,
        NULL,
        (jlong)render_context_value,
        (jint)width,
        (jint)height
    );
}

FUO_FFI_EXPORT int64_t fuo_mpv_d3d11_render_target_shared_handle(int64_t target_value) {
    return (int64_t)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeD3D11RenderTargetSharedHandle(
        NULL,
        NULL,
        (jlong)target_value
    );
}

FUO_FFI_EXPORT int fuo_mpv_render_d3d11(int64_t render_context_value, int64_t target_value) {
#if defined(_WIN32)
    fuo_gl_render_context *renderer = gl_render_context_from_jlong((jlong)render_context_value);
    fuo_windows_mpv_shared_target *target =
        (fuo_windows_mpv_shared_target *)(intptr_t)target_value;
    if (
        renderer == NULL || renderer->mpv == NULL || target == NULL ||
        target->pbuffer == NULL || !fuo_ensure_windows_egl(renderer)
    ) {
        return 0;
    }
    fuo_egl_display current_display = fuo_windows_egl.get_current_display();
    fuo_egl_context current_context = fuo_windows_egl.get_current_context();
    fuo_egl_surface host_draw = fuo_windows_egl.get_current_surface(FUO_EGL_DRAW);
    fuo_egl_surface host_read = fuo_windows_egl.get_current_surface(FUO_EGL_READ);
    if (
        current_display != target->display || current_context != target->context ||
        host_draw == NULL || host_read == NULL
    ) {
        return 0;
    }
    if (!fuo_windows_egl.make_current(
            target->display,
            target->pbuffer,
            target->pbuffer,
            target->context
        )) {
        return 0;
    }
    mpv_opengl_fbo fbo = {
        .fbo = 0,
        .w = target->width,
        .h = target->height,
        .internal_format = (int)FUO_GL_RGBA8,
    };
    int flip_y = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    mpv_render_context_render(renderer->mpv, params);
    if (ensure_gl_api()) global_gl_api.flush();
    return fuo_windows_egl.make_current(
        current_display,
        host_draw,
        host_read,
        current_context
    ) ? 1 : 0;
#else
    (void)render_context_value;
    (void)target_value;
    return 0;
#endif
}

FUO_FFI_EXPORT void fuo_mpv_destroy_d3d11_render_target(int64_t target_value) {
    Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeDestroyD3D11RenderTarget(
        NULL,
        NULL,
        (jlong)target_value
    );
}

FUO_FFI_EXPORT void fuo_mpv_free_opengl_render_context(int64_t render_context_value) {
    Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeFreeOpenGlRenderContext(
        NULL,
        NULL,
        (jlong)render_context_value
    );
}

FUO_FFI_EXPORT int64_t fuo_mpv_create_iosurface_render_context(int64_t handle_value) {
    return (int64_t)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateIoSurfaceRenderContext(
        NULL,
        NULL,
        (jlong)handle_value
    );
}

FUO_FFI_EXPORT int64_t fuo_mpv_create_iosurface_render_target(
    int64_t render_context_value,
    int width,
    int height
) {
    return (int64_t)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateIoSurfaceRenderTarget(
        NULL,
        NULL,
        (jlong)render_context_value,
        (jint)width,
        (jint)height
    );
}

FUO_FFI_EXPORT int64_t fuo_mpv_iosurface_render_target_pointer(int64_t render_target_value) {
    return (int64_t)Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeIoSurfaceRenderTargetPointer(
        NULL,
        NULL,
        (jlong)render_target_value
    );
}

FUO_FFI_EXPORT int fuo_mpv_render_iosurface(
    int64_t render_context_value,
    int64_t render_target_value
) {
    return Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeRenderIoSurface(
        NULL,
        NULL,
        (jlong)render_context_value,
        (jlong)render_target_value
    ) == JNI_TRUE ? 1 : 0;
}

FUO_FFI_EXPORT void fuo_mpv_destroy_iosurface_render_target(
    int64_t render_context_value,
    int64_t render_target_value
) {
    Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeDestroyIoSurfaceRenderTarget(
        NULL,
        NULL,
        (jlong)render_context_value,
        (jlong)render_target_value
    );
}

FUO_FFI_EXPORT void fuo_mpv_free_iosurface_render_context(int64_t render_context_value) {
    Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeFreeIoSurfaceRenderContext(
        NULL,
        NULL,
        (jlong)render_context_value
    );
}

FUO_FFI_EXPORT int fuo_mpv_render_software(
    int64_t render_context_value,
    int width,
    int height,
    int stride,
    unsigned char *pixels,
    size_t pixel_length
) {
    mpv_render_context *context = (mpv_render_context *)(intptr_t)render_context_value;
    if (context == NULL || width <= 0 || height <= 0 || stride <= 0 || pixels == NULL) {
        return MPV_ERROR_INVALID_PARAMETER;
    }
    int64_t required = (int64_t)stride * (int64_t)height;
    if (required <= 0 || (uint64_t)required > (uint64_t)pixel_length) {
        return MPV_ERROR_INVALID_PARAMETER;
    }
    int size[2] = {width, height};
    const char *format = "bgr0";
    size_t native_stride = (size_t)stride;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_SW_SIZE, size},
        {MPV_RENDER_PARAM_SW_FORMAT, (void *)format},
        {MPV_RENDER_PARAM_SW_STRIDE, &native_stride},
        {MPV_RENDER_PARAM_SW_POINTER, pixels},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    mpv_render_context_render(context, params);
    return 0;
}

FUO_FFI_EXPORT void fuo_mpv_free_render_context(int64_t render_context_value) {
    Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeFreeRenderContext(
        NULL,
        NULL,
        (jlong)render_context_value
    );
}

FUO_FFI_EXPORT int64_t fuo_d3d11_texture_create(int width, int height) {
    return (int64_t)Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeCreate(
        NULL,
        NULL,
        (jint)width,
        (jint)height
    );
}

FUO_FFI_EXPORT int64_t fuo_d3d11_texture_shared_handle(int64_t target_value) {
    return (int64_t)Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeSharedHandle(
        NULL,
        NULL,
        (jlong)target_value
    );
}

FUO_FFI_EXPORT int fuo_d3d11_texture_upload(
    int64_t target_value,
    uint32_t *pixels,
    size_t pixel_count
) {
#if defined(_WIN32)
    fuo_windows_video_texture *target =
        (fuo_windows_video_texture *)(intptr_t)target_value;
    int64_t required = target == NULL ? 0 : (int64_t)target->width * (int64_t)target->height;
    if (
        target == NULL || target->texture == NULL || target->context == NULL ||
        target->keyed_mutex == NULL || pixels == NULL || required <= 0 ||
        (uint64_t)required > (uint64_t)pixel_count
    ) {
        return 0;
    }
    for (int64_t i = 0; i < required; ++i) {
        uint32_t argb = pixels[i];
        pixels[i] =
            (argb & 0xFF000000u) |
            ((argb & 0x00FF0000u) >> 16u) |
            (argb & 0x0000FF00u) |
            ((argb & 0x000000FFu) << 16u);
    }
    HRESULT acquire = IDXGIKeyedMutex_AcquireSync(target->keyed_mutex, 0u, 16u);
    if (acquire != S_OK) return 0;
    ID3D11DeviceContext_UpdateSubresource(
        target->context,
        (ID3D11Resource *)target->texture,
        0u,
        NULL,
        pixels,
        (UINT)target->width * 4u,
        0u
    );
    ID3D11DeviceContext_Flush(target->context);
    IDXGIKeyedMutex_ReleaseSync(target->keyed_mutex, 0u);
    return 1;
#else
    (void)target_value;
    (void)pixels;
    (void)pixel_count;
    return 0;
#endif
}

FUO_FFI_EXPORT void fuo_d3d11_texture_destroy(int64_t target_value) {
    Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeDestroy(
        NULL,
        NULL,
        (jlong)target_value
    );
}
