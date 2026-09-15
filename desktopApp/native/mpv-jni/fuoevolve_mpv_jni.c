#include <locale.h>
#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#define FUO_NATIVE_EXPORT __declspec(dllexport)
#else
#include <dlfcn.h>
#define FUO_NATIVE_EXPORT __attribute__((visibility("default")))
#endif

static mpv_handle *fuo_handle_from_i64(int64_t value) {
    return (mpv_handle *)(intptr_t)value;
}

static mpv_render_context *fuo_render_context_from_i64(int64_t value) {
    return (mpv_render_context *)(intptr_t)value;
}

/* Minimal dynamically-resolved GLES surface used by the Tao OpenGL path. We deliberately avoid
 * linking another GL implementation into the bridge: Tao owns the active EGL/GLES implementation,
 * so libmpv and Skia stay on the same GPU device/context. */
#if defined(_WIN32)
#define FUO_GL_APIENTRY APIENTRY
#else
#define FUO_GL_APIENTRY
#endif

typedef unsigned int fuo_gl_enum;
typedef unsigned int fuo_gl_uint;
typedef int fuo_gl_int;
typedef int fuo_gl_sizei;

typedef void (FUO_GL_APIENTRY *fuo_gl_gen_textures_fn)(fuo_gl_sizei, fuo_gl_uint *);
typedef void (FUO_GL_APIENTRY *fuo_gl_delete_textures_fn)(fuo_gl_sizei, const fuo_gl_uint *);
typedef void (FUO_GL_APIENTRY *fuo_gl_bind_texture_fn)(fuo_gl_enum, fuo_gl_uint);
typedef void (FUO_GL_APIENTRY *fuo_gl_tex_parameteri_fn)(fuo_gl_enum, fuo_gl_enum, fuo_gl_int);
typedef void (FUO_GL_APIENTRY *fuo_gl_tex_image_2d_fn)(
    fuo_gl_enum, fuo_gl_int, fuo_gl_int, fuo_gl_sizei, fuo_gl_sizei,
    fuo_gl_int, fuo_gl_enum, fuo_gl_enum, const void *
);
typedef void (FUO_GL_APIENTRY *fuo_gl_gen_framebuffers_fn)(fuo_gl_sizei, fuo_gl_uint *);
typedef void (FUO_GL_APIENTRY *fuo_gl_delete_framebuffers_fn)(fuo_gl_sizei, const fuo_gl_uint *);
typedef void (FUO_GL_APIENTRY *fuo_gl_bind_framebuffer_fn)(fuo_gl_enum, fuo_gl_uint);
typedef void (FUO_GL_APIENTRY *fuo_gl_framebuffer_texture_2d_fn)(
    fuo_gl_enum, fuo_gl_enum, fuo_gl_enum, fuo_gl_uint, fuo_gl_int
);
typedef fuo_gl_enum (FUO_GL_APIENTRY *fuo_gl_check_framebuffer_status_fn)(fuo_gl_enum);
typedef void (FUO_GL_APIENTRY *fuo_gl_get_integerv_fn)(fuo_gl_enum, fuo_gl_int *);
typedef void (FUO_GL_APIENTRY *fuo_gl_flush_fn)(void);

typedef struct fuo_gl_render_context fuo_gl_render_context;

#if defined(_WIN32)
typedef void *(WINAPI *fuo_gl_get_proc_address_fn)(const char *);
#else
typedef void *(*fuo_gl_get_proc_address_fn)(const char *);
#endif

#if !defined(_WIN32) && !defined(__APPLE__)
enum {
    FUO_NATIVE_DISPLAY_NONE = 0,
    FUO_NATIVE_DISPLAY_X11 = 1,
    FUO_NATIVE_DISPLAY_WAYLAND = 2,
    FUO_NATIVE_DISPLAY_EXACT = 0x10,
};

typedef struct fuo_native_display {
    void *value;
    int kind;
} fuo_native_display;
#endif

struct fuo_gl_render_context {
    mpv_render_context *mpv;
    fuo_gl_get_proc_address_fn tao_get_proc_address;
#if !defined(_WIN32) && !defined(__APPLE__)
    fuo_native_display native_display;
#endif
};

static fuo_gl_render_context *fuo_gl_render_context_from_i64(int64_t value) {
    return (fuo_gl_render_context *)(intptr_t)value;
}

