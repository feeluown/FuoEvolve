/* Windows zero-copy libmpv -> D3D11 shared-texture producer.
 *
 * Tao uses ANGLE on Windows. While Tao's EGL context is current, create a plain shared D3D11
 * texture on ANGLE's own D3D11 device and wrap that texture as an EGL pbuffer. libmpv renders
 * directly into the pbuffer's default framebuffer. Nucleus TextureView then imports the legacy
 * DXGI shared handle and samples the same resource, so no ImageBitmap readback, JNI pixel array,
 * channel conversion, or CPU -> GPU upload is required.
 *
 * The texture intentionally does not use IDXGIKeyedMutex. Nucleus treats a plain
 * D3D11_RESOURCE_MISC_SHARED texture as its direct zero-copy mode; glFlush() publishes producer
 * commands before TextureView is invalidated.
 */
#if defined(_WIN32)

#ifndef COBJMACROS
#define COBJMACROS
#endif
#include <d3d11.h>
#include <dxgi.h>

typedef void *fuo_egl_display;
typedef void *fuo_egl_context;
typedef void *fuo_egl_surface;
typedef void *fuo_egl_config;
typedef void *fuo_egl_device;
typedef void *fuo_egl_client_buffer;
typedef intptr_t fuo_egl_attrib;
typedef int fuo_egl_int;
typedef unsigned int fuo_egl_boolean;
typedef unsigned int fuo_egl_enum;

#define FUO_EGL_FALSE 0u
#define FUO_EGL_NONE 0x3038
#define FUO_EGL_CONFIG_ID 0x3028
#define FUO_EGL_DRAW 0x3059
#define FUO_EGL_READ 0x305A
#define FUO_EGL_TEXTURE_FORMAT 0x3080
#define FUO_EGL_TEXTURE_TARGET 0x3081
#define FUO_EGL_TEXTURE_RGBA 0x305E
#define FUO_EGL_TEXTURE_2D 0x305F
#define FUO_EGL_DEVICE_EXT 0x322C
#define FUO_EGL_D3D11_DEVICE_ANGLE 0x33A1
#define FUO_EGL_D3D_TEXTURE_ANGLE 0x33A3

typedef fuo_egl_display (WINAPI *fuo_egl_get_current_display_fn)(void);
typedef fuo_egl_context (WINAPI *fuo_egl_get_current_context_fn)(void);
typedef fuo_egl_surface (WINAPI *fuo_egl_get_current_surface_fn)(fuo_egl_int);
typedef fuo_egl_boolean (WINAPI *fuo_egl_query_surface_fn)(
    fuo_egl_display, fuo_egl_surface, fuo_egl_int, fuo_egl_int *
);
typedef fuo_egl_boolean (WINAPI *fuo_egl_choose_config_fn)(
    fuo_egl_display, const fuo_egl_int *, fuo_egl_config *, fuo_egl_int, fuo_egl_int *
);
typedef fuo_egl_boolean (WINAPI *fuo_egl_query_display_attrib_ext_fn)(
    fuo_egl_display, fuo_egl_int, fuo_egl_attrib *
);
typedef fuo_egl_boolean (WINAPI *fuo_egl_query_device_attrib_ext_fn)(
    fuo_egl_device, fuo_egl_int, fuo_egl_attrib *
);
typedef fuo_egl_surface (WINAPI *fuo_egl_create_pbuffer_from_client_buffer_fn)(
    fuo_egl_display,
    fuo_egl_enum,
    fuo_egl_client_buffer,
    fuo_egl_config,
    const fuo_egl_int *
);
typedef fuo_egl_boolean (WINAPI *fuo_egl_make_current_fn)(
    fuo_egl_display, fuo_egl_surface, fuo_egl_surface, fuo_egl_context
);
typedef fuo_egl_boolean (WINAPI *fuo_egl_destroy_surface_fn)(fuo_egl_display, fuo_egl_surface);
typedef void *(WINAPI *fuo_egl_get_proc_address_zero_fn)(const char *);

