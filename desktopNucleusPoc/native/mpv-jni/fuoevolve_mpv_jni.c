#include <jni.h>
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
#else
#include <dlfcn.h>
#endif

static mpv_handle *handle_from_jlong(jlong value) {
    return (mpv_handle *)(intptr_t)value;
}

static mpv_render_context *render_context_from_jlong(jlong value) {
    return (mpv_render_context *)(intptr_t)value;
}

static char *jstring_to_utf8(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;

    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (chars == NULL) return NULL;
    jsize length = (*env)->GetStringLength(env, value);

    size_t capacity = (size_t)length * 4u + 1u;
    char *result = (char *)malloc(capacity);
    if (result == NULL) {
        (*env)->ReleaseStringChars(env, value, chars);
        return NULL;
    }

    size_t out = 0;
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xD800u && codepoint <= 0xDBFFu && i + 1 < length) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00u && low <= 0xDFFFu) {
                codepoint = 0x10000u + ((codepoint - 0xD800u) << 10u) + (low - 0xDC00u);
                ++i;
            }
        }

        if (codepoint <= 0x7Fu) {
            result[out++] = (char)codepoint;
        } else if (codepoint <= 0x7FFu) {
            result[out++] = (char)(0xC0u | (codepoint >> 6u));
            result[out++] = (char)(0x80u | (codepoint & 0x3Fu));
        } else if (codepoint <= 0xFFFFu) {
            result[out++] = (char)(0xE0u | (codepoint >> 12u));
            result[out++] = (char)(0x80u | ((codepoint >> 6u) & 0x3Fu));
            result[out++] = (char)(0x80u | (codepoint & 0x3Fu));
        } else {
            result[out++] = (char)(0xF0u | (codepoint >> 18u));
            result[out++] = (char)(0x80u | ((codepoint >> 12u) & 0x3Fu));
            result[out++] = (char)(0x80u | ((codepoint >> 6u) & 0x3Fu));
            result[out++] = (char)(0x80u | (codepoint & 0x3Fu));
        }
    }
    result[out] = '\0';
    (*env)->ReleaseStringChars(env, value, chars);
    return result;
}