typedef struct fuo_gl_api {
    fuo_gl_gen_textures_fn gen_textures;
    fuo_gl_delete_textures_fn delete_textures;
    fuo_gl_bind_texture_fn bind_texture;
    fuo_gl_tex_parameteri_fn tex_parameteri;
    fuo_gl_tex_image_2d_fn tex_image_2d;
    fuo_gl_gen_framebuffers_fn gen_framebuffers;
    fuo_gl_delete_framebuffers_fn delete_framebuffers;
    fuo_gl_bind_framebuffer_fn bind_framebuffer;
    fuo_gl_framebuffer_texture_2d_fn framebuffer_texture_2d;
    fuo_gl_check_framebuffer_status_fn check_framebuffer_status;
    fuo_gl_get_integerv_fn get_integerv;
    fuo_gl_flush_fn flush;
} fuo_gl_api;

typedef struct fuo_gl_target {
    fuo_gl_uint texture;
    fuo_gl_uint framebuffer;
    int width;
    int height;
} fuo_gl_target;

static fuo_gl_api global_gl_api;
static int global_gl_api_loaded = 0;
static fuo_gl_get_proc_address_fn global_tao_get_proc_address = NULL;

#define FUO_GL_TEXTURE_2D 0x0DE1u
#define FUO_GL_RGBA 0x1908u
#define FUO_GL_RGBA8 0x8058u
#define FUO_GL_UNSIGNED_BYTE 0x1401u
#define FUO_GL_TEXTURE_MIN_FILTER 0x2801u
#define FUO_GL_TEXTURE_MAG_FILTER 0x2800u
#define FUO_GL_TEXTURE_WRAP_S 0x2802u
#define FUO_GL_TEXTURE_WRAP_T 0x2803u
#define FUO_GL_LINEAR 0x2601u
#define FUO_GL_CLAMP_TO_EDGE 0x812Fu
#define FUO_GL_FRAMEBUFFER 0x8D40u
#define FUO_GL_FRAMEBUFFER_BINDING 0x8CA6u
#define FUO_GL_TEXTURE_BINDING_2D 0x8069u
#define FUO_GL_COLOR_ATTACHMENT0 0x8CE0u
#define FUO_GL_FRAMEBUFFER_COMPLETE 0x8CD5u

#if defined(_WIN32)
typedef void *(WINAPI *fuo_egl_get_proc_address_fn)(const char *);

static void *resolve_gl_proc(void *context, const char *name) {
    fuo_gl_get_proc_address_fn tao_get_proc_address = global_tao_get_proc_address;
    if (context != NULL) {
        fuo_gl_render_context *renderer = (fuo_gl_render_context *)context;
        tao_get_proc_address = renderer->tao_get_proc_address;
    }
    if (tao_get_proc_address != NULL) {
        void *resolved = tao_get_proc_address(name);
        if (resolved != NULL) return resolved;
    }

    static HMODULE egl_module = NULL;
    static HMODULE gles_module = NULL;
    static fuo_egl_get_proc_address_fn egl_get_proc_address = NULL;
    if (egl_module == NULL) {
        egl_module = GetModuleHandleA("libEGL.dll");
        if (egl_module == NULL) egl_module = LoadLibraryA("libEGL.dll");
        if (egl_module != NULL) {
            egl_get_proc_address = (fuo_egl_get_proc_address_fn)(intptr_t)
                GetProcAddress(egl_module, "eglGetProcAddress");
        }
    }
    if (gles_module == NULL) {
        gles_module = GetModuleHandleA("libGLESv2.dll");
        if (gles_module == NULL) gles_module = LoadLibraryA("libGLESv2.dll");
    }
    if (egl_get_proc_address != NULL) {
        void *resolved = egl_get_proc_address(name);
        if (resolved != NULL) return resolved;
    }
    if (gles_module != NULL) {
        FARPROC resolved = GetProcAddress(gles_module, name);
        if (resolved != NULL) return (void *)(intptr_t)resolved;
    }
    return NULL;
}
#else
typedef void *(*fuo_egl_get_proc_address_fn)(const char *);

