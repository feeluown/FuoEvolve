/* Stable C ABI consumed through Java FFM. */
#include <stdarg.h>

#define FUO_EVENT_BUFFER_FALLBACK "queue-overflow"

static int fuo_write_text(char *buffer, size_t capacity, const char *format, ...) {
    if (buffer == NULL || capacity == 0u || format == NULL) return 0;
    va_list arguments;
    va_start(arguments, format);
    int written = vsnprintf(buffer, capacity, format, arguments);
    va_end(arguments);
    if (written < 0) return 0;
    if ((size_t)written >= capacity) {
        size_t fallback_length = strlen(FUO_EVENT_BUFFER_FALLBACK);
        if (fallback_length + 1u > capacity) return 0;
        memcpy(buffer, FUO_EVENT_BUFFER_FALLBACK, fallback_length + 1u);
        return (int)fallback_length;
    }
    return written;
}

FUO_NATIVE_EXPORT int64_t fuo_mpv_create(void) {
    setlocale(LC_NUMERIC, "C");
    return (int64_t)(intptr_t)mpv_create();
}

FUO_NATIVE_EXPORT int fuo_mpv_initialize(int64_t handle_value) {
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
    return handle == NULL ? MPV_ERROR_INVALID_PARAMETER : mpv_initialize(handle);
}

FUO_NATIVE_EXPORT int fuo_mpv_set_option(
    int64_t handle_value,
    const char *name,
    const char *value
) {
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
    if (handle == NULL || name == NULL || value == NULL) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_set_option_string(handle, name, value);
}

FUO_NATIVE_EXPORT int fuo_mpv_set_property(
    int64_t handle_value,
    const char *name,
    const char *value
) {
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
    if (handle == NULL || name == NULL || value == NULL) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_set_property_string(handle, name, value);
}

/* Returns 0 for no value, a positive byte length on success, or the negative required byte length
 * when the caller-provided buffer is too small. */
FUO_NATIVE_EXPORT int fuo_mpv_get_property(
    int64_t handle_value,
    const char *name,
    char *buffer,
    size_t capacity
) {
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
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

FUO_NATIVE_EXPORT int fuo_mpv_command(int64_t handle_value, const char *const *args) {
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
    if (handle == NULL || args == NULL) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_command(handle, args);
}

FUO_NATIVE_EXPORT int fuo_mpv_observe_property(
    int64_t handle_value,
    uint64_t reply_userdata,
    const char *name
) {
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
    if (handle == NULL || reply_userdata == 0u || name == NULL) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_observe_property(handle, reply_userdata, name, MPV_FORMAT_STRING);
}

FUO_NATIVE_EXPORT int fuo_mpv_wait_observed_event(
    int64_t handle_value,
    double timeout_seconds,
    char *buffer,
    size_t capacity
) {
    (void)timeout_seconds;
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
    if (handle == NULL || buffer == NULL || capacity == 0u) return 0;
    const mpv_event *event = mpv_wait_event(handle, -1.0);
    if (event == NULL || event->event_id == MPV_EVENT_NONE) return 0;

    switch (event->event_id) {
        case MPV_EVENT_SHUTDOWN:
            fuo_observed_pending_entry_id = -1;
            fuo_observed_active_entry_id = -1;
            return fuo_write_text(buffer, capacity, "shutdown");
        case MPV_EVENT_START_FILE: {
            const mpv_event_start_file *start = (const mpv_event_start_file *)event->data;
            if (start == NULL) return 0;
            fuo_observed_pending_entry_id = start->playlist_entry_id;
            fuo_observed_active_entry_id = -1;
            return fuo_write_text(buffer, capacity, "start:%lld", (long long)start->playlist_entry_id);
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
            return fuo_write_text(buffer, capacity, "loaded");
        }
        case MPV_EVENT_PLAYBACK_RESTART:
            return fuo_write_text(buffer, capacity, "restart");
        case MPV_EVENT_END_FILE: {
            const mpv_event_end_file *end = (const mpv_event_end_file *)event->data;
            if (end == NULL) return 0;
            if (end->playlist_entry_id == fuo_observed_pending_entry_id) {
                fuo_observed_pending_entry_id = -1;
            }
            if (end->playlist_entry_id == fuo_observed_active_entry_id) {
                fuo_observed_active_entry_id = -1;
            }
            return fuo_write_text(
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
            ) return 0;
            const mpv_event_property *property = (const mpv_event_property *)event->data;
            const char *value = "";
            if (property != NULL && property->format == MPV_FORMAT_STRING && property->data != NULL) {
                const char *native_value = *(char **)property->data;
                if (native_value != NULL) value = native_value;
            }
            return fuo_write_text(
                buffer,
                capacity,
                "property:%llu:%s",
                (unsigned long long)event->reply_userdata,
                value
            );
        }
        case MPV_EVENT_QUEUE_OVERFLOW:
            fuo_observed_activate_current_after_overflow(handle);
            return fuo_write_text(buffer, capacity, "queue-overflow");
        default:
            return 0;
    }
}

FUO_NATIVE_EXPORT void fuo_mpv_wakeup(int64_t handle_value) {
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
    if (handle != NULL) mpv_wakeup(handle);
}

FUO_NATIVE_EXPORT void fuo_mpv_destroy(int64_t handle_value) {
    mpv_handle *handle = fuo_handle_from_i64(handle_value);
    if (handle != NULL) mpv_terminate_destroy(handle);
}

FUO_NATIVE_EXPORT int fuo_mpv_error_string(int error, char *buffer, size_t capacity) {
    const char *value = mpv_error_string(error);
    if (value == NULL || buffer == NULL || capacity == 0u) return 0;
    size_t length = strlen(value);
    if (capacity <= length) return -(int)length;
    memcpy(buffer, value, length + 1u);
    return (int)length;
}

FUO_NATIVE_EXPORT int fuo_mpv_render_software(
    int64_t render_context_value,
    int width,
    int height,
    int stride,
    unsigned char *pixels,
    size_t pixel_length
) {
    mpv_render_context *context = fuo_render_context_from_i64(render_context_value);
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