static jstring utf8_to_jstring(JNIEnv *env, const char *value) {
    if (value == NULL) return NULL;

    size_t byte_length = strlen(value);
    jchar *chars = (jchar *)malloc((byte_length + 1u) * sizeof(jchar));
    if (chars == NULL) return NULL;

    size_t in = 0;
    jsize out = 0;
    while (in < byte_length) {
        unsigned char first = (unsigned char)value[in++];
        uint32_t codepoint;
        if (first < 0x80u) {
            codepoint = first;
        } else if ((first & 0xE0u) == 0xC0u && in < byte_length) {
            codepoint = ((uint32_t)(first & 0x1Fu) << 6u) |
                        ((uint32_t)value[in++] & 0x3Fu);
        } else if ((first & 0xF0u) == 0xE0u && in + 1u < byte_length) {
            codepoint = ((uint32_t)(first & 0x0Fu) << 12u) |
                        (((uint32_t)value[in++] & 0x3Fu) << 6u) |
                        ((uint32_t)value[in++] & 0x3Fu);
        } else if ((first & 0xF8u) == 0xF0u && in + 2u < byte_length) {
            codepoint = ((uint32_t)(first & 0x07u) << 18u) |
                        (((uint32_t)value[in++] & 0x3Fu) << 12u) |
                        (((uint32_t)value[in++] & 0x3Fu) << 6u) |
                        ((uint32_t)value[in++] & 0x3Fu);
        } else {
            codepoint = 0xFFFDu;
        }

        if (codepoint <= 0xFFFFu) {
            chars[out++] = (jchar)codepoint;
        } else {
            codepoint -= 0x10000u;
            chars[out++] = (jchar)(0xD800u | (codepoint >> 10u));
            chars[out++] = (jchar)(0xDC00u | (codepoint & 0x3FFu));
        }
    }

    jstring result = (*env)->NewString(env, chars, out);
    free(chars);
    return result;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeCreate(JNIEnv *env, jobject self) {
    (void)env;
    (void)self;
    setlocale(LC_NUMERIC, "C");
    return (jlong)(intptr_t)mpv_create();
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeInitialize(
    JNIEnv *env,
    jobject self,
    jlong handle_value
) {
    (void)env;
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    return handle == NULL ? MPV_ERROR_INVALID_PARAMETER : mpv_initialize(handle);
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeSetOption(
    JNIEnv *env,
    jobject self,
    jlong handle_value,
    jstring name_value,
    jstring data_value
) {
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    char *name = jstring_to_utf8(env, name_value);
    char *data = jstring_to_utf8(env, data_value);
    if (handle == NULL || name == NULL || data == NULL) {
        free(name);
        free(data);
        return MPV_ERROR_INVALID_PARAMETER;
    }
    int result = mpv_set_option_string(handle, name, data);
    free(name);
    free(data);
    return result;
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeSetProperty(
    JNIEnv *env,
    jobject self,
    jlong handle_value,
    jstring name_value,
    jstring data_value
) {
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    char *name = jstring_to_utf8(env, name_value);
    char *data = jstring_to_utf8(env, data_value);
    if (handle == NULL || name == NULL || data == NULL) {
        free(name);
        free(data);
        return MPV_ERROR_INVALID_PARAMETER;
    }
    int result = mpv_set_property_string(handle, name, data);
    free(name);
    free(data);
    return result;
}

JNIEXPORT jstring JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeGetProperty(
    JNIEnv *env,
    jobject self,
    jlong handle_value,
    jstring name_value
) {
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    char *name = jstring_to_utf8(env, name_value);
    if (handle == NULL || name == NULL) {
        free(name);
        return NULL;
    }
    char *value = mpv_get_property_string(handle, name);
    free(name);
    if (value == NULL) return NULL;
    jstring result = utf8_to_jstring(env, value);
    mpv_free(value);
    return result;
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeCommand(
    JNIEnv *env,
    jobject self,
    jlong handle_value,
    jobjectArray args_value
) {
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    if (handle == NULL || args_value == NULL) return MPV_ERROR_INVALID_PARAMETER;

    jsize length = (*env)->GetArrayLength(env, args_value);
    const char **args = (const char **)calloc((size_t)length + 1u, sizeof(char *));
    if (args == NULL) return MPV_ERROR_NOMEM;

    int result = MPV_ERROR_INVALID_PARAMETER;
    jsize populated = 0;
    for (; populated < length; ++populated) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, args_value, populated);
        args[populated] = jstring_to_utf8(env, item);
        if (item != NULL) (*env)->DeleteLocalRef(env, item);
        if (args[populated] == NULL) goto cleanup;
    }
    args[length] = NULL;
    result = mpv_command(handle, args);

cleanup:
    for (jsize i = 0; i < populated; ++i) free((void *)args[i]);
    if (populated < length && args[populated] != NULL) free((void *)args[populated]);
    free(args);
    return result;
}

JNIEXPORT jstring JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeWaitEvent(
    JNIEnv *env,
    jobject self,
    jlong handle_value,
    jdouble timeout_seconds
) {
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    if (handle == NULL) return NULL;

    const mpv_event *event = mpv_wait_event(handle, timeout_seconds);
    if (event == NULL || event->event_id == MPV_EVENT_NONE) return NULL;

    char buffer[192];
    switch (event->event_id) {
        case MPV_EVENT_SHUTDOWN:
            return (*env)->NewStringUTF(env, "shutdown");
        case MPV_EVENT_START_FILE: {
            const mpv_event_start_file *start = (const mpv_event_start_file *)event->data;
            if (start == NULL) return NULL;
            snprintf(buffer, sizeof(buffer), "start:%lld", (long long)start->playlist_entry_id);
            return (*env)->NewStringUTF(env, buffer);
        }
        case MPV_EVENT_FILE_LOADED:
            return (*env)->NewStringUTF(env, "loaded");
        case MPV_EVENT_PLAYBACK_RESTART:
            return (*env)->NewStringUTF(env, "restart");
        case MPV_EVENT_END_FILE: {
            const mpv_event_end_file *end = (const mpv_event_end_file *)event->data;
            if (end == NULL) return NULL;
            snprintf(
                buffer,
                sizeof(buffer),
                "end:%lld:%d:%d",
                (long long)end->playlist_entry_id,
                end->reason,
                end->error
            );
            return (*env)->NewStringUTF(env, buffer);
        }
        default:
            return NULL;
    }
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeWakeup(
    JNIEnv *env,
    jobject self,
    jlong handle_value
) {
    (void)env;
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    if (handle != NULL) mpv_wakeup(handle);
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeDestroy(
    JNIEnv *env,
    jobject self,
    jlong handle_value
) {
    (void)env;
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    if (handle != NULL) mpv_terminate_destroy(handle);
}

JNIEXPORT jstring JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeErrorString(
    JNIEnv *env,
    jobject self,
    jint error
) {
    (void)self;
    return utf8_to_jstring(env, mpv_error_string(error));
}

/* Shared desktop video controller aliases the existing client API so the audio and video
 * implementations stay on the same JNI bridge and libmpv runtime. */
JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreate(JNIEnv *env, jobject self) {
    return Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeCreate(env, self);
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeInitialize(
    JNIEnv *env, jobject self, jlong handle_value
) {
    return Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeInitialize(env, self, handle_value);
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeSetOption(
    JNIEnv *env, jobject self, jlong handle_value, jstring name_value, jstring data_value
) {
    return Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeSetOption(
        env, self, handle_value, name_value, data_value
    );
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeSetProperty(
    JNIEnv *env, jobject self, jlong handle_value, jstring name_value, jstring data_value
) {
    return Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeSetProperty(
        env, self, handle_value, name_value, data_value
    );
}

JNIEXPORT jstring JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeGetProperty(
    JNIEnv *env, jobject self, jlong handle_value, jstring name_value
) {
    return Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeGetProperty(
        env, self, handle_value, name_value
    );
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCommand(
    JNIEnv *env, jobject self, jlong handle_value, jobjectArray args_value
) {
    return Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeCommand(
        env, self, handle_value, args_value
    );
}

JNIEXPORT jstring JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeWaitEvent(
    JNIEnv *env, jobject self, jlong handle_value, jdouble timeout_seconds
) {
    return Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeWaitEvent(
        env, self, handle_value, timeout_seconds
    );
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeWakeup(
    JNIEnv *env, jobject self, jlong handle_value
) {
    Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeWakeup(env, self, handle_value);
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeDestroy(
    JNIEnv *env, jobject self, jlong handle_value
) {
    Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeDestroy(env, self, handle_value);
}

JNIEXPORT jstring JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeErrorString(
    JNIEnv *env, jobject self, jint error
) {
    return Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeErrorString(env, self, error);
}

/* Minimal dynamically-resolved GLES surface used by the Tao OpenGL path. We deliberately avoid
 * linking another GL implementation into the JNI bridge: on Windows Tao owns ANGLE, and on Linux
 * it owns the active EGL/GLES implementation. Resolving against that current context guarantees
 * libmpv and Skia operate on the same GPU device/context. */
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

#define FUO_GL_TEXTURE_2D 0x0DE1u
#define FUO_GL_RGBA 0x1908u
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
    (void)context;
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
    (void)context;
    void *resolved = dlsym(RTLD_DEFAULT, name);
    if (resolved != NULL) return resolved;

#if !defined(__APPLE__)
    static void *egl_module = NULL;
    static void *gles_module = NULL;
    static fuo_egl_get_proc_address_fn egl_get_proc_address = NULL;
    if (egl_module == NULL) {
        egl_module = dlopen("libEGL.so.1", RTLD_LAZY | RTLD_LOCAL);
        if (egl_module != NULL) {
            egl_get_proc_address = (fuo_egl_get_proc_address_fn)dlsym(
                egl_module,
                "eglGetProcAddress"
            );
        }
    }
    if (gles_module == NULL) {
        gles_module = dlopen("libGLESv2.so.2", RTLD_LAZY | RTLD_LOCAL);
    }
    if (egl_get_proc_address != NULL) {
        resolved = egl_get_proc_address(name);
        if (resolved != NULL) return resolved;
    }
    if (gles_module != NULL) return dlsym(gles_module, name);
#endif
    return NULL;
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
    LOAD_GL_PROC(
        framebuffer_texture_2d,
        fuo_gl_framebuffer_texture_2d_fn,
        "glFramebufferTexture2D"
    );
    LOAD_GL_PROC(
        check_framebuffer_status,
        fuo_gl_check_framebuffer_status_fn,
        "glCheckFramebufferStatus"
    );
    LOAD_GL_PROC(get_integerv, fuo_gl_get_integerv_fn, "glGetIntegerv");
    LOAD_GL_PROC(flush, fuo_gl_flush_fn, "glFlush");
    global_gl_api_loaded = 1;
    return 1;
}

static void destroy_gl_target(fuo_gl_target *target) {
    if (target == NULL || !ensure_gl_api()) return;
    if (target->framebuffer != 0u) {
        global_gl_api.delete_framebuffers(1, &target->framebuffer);
        target->framebuffer = 0u;
    }
    if (target->texture != 0u) {
        global_gl_api.delete_textures(1, &target->texture);
        target->texture = 0u;
    }
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateSoftwareRenderContext(
    JNIEnv *env,
    jobject self,
    jlong handle_value
) {
    (void)env;
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    if (handle == NULL) return 0;

    mpv_render_context *context = NULL;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, (void *)MPV_RENDER_API_TYPE_SW},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    int result = mpv_render_context_create(&context, handle, params);
    if (result < 0 || context == NULL) return 0;
    return (jlong)(intptr_t)context;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateOpenGlRenderContext(
    JNIEnv *env,
    jobject self,
    jlong handle_value
) {
    (void)env;
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    if (handle == NULL) return 0;

    mpv_opengl_init_params gl_init = {
        .get_proc_address = resolve_gl_proc,
        .get_proc_address_ctx = NULL,
    };
    mpv_render_context *context = NULL;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, (void *)MPV_RENDER_API_TYPE_OPENGL},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    int result = mpv_render_context_create(&context, handle, params);
    if (result < 0 || context == NULL) return 0;
    return (jlong)(intptr_t)context;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeUpdateRenderContext(
    JNIEnv *env,
    jobject self,
    jlong render_context_value
) {
    (void)env;
    (void)self;
    mpv_render_context *context = render_context_from_jlong(render_context_value);
    return context == NULL ? 0 : (jlong)mpv_render_context_update(context);
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateOpenGlRenderTarget(
    JNIEnv *env,
    jobject self,
    jint width,
    jint height
) {
    (void)env;
    (void)self;
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
        FUO_GL_TEXTURE_2D,
        0,
        FUO_GL_RGBA,
        width,
        height,
        0,
        FUO_GL_RGBA,
        FUO_GL_UNSIGNED_BYTE,
        NULL
    );

    global_gl_api.gen_framebuffers(1, &target->framebuffer);
    if (target->framebuffer == 0u) goto failure;
    global_gl_api.bind_framebuffer(FUO_GL_FRAMEBUFFER, target->framebuffer);
    global_gl_api.framebuffer_texture_2d(
        FUO_GL_FRAMEBUFFER,
        FUO_GL_COLOR_ATTACHMENT0,
        FUO_GL_TEXTURE_2D,
        target->texture,
        0
    );
    if (global_gl_api.check_framebuffer_status(FUO_GL_FRAMEBUFFER) != FUO_GL_FRAMEBUFFER_COMPLETE) {
        goto failure;
    }

    global_gl_api.bind_framebuffer(FUO_GL_FRAMEBUFFER, (fuo_gl_uint)previous_framebuffer);
    global_gl_api.bind_texture(FUO_GL_TEXTURE_2D, (fuo_gl_uint)previous_texture);
    return (jlong)(intptr_t)target;

failure:
    global_gl_api.bind_framebuffer(FUO_GL_FRAMEBUFFER, (fuo_gl_uint)previous_framebuffer);
    global_gl_api.bind_texture(FUO_GL_TEXTURE_2D, (fuo_gl_uint)previous_texture);
    destroy_gl_target(target);
    free(target);
    return 0;
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeOpenGlRenderTargetFramebuffer(
    JNIEnv *env,
    jobject self,
    jlong render_target_value
) {
    (void)env;
    (void)self;
    fuo_gl_target *target = (fuo_gl_target *)(intptr_t)render_target_value;
    return target == NULL ? 0 : (jint)target->framebuffer;
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeRenderOpenGl(
    JNIEnv *env,
    jobject self,
    jlong render_context_value,
    jlong render_target_value
) {
    (void)env;
    (void)self;
    mpv_render_context *context = render_context_from_jlong(render_context_value);
    fuo_gl_target *target = (fuo_gl_target *)(intptr_t)render_target_value;
    if (context == NULL || target == NULL) return;

    mpv_opengl_fbo fbo = {
        .fbo = (int)target->framebuffer,
        .w = target->width,
        .h = target->height,
        .internal_format = 0,
    };
    int flip_y = 0;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    mpv_render_context_render(context, params);
    if (ensure_gl_api()) global_gl_api.flush();
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeReportSwap(
    JNIEnv *env,
    jobject self,
    jlong render_context_value
) {
    (void)env;
    (void)self;
    mpv_render_context *context = render_context_from_jlong(render_context_value);
    if (context != NULL) mpv_render_context_report_swap(context);
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeDestroyOpenGlRenderTarget(
    JNIEnv *env,
    jobject self,
    jlong render_target_value
) {
    (void)env;
    (void)self;
    fuo_gl_target *target = (fuo_gl_target *)(intptr_t)render_target_value;
    if (target == NULL) return;
    destroy_gl_target(target);
    free(target);
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeRenderSoftware(
    JNIEnv *env,
    jobject self,
    jlong render_context_value,
    jint width,
    jint height,
    jint stride,
    jbyteArray pixels_value
) {
    (void)self;
    mpv_render_context *context = render_context_from_jlong(render_context_value);
    if (context == NULL || width <= 0 || height <= 0 || stride <= 0 || pixels_value == NULL) {
        return MPV_ERROR_INVALID_PARAMETER;
    }

    jsize pixel_length = (*env)->GetArrayLength(env, pixels_value);
    int64_t required = (int64_t)stride * (int64_t)height;
    if (required <= 0 || required > pixel_length) return MPV_ERROR_INVALID_PARAMETER;

    jboolean is_copy = JNI_FALSE;
    jbyte *pixels = (*env)->GetByteArrayElements(env, pixels_value, &is_copy);
    if (pixels == NULL) return MPV_ERROR_NOMEM;

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
    (*env)->ReleaseByteArrayElements(env, pixels_value, pixels, 0);
    return 0;
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeFreeRenderContext(
    JNIEnv *env,
    jobject self,
    jlong render_context_value
) {
    (void)env;
    (void)self;
    mpv_render_context *context = render_context_from_jlong(render_context_value);
    if (context != NULL) mpv_render_context_free(context);
}