static void *resolve_gl_proc(void *context, const char *name) {
    fuo_gl_get_proc_address_fn tao_get_proc_address = global_tao_get_proc_address;
    if (context != NULL) {
        fuo_gl_render_context *renderer = (fuo_gl_render_context *)context;
        tao_get_proc_address = renderer->tao_get_proc_address;
    }
    if (tao_get_proc_address != NULL) {
        void *resolved = tao_get_proc_address(name);
        if (resolved != NULL) return resolved;
    }
#if !defined(__APPLE__)
    static void *egl_module = NULL;
    static void *gles_module = NULL;
    static fuo_egl_get_proc_address_fn egl_get_proc_address = NULL;
    if (egl_module == NULL) {
        egl_module = dlopen("libEGL.so.1", RTLD_LAZY | RTLD_LOCAL);
        if (egl_module != NULL) {
            egl_get_proc_address = (fuo_egl_get_proc_address_fn)dlsym(egl_module, "eglGetProcAddress");
        }
    }
    if (gles_module == NULL) gles_module = dlopen("libGLESv2.so.2", RTLD_LAZY | RTLD_LOCAL);
    if (egl_get_proc_address != NULL) {
        void *resolved = egl_get_proc_address(name);
        if (resolved != NULL) return resolved;
    }
    if (gles_module != NULL) {
        void *resolved = dlsym(gles_module, name);
        if (resolved != NULL) return resolved;
    }
#endif
    return dlsym(RTLD_DEFAULT, name);
}
#endif

#define LOAD_GL_PROC(field, type, name) \
    do { \
        global_gl_api.field = (type)(intptr_t)resolve_gl_proc(NULL, name); \
        if (global_gl_api.field == NULL) return 0; \
    } while (0)

static int ensure_gl_api(void) {
    if (global_gl_api_loaded) return 1;
    LOAD_GL_PROC(gen_textures, fuo_gl_gen_textures_fn, "glGenTextures");
    LOAD_GL_PROC(delete_textures, fuo_gl_delete_textures_fn, "glDeleteTextures");
    LOAD_GL_PROC(bind_texture, fuo_gl_bind_texture_fn, "glBindTexture");
    LOAD_GL_PROC(tex_parameteri, fuo_gl_tex_parameteri_fn, "glTexParameteri");
    LOAD_GL_PROC(tex_image_2d, fuo_gl_tex_image_2d_fn, "glTexImage2D");
    LOAD_GL_PROC(gen_framebuffers, fuo_gl_gen_framebuffers_fn, "glGenFramebuffers");
    LOAD_GL_PROC(delete_framebuffers, fuo_gl_delete_framebuffers_fn, "glDeleteFramebuffers");
    LOAD_GL_PROC(bind_framebuffer, fuo_gl_bind_framebuffer_fn, "glBindFramebuffer");
    LOAD_GL_PROC(framebuffer_texture_2d, fuo_gl_framebuffer_texture_2d_fn, "glFramebufferTexture2D");
    LOAD_GL_PROC(check_framebuffer_status, fuo_gl_check_framebuffer_status_fn, "glCheckFramebufferStatus");
    LOAD_GL_PROC(get_integerv, fuo_gl_get_integerv_fn, "glGetIntegerv");
    LOAD_GL_PROC(flush, fuo_gl_flush_fn, "glFlush");
    global_gl_api_loaded = 1;
    return 1;
}

static void destroy_gl_target(fuo_gl_target *target) {
    if (target == NULL || !ensure_gl_api()) return;
    if (target->framebuffer != 0u) global_gl_api.delete_framebuffers(1, &target->framebuffer);
    if (target->texture != 0u) global_gl_api.delete_textures(1, &target->texture);
}

FUO_NATIVE_EXPORT int64_t fuo_mpv_create_software_render_context(int64_t handle_value) {
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
    if (handle == NULL) return 0;
    mpv_render_context *context = NULL;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, (void *)MPV_RENDER_API_TYPE_SW},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    int result = mpv_render_context_create(&context, handle, params);
    return result < 0 || context == NULL ? 0 : (int64_t)(intptr_t)context;
}

