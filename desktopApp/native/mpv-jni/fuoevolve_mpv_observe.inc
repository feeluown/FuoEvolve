/* Event-driven libmpv property bridge shared by audio and video controllers.
 *
 * Property values are pushed into mpv's client event queue by mpv_observe_property(). The JNI
 * event thread blocks in mpv_wait_event() until work arrives; close paths already call
 * mpv_wakeup(), so no periodic timeout is needed. This preserves the one-way JNI model: native
 * code never calls back into managed classes.
 *
 * Property events do not carry a playlist entry id, so keep a small event-thread-local lifecycle
 * gate. A property is forwarded only after START_FILE and the matching FILE_LOADED have both been
 * observed for the entry that mpv currently reports as playing. This prevents queued properties
 * from a replaced source from being reinterpreted as state for the new source.
 */

static _Thread_local int64_t fuo_observed_pending_entry_id = -1;
static _Thread_local int64_t fuo_observed_active_entry_id = -1;

static int fuo_mpv_current_playlist_entry_id(mpv_handle *handle, int64_t *entry_id) {
    if (handle == NULL || entry_id == NULL) return 0;

    int64_t playing_position = -1;
    if (
        mpv_get_property(
            handle,
            "playlist-playing-pos",
            MPV_FORMAT_INT64,
            &playing_position
        ) < 0 ||
        playing_position < 0
    ) {
        return 0;
    }

    char property_name[64];
    int written = snprintf(
        property_name,
        sizeof(property_name),
        "playlist/%lld/id",
        (long long)playing_position
    );
    if (written <= 0 || (size_t)written >= sizeof(property_name)) return 0;

    int64_t current_entry_id = -1;
    if (
        mpv_get_property(handle, property_name, MPV_FORMAT_INT64, &current_entry_id) < 0 ||
        current_entry_id < 0
    ) {
        return 0;
    }
    *entry_id = current_entry_id;
    return 1;
}

static void fuo_observed_activate_current_after_overflow(mpv_handle *handle) {
    int64_t current_entry_id = -1;
    if (fuo_mpv_current_playlist_entry_id(handle, &current_entry_id)) {
        fuo_observed_active_entry_id = current_entry_id;
    } else {
        fuo_observed_active_entry_id = -1;
    }
    fuo_observed_pending_entry_id = -1;
}

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
    (void)timeout_seconds;

    const mpv_event *event = mpv_wait_event(handle, -1.0);
    if (event == NULL || event->event_id == MPV_EVENT_NONE) return NULL;

    char buffer[192];
    switch (event->event_id) {
        case MPV_EVENT_SHUTDOWN:
            fuo_observed_pending_entry_id = -1;
            fuo_observed_active_entry_id = -1;
            return (*env)->NewStringUTF(env, "shutdown");
        case MPV_EVENT_START_FILE: {
            const mpv_event_start_file *start = (const mpv_event_start_file *)event->data;
            if (start == NULL) return NULL;
            fuo_observed_pending_entry_id = start->playlist_entry_id;
            fuo_observed_active_entry_id = -1;
            snprintf(buffer, sizeof(buffer), "start:%lld", (long long)start->playlist_entry_id);
            return (*env)->NewStringUTF(env, buffer);
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
            return (*env)->NewStringUTF(env, "loaded");
        }
        case MPV_EVENT_PLAYBACK_RESTART:
            return (*env)->NewStringUTF(env, "restart");
        case MPV_EVENT_END_FILE: {
            const mpv_event_end_file *end = (const mpv_event_end_file *)event->data;
            if (end == NULL) return NULL;
            if (end->playlist_entry_id == fuo_observed_pending_entry_id) {
                fuo_observed_pending_entry_id = -1;
            }
            if (end->playlist_entry_id == fuo_observed_active_entry_id) {
                fuo_observed_active_entry_id = -1;
            }
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
            int64_t current_entry_id = -1;
            if (
                fuo_observed_active_entry_id < 0 ||
                !fuo_mpv_current_playlist_entry_id(handle, &current_entry_id) ||
                current_entry_id != fuo_observed_active_entry_id
            ) {
                return NULL;
            }

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
            fuo_observed_activate_current_after_overflow(handle);
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