typedef struct fuo_windows_mpv_shared_target {
    fuo_egl_display display;
    fuo_egl_context context;
    fuo_egl_surface pbuffer;
    ID3D11Texture2D *texture;
    HANDLE shared_handle;
    int width;
    int height;
} fuo_windows_mpv_shared_target;

typedef struct fuo_windows_egl_api {
    fuo_egl_get_current_display_fn get_current_display;
    fuo_egl_get_current_context_fn get_current_context;
    fuo_egl_get_current_surface_fn get_current_surface;
    fuo_egl_query_surface_fn query_surface;
    fuo_egl_choose_config_fn choose_config;
    fuo_egl_query_display_attrib_ext_fn query_display_attrib_ext;
    fuo_egl_query_device_attrib_ext_fn query_device_attrib_ext;
    fuo_egl_create_pbuffer_from_client_buffer_fn create_pbuffer_from_client_buffer;
    fuo_egl_make_current_fn make_current;
    fuo_egl_destroy_surface_fn destroy_surface;
} fuo_windows_egl_api;

static fuo_windows_egl_api fuo_windows_egl;
static int fuo_windows_egl_loaded = 0;

/* {035f3ab4-482e-4e50-b41f-8a7f8bd8960b} */
static const GUID fuo_zero_iid_idxgi_resource =
    {0x035f3ab4, 0x482e, 0x4e50, {0xb4, 0x1f, 0x8a, 0x7f, 0x8b, 0xd8, 0x96, 0x0b}};

static void *fuo_resolve_windows_egl_proc(fuo_gl_render_context *renderer, const char *name) {
    if (renderer != NULL && renderer->tao_get_proc_address != NULL) {
        void *resolved = renderer->tao_get_proc_address(name);
        if (resolved != NULL) return resolved;
    }

    static HMODULE egl_module = NULL;
    static fuo_egl_get_proc_address_zero_fn egl_get_proc_address = NULL;
    if (egl_module == NULL) {
        egl_module = GetModuleHandleA("libEGL.dll");
        if (egl_module == NULL) egl_module = LoadLibraryA("libEGL.dll");
        if (egl_module != NULL) {
            egl_get_proc_address = (fuo_egl_get_proc_address_zero_fn)(intptr_t)
                GetProcAddress(egl_module, "eglGetProcAddress");
        }
    }
    if (egl_module != NULL) {
        FARPROC resolved = GetProcAddress(egl_module, name);
        if (resolved != NULL) return (void *)(intptr_t)resolved;
    }
    if (egl_get_proc_address != NULL) return egl_get_proc_address(name);
    return NULL;
}

#define FUO_LOAD_EGL(field, type, name) \
    do { \
        fuo_windows_egl.field = (type)(intptr_t)fuo_resolve_windows_egl_proc(renderer, name); \
        if (fuo_windows_egl.field == NULL) return 0; \
    } while (0)

static int fuo_ensure_windows_egl(fuo_gl_render_context *renderer) {
    if (fuo_windows_egl_loaded) return 1;
    FUO_LOAD_EGL(get_current_display, fuo_egl_get_current_display_fn, "eglGetCurrentDisplay");
    FUO_LOAD_EGL(get_current_context, fuo_egl_get_current_context_fn, "eglGetCurrentContext");
    FUO_LOAD_EGL(get_current_surface, fuo_egl_get_current_surface_fn, "eglGetCurrentSurface");
    FUO_LOAD_EGL(query_surface, fuo_egl_query_surface_fn, "eglQuerySurface");
    FUO_LOAD_EGL(choose_config, fuo_egl_choose_config_fn, "eglChooseConfig");
    FUO_LOAD_EGL(
        query_display_attrib_ext,
        fuo_egl_query_display_attrib_ext_fn,
        "eglQueryDisplayAttribEXT"
    );
    FUO_LOAD_EGL(
        query_device_attrib_ext,
        fuo_egl_query_device_attrib_ext_fn,
        "eglQueryDeviceAttribEXT"
    );
    FUO_LOAD_EGL(
        create_pbuffer_from_client_buffer,
        fuo_egl_create_pbuffer_from_client_buffer_fn,
        "eglCreatePbufferFromClientBuffer"
    );
    FUO_LOAD_EGL(make_current, fuo_egl_make_current_fn, "eglMakeCurrent");
    FUO_LOAD_EGL(destroy_surface, fuo_egl_destroy_surface_fn, "eglDestroySurface");
    fuo_windows_egl_loaded = 1;
    return 1;
}