FUO_NATIVE_EXPORT int64_t fuo_mpv_create_opengl_render_context(
    int64_t handle_value,
    int direct_hardware,
    int native_display_kind,
    int64_t native_display_value,
    int64_t tao_get_proc_address_value
) {
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
    if (handle == NULL) return 0;
    fuo_gl_render_context *renderer =
        (fuo_gl_render_context *)calloc(1u, sizeof(fuo_gl_render_context));
    if (renderer == NULL) return 0;
    renderer->tao_get_proc_address =
        (fuo_gl_get_proc_address_fn)(intptr_t)tao_get_proc_address_value;
    if (global_tao_get_proc_address != renderer->tao_get_proc_address) {
        global_tao_get_proc_address = renderer->tao_get_proc_address;
        global_gl_api_loaded = 0;
    }
#if !defined(_WIN32) && !defined(__APPLE__)
    const int native_display_protocol = native_display_kind & 0x0F;
    if ((native_display_protocol == FUO_NATIVE_DISPLAY_WAYLAND ||
         native_display_protocol == FUO_NATIVE_DISPLAY_X11) && native_display_value != 0) {
        renderer->native_display.value = (void *)(intptr_t)native_display_value;
        renderer->native_display.kind = native_display_protocol | FUO_NATIVE_DISPLAY_EXACT;
    }
    if (direct_hardware && renderer->native_display.value == NULL) {
        free(renderer);
        return 0;
    }
#else
    (void)direct_hardware; (void)native_display_kind; (void)native_display_value;
#endif
    mpv_opengl_init_params gl_init = {
        .get_proc_address = resolve_gl_proc,
        .get_proc_address_ctx = renderer,
    };
    mpv_render_param params[4];
    int param_count = 0;
    params[param_count++] =
        (mpv_render_param){MPV_RENDER_PARAM_API_TYPE, (void *)MPV_RENDER_API_TYPE_OPENGL};
    params[param_count++] =
        (mpv_render_param){MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init};
#if !defined(_WIN32) && !defined(__APPLE__)
    const int protocol = renderer->native_display.kind & 0x0F;
    if (protocol == FUO_NATIVE_DISPLAY_WAYLAND) {
        params[param_count++] =
            (mpv_render_param){MPV_RENDER_PARAM_WL_DISPLAY, renderer->native_display.value};
    } else if (protocol == FUO_NATIVE_DISPLAY_X11) {
        params[param_count++] =
            (mpv_render_param){MPV_RENDER_PARAM_X11_DISPLAY, renderer->native_display.value};
    }
#endif
    params[param_count] = (mpv_render_param){MPV_RENDER_PARAM_INVALID, NULL};
    int result = mpv_render_context_create(&renderer->mpv, handle, params);
    if (result < 0 || renderer->mpv == NULL) {
        free(renderer);
        return 0;
    }
    return (int64_t)(intptr_t)renderer;
}

FUO_NATIVE_EXPORT int fuo_mpv_opengl_render_context_display_kind(int64_t render_context_value) {
#if !defined(_WIN32) && !defined(__APPLE__)
    fuo_gl_render_context *renderer = fuo_gl_render_context_from_i64(render_context_value);
    return renderer == NULL ? FUO_NATIVE_DISPLAY_NONE : renderer->native_display.kind;
#else
    (void)render_context_value;
    return 0;
#endif
}

FUO_NATIVE_EXPORT int64_t fuo_mpv_update_render_context(int64_t render_context_value) {
    fuo_gl_render_context *renderer = fuo_gl_render_context_from_i64(render_context_value);
    return renderer == NULL || renderer->mpv == NULL
        ? 0
        : (int64_t)mpv_render_context_update(renderer->mpv);
}

