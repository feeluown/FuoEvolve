/* Event-driven libmpv property bridge shared by audio and video controllers.
 *
 * Kotlin keeps polling mpv_wait_event(), but property values are pushed into that queue by
 * mpv_observe_property() instead of being synchronously re-read on a timer. This preserves the
 * one-way JNI model: native code never calls back into managed classes.
 */

static jint fuo_mpv_observe_string_property(
    JNIEnv *env,
    jlong handle_value,
    jlong reply_userdata,
    jstring name_value
) {
    mpv_handle *handle = handle_from_jlong(handle_value);
    char *name = jstring_to_utf8(env, name_value);
    if (handle == NULL || name == NULL || reply_userdata <= 0) {
        free(name);
        return MPV_ERROR_INVALID_PARAMETER;
    }
    int result = mpv_observe_property(
        handle,
        (uint64_t)reply_userdata,
        name,
        MPV_FORMAT_STRING
    );
    free(name);
    return result;
}

static jstring fuo_mpv_observed_event_string(
    JNIEnv *env,
    uint64_t reply_userdata,
    const char *value
) {
    if (value == NULL) value = "";
    int required = snprintf(
        NULL,
        0,
        "property:%llu:%s",
        (unsigned long long)reply_userdata,
        value
    );
    if (required < 0) return NULL;
    char *buffer = (char *)malloc((size_t)required + 1u);
    if (buffer == NULL) return NULL;
    snprintf(
        buffer,
        (size_t)required + 1u,
        "property:%llu:%s",
        (unsigned long long)reply_userdata,
        value
    );
    jstring result = utf8_to_jstring(env, buffer);
    free(buffer);
    return result;
}

static jstring fuo_mpv_wait_event_observed(
    JNIEnv *env,
    jlong handle_value,
    jdouble timeout_seconds
) {
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
        case MPV_EVENT_PROPERTY_CHANGE: {
            const mpv_event_property *property = (const mpv_event_property *)event->data;
            const char *value = NULL;
            if (
                property != NULL &&
                property->format == MPV_FORMAT_STRING &&
                property->data != NULL
            ) {
                value = *(char **)property->data;
            }
            return fuo_mpv_observed_event_string(env, event->reply_userdata, value);
        }
        case MPV_EVENT_QUEUE_OVERFLOW:
            return (*env)->NewStringUTF(env, "queue-overflow");
        default:
            return NULL;
    }
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeObserveProperty(
    JNIEnv *env,
    jobject self,
    jlong handle_value,
    jlong reply_userdata,
    jstring name_value
) {
    (void)self;
    return fuo_mpv_observe_string_property(env, handle_value, reply_userdata, name_value);
}

JNIEXPORT jstring JNICALL
Java_org_feeluown_mobile_nucleus_JniMpvApi_nativeWaitObservedEvent(
    JNIEnv *env,
    jobject self,
    jlong handle_value,
    jdouble timeout_seconds
) {
    (void)self;
    return fuo_mpv_wait_event_observed(env, handle_value, timeout_seconds);
}

JNIEXPORT jint JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeObserveProperty(
    JNIEnv *env,
    jobject self,
    jlong handle_value,
    jlong reply_userdata,
    jstring name_value
) {
    (void)self;
    return fuo_mpv_observe_string_property(env, handle_value, reply_userdata, name_value);
}

JNIEXPORT jstring JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeWaitObservedEvent(
    JNIEnv *env,
    jobject self,
    jlong handle_value,
    jdouble timeout_seconds
) {
    (void)self;
    return fuo_mpv_wait_event_observed(env, handle_value, timeout_seconds);
}
