#include <jni.h>
#include <locale.h>
#include <mpv/client.h>
#include <mpv/render.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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

    jbyte *pixels = (*env)->GetPrimitiveArrayCritical(env, pixels_value, NULL);
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
    int result = mpv_render_context_render(context, params);
    (*env)->ReleasePrimitiveArrayCritical(env, pixels_value, pixels, 0);
    return result;
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