FUO_NATIVE_EXPORT int64_t fuo_mpv_create_opengl_render_target(int width, int height) {
    if (width <= 0 || height <= 0 || !ensure_gl_api()) return 0;
    fuo_gl_int previous_framebuffer = 0;
    fuo_gl_int previous_texture = 0;
    global_gl_api.get_integerv(FUO_GL_FRAMEBUFFER_BINDING, &previous_framebuffer);
    global_gl_api.get_integerv(FUO_GL_TEXTURE_BINDING_2D, &previous_texture);
    fuo_gl_target *target = (fuo_gl_target *)calloc(1u, sizeof(fuo_gl_target));
    if (target == NULL) return 0;
    target->width = width;
    target->height = height;
    global_gl_api.gen_textures(1, &target->texture);
    if (target->texture == 0u) goto failure;
    global_gl_api.bind_texture(FUO_GL_TEXTURE_2D, target->texture);
    global_gl_api.tex_parameteri(FUO_GL_TEXTURE_2D, FUO_GL_TEXTURE_MIN_FILTER, FUO_GL_LINEAR);
    global_gl_api.tex_parameteri(FUO_GL_TEXTURE_2D, FUO_GL_TEXTURE_MAG_FILTER, FUO_GL_LINEAR);
    global_gl_api.tex_parameteri(FUO_GL_TEXTURE_2D, FUO_GL_TEXTURE_WRAP_S, FUO_GL_CLAMP_TO_EDGE);
    global_gl_api.tex_parameteri(FUO_GL_TEXTURE_2D, FUO_GL_TEXTURE_WRAP_T, FUO_GL_CLAMP_TO_EDGE);
    global_gl_api.tex_image_2d(
        FUO_GL_TEXTURE_2D, 0, FUO_GL_RGBA8, width, height, 0,
        FUO_GL_RGBA, FUO_GL_UNSIGNED_BYTE, NULL
    );
    global_gl_api.gen_framebuffers(1, &target->framebuffer);
    if (target->framebuffer == 0u) goto failure;
    global_gl_api.bind_framebuffer(FUO_GL_FRAMEBUFFER, target->framebuffer);
    global_gl_api.framebuffer_texture_2d(
        FUO_GL_FRAMEBUFFER, FUO_GL_COLOR_ATTACHMENT0, FUO_GL_TEXTURE_2D, target->texture, 0
    );
    if (global_gl_api.check_framebuffer_status(FUO_GL_FRAMEBUFFER) != FUO_GL_FRAMEBUFFER_COMPLETE) {
        goto failure;
    }
    global_gl_api.bind_framebuffer(FUO_GL_FRAMEBUFFER, (fuo_gl_uint)previous_framebuffer);
    global_gl_api.bind_texture(FUO_GL_TEXTURE_2D, (fuo_gl_uint)previous_texture);
    return (int64_t)(intptr_t)target;

failure:
    global_gl_api.bind_framebuffer(FUO_GL_FRAMEBUFFER, (fuo_gl_uint)previous_framebuffer);
    global_gl_api.bind_texture(FUO_GL_TEXTURE_2D, (fuo_gl_uint)previous_texture);
    destroy_gl_target(target);
    free(target);
    return 0;
}

FUO_NATIVE_EXPORT int fuo_mpv_opengl_render_target_framebuffer(int64_t render_target_value) {
    fuo_gl_target *target = (fuo_gl_target *)(intptr_t)render_target_value;
    return target == NULL ? 0 : (int)target->framebuffer;
}

FUO_NATIVE_EXPORT void fuo_mpv_render_opengl(
    int64_t render_context_value,
    int64_t render_target_value
) {
    fuo_gl_render_context *renderer = fuo_gl_render_context_from_i64(render_context_value);
    fuo_gl_target *target = (fuo_gl_target *)(intptr_t)render_target_value;
    if (renderer == NULL || renderer->mpv == NULL || target == NULL) return;
    mpv_opengl_fbo fbo = {
        .fbo = (int)target->framebuffer,
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
}

FUO_NATIVE_EXPORT void fuo_mpv_report_swap(int64_t render_context_value) {
    fuo_gl_render_context *renderer = fuo_gl_render_context_from_i64(render_context_value);
    if (renderer != NULL && renderer->mpv != NULL) mpv_render_context_report_swap(renderer->mpv);
}

FUO_NATIVE_EXPORT void fuo_mpv_destroy_opengl_render_target(int64_t render_target_value) {
    fuo_gl_target *target = (fuo_gl_target *)(intptr_t)render_target_value;
    if (target == NULL) return;
    destroy_gl_target(target);
    free(target);
}

FUO_NATIVE_EXPORT void fuo_mpv_free_opengl_render_context(int64_t render_context_value) {
    fuo_gl_render_context *renderer = fuo_gl_render_context_from_i64(render_context_value);
    if (renderer == NULL) return;
    if (renderer->mpv != NULL) mpv_render_context_free(renderer->mpv);
    if (global_tao_get_proc_address == renderer->tao_get_proc_address) {
        global_tao_get_proc_address = NULL;
        global_gl_api_loaded = 0;
    }
    free(renderer);
}

FUO_NATIVE_EXPORT void fuo_mpv_free_render_context(int64_t render_context_value) {
    mpv_render_context *context = fuo_render_context_from_i64(render_context_value);
    if (context != NULL) mpv_render_context_free(context);
}

#include "fuoevolve_mpv_iosurface.inc"
