/* Event-driven libmpv property state shared by the FFM playback and video controllers.
 *
 * Property events do not carry a playlist entry id, so keep a small event-thread-local lifecycle
 * gate. A property is forwarded only after START_FILE and the matching FILE_LOADED have both been
 * observed for the entry that mpv currently reports as playing.
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