static void fuo_destroy_windows_mpv_shared_target(fuo_windows_mpv_shared_target *target) {
    if (target == NULL) return;
    if (
        target->pbuffer != NULL &&
        target->display != NULL &&
        fuo_windows_egl.destroy_surface != NULL
    ) {
        fuo_windows_egl.destroy_surface(target->display, target->pbuffer);
        target->pbuffer = NULL;
    }
    if (target->texture != NULL) {
        ID3D11Texture2D_Release(target->texture);
        target->texture = NULL;
    }
    free(target);
}

static fuo_egl_config fuo_current_egl_config(fuo_egl_display display, fuo_egl_surface surface) {
    fuo_egl_int config_id = 0;
    if (
        display == NULL ||
        surface == NULL ||
        !fuo_windows_egl.query_surface(display, surface, FUO_EGL_CONFIG_ID, &config_id)
    ) {
        return NULL;
    }
    const fuo_egl_int attributes[] = {
        FUO_EGL_CONFIG_ID, config_id,
        FUO_EGL_NONE,
    };
    fuo_egl_config config = NULL;
    fuo_egl_int count = 0;
    if (
        !fuo_windows_egl.choose_config(display, attributes, &config, 1, &count) ||
        count < 1
    ) {
        return NULL;
    }
    return config;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateD3D11RenderTarget(
    JNIEnv *env,
    jobject self,
    jlong render_context_value,
    jint width,
    jint height
) {
    (void)env;
    (void)self;
    fuo_gl_render_context *renderer = gl_render_context_from_jlong(render_context_value);
    if (
        renderer == NULL ||
        renderer->mpv == NULL ||
        width <= 0 ||
        height <= 0 ||
        !fuo_ensure_windows_egl(renderer)
    ) {
        return 0;
    }

    fuo_egl_display display = fuo_windows_egl.get_current_display();
    fuo_egl_context context = fuo_windows_egl.get_current_context();
    fuo_egl_surface draw = fuo_windows_egl.get_current_surface(FUO_EGL_DRAW);
    if (display == NULL || context == NULL || draw == NULL) return 0;
    fuo_egl_config config = fuo_current_egl_config(display, draw);
    if (config == NULL) return 0;

    fuo_egl_attrib device_attrib = 0;
    if (
        !fuo_windows_egl.query_display_attrib_ext(
            display,
            FUO_EGL_DEVICE_EXT,
            &device_attrib
        ) ||
        device_attrib == 0
    ) {
        return 0;
    }
    fuo_egl_attrib d3d_attrib = 0;
    if (
        !fuo_windows_egl.query_device_attrib_ext(
            (fuo_egl_device)(intptr_t)device_attrib,
            FUO_EGL_D3D11_DEVICE_ANGLE,
            &d3d_attrib
        ) ||
        d3d_attrib == 0
    ) {
        return 0;
    }
    ID3D11Device *device = (ID3D11Device *)(intptr_t)d3d_attrib;

    fuo_windows_mpv_shared_target *target =
        (fuo_windows_mpv_shared_target *)calloc(1u, sizeof(fuo_windows_mpv_shared_target));
    if (target == NULL) return 0;
    target->display = display;
    target->context = context;
    target->width = width;
    target->height = height;

    D3D11_TEXTURE2D_DESC desc;
    memset(&desc, 0, sizeof(desc));
    desc.Width = (UINT)width;
    desc.Height = (UINT)height;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    desc.MiscFlags = D3D11_RESOURCE_MISC_SHARED;
    if (
        FAILED(ID3D11Device_CreateTexture2D(device, &desc, NULL, &target->texture)) ||
        target->texture == NULL
    ) {
        goto failure;
    }

    IDXGIResource *resource = NULL;
    if (
        FAILED(ID3D11Texture2D_QueryInterface(
            target->texture,
            &fuo_zero_iid_idxgi_resource,
            (void **)&resource
        )) ||
        resource == NULL
    ) {
        goto failure;
    }
    HRESULT shared_result = IDXGIResource_GetSharedHandle(resource, &target->shared_handle);
    IDXGIResource_Release(resource);
    if (FAILED(shared_result) || target->shared_handle == NULL) goto failure;

    const fuo_egl_int pbuffer_attributes[] = {
        FUO_EGL_TEXTURE_FORMAT, FUO_EGL_TEXTURE_RGBA,
        FUO_EGL_TEXTURE_TARGET, FUO_EGL_TEXTURE_2D,
        FUO_EGL_NONE,
    };
    target->pbuffer = fuo_windows_egl.create_pbuffer_from_client_buffer(
        display,
        FUO_EGL_D3D_TEXTURE_ANGLE,
        (fuo_egl_client_buffer)target->texture,
        config,
        pbuffer_attributes
    );
    if (target->pbuffer == NULL) goto failure;

    return (jlong)(intptr_t)target;

failure:
    fuo_destroy_windows_mpv_shared_target(target);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeD3D11RenderTargetSharedHandle(
    JNIEnv *env,
    jobject self,
    jlong target_value
) {
    (void)env;
    (void)self;
    fuo_windows_mpv_shared_target *target =
        (fuo_windows_mpv_shared_target *)(intptr_t)target_value;
    return target == NULL ? 0 : (jlong)(intptr_t)target->shared_handle;
}

JNIEXPORT jboolean JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeRenderD3D11(
    JNIEnv *env,
    jobject self,
    jlong render_context_value,
    jlong target_value
) {
    (void)env;
    (void)self;
    fuo_gl_render_context *renderer = gl_render_context_from_jlong(render_context_value);
    fuo_windows_mpv_shared_target *target =
        (fuo_windows_mpv_shared_target *)(intptr_t)target_value;
    if (
        renderer == NULL ||
        renderer->mpv == NULL ||
        target == NULL ||
        target->pbuffer == NULL ||
        !fuo_ensure_windows_egl(renderer)
    ) {
        return JNI_FALSE;
    }

    fuo_egl_display current_display = fuo_windows_egl.get_current_display();
    fuo_egl_context current_context = fuo_windows_egl.get_current_context();
    fuo_egl_surface host_draw = fuo_windows_egl.get_current_surface(FUO_EGL_DRAW);
    fuo_egl_surface host_read = fuo_windows_egl.get_current_surface(FUO_EGL_READ);
    if (
        current_display != target->display ||
        current_context != target->context ||
        host_draw == NULL ||
        host_read == NULL
    ) {
        return JNI_FALSE;
    }

    if (
        !fuo_windows_egl.make_current(
            target->display,
            target->pbuffer,
            target->pbuffer,
            target->context
        )
    ) {
        return JNI_FALSE;
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

    fuo_egl_boolean restored = fuo_windows_egl.make_current(
        current_display,
        host_draw,
        host_read,
        current_context
    );
    return restored ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeDestroyD3D11RenderTarget(
    JNIEnv *env,
    jobject self,
    jlong target_value
) {
    (void)env;
    (void)self;
    fuo_destroy_windows_mpv_shared_target(
        (fuo_windows_mpv_shared_target *)(intptr_t)target_value
    );
}

#else

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateD3D11RenderTarget(
    JNIEnv *env, jobject self, jlong render_context_value, jint width, jint height
) {
    (void)env; (void)self; (void)render_context_value; (void)width; (void)height;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeD3D11RenderTargetSharedHandle(
    JNIEnv *env, jobject self, jlong target_value
) {
    (void)env; (void)self; (void)target_value;
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeRenderD3D11(
    JNIEnv *env, jobject self, jlong render_context_value, jlong target_value
) {
    (void)env; (void)self; (void)render_context_value; (void)target_value;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeDestroyD3D11RenderTarget(
    JNIEnv *env, jobject self, jlong target_value
) {
    (void)env; (void)self; (void)target_value;
}

#endif
