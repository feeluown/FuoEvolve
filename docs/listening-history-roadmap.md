# Listening history and statistics roadmap

## Goal

FuoEvolve should own a provider-independent listening history that can later power recent playback,
frequently played resources, rolling N-day statistics, listening insights, recommendations, backup,
and cross-device continuity.

The storage model is intentionally event-first. We persist what actually happened during playback and
derive user-facing rankings later instead of permanently collapsing history into a single `playCount`.

## Phase 1: persistence foundation

This phase is implemented by the initial listening-history PR.

- Add a provider-neutral `ListeningHistoryRecord` contract in `:core:model`.
- Record one logical track playback transaction from playback state transitions; replaying or actively
  selecting the same logical track creates another event, while a true resume continues the event.
- Accumulate only time spent in `PlayerStatus.Playing`; paused, loading, and buffering time is excluded.
- Checkpoint active sessions every 30 seconds and on pause/non-playing transitions so process death
  loses at most the latest in-memory interval in normal operation.
- Finalize sessions on natural end, track change, a new playback transaction, stop/idle, or playback error.
- Keep smart replacement as one logical track while also storing the resolved replacement as a
  separate relation.
- Store resource dimensions independently from events so a single event can relate to a track,
  artists, album, playlist context, feature context, local directory, and resolved source.
- Persist playback start reason when it is available from the queue transaction.
- Introduce `:persistence:listening` using SQLDelight 2.3.2. The schema and storage implementation are
  Kotlin Multiplatform; Android is wired into the current process composition in this phase and an
  iOS driver factory is already provided for later iOS bootstrap wiring.

### Qualified playback

Raw events are never discarded just because they are short. `qualified` is a derived flag stored with
each checkpoint/final record:

- known duration longer than 30 seconds: qualified after 50% or 4 minutes, whichever comes first;
- known duration of 30 seconds or less: not qualified;
- unknown duration: use 30 seconds of actual playing time as a fallback.

This lets a future recent-history UI use a lower threshold (for example 5 seconds) without losing raw
facts, while frequent/statistical views can use `qualified = true`.

### Legacy playlist stats

`AppSettings.playlistPlaybackStats` is not migrated. It does not contain event timestamps or listening
duration, so synthesizing history would corrupt rolling-window statistics. The old map can continue to
serve the existing Mine playlist ordering until the new read model replaces it, then be deleted.

The new database starts fresh from the version that introduces this feature.

## Phase 2: recent playback read model

Add read/query contracts on top of the same database rather than another storage system.

- Recent activity timeline ordered by event time and grouped by day.
- Recent resources projection using the latest event per resource.
- Initial Mine entry: `最近播放` with a full history page.
- Allow filtering by resource type when useful.
- Add explicit playback-context sessions so playlist/feature listening sessions can be counted without
  equating every child-track play to another playlist play.
- Enrich playlist context with stable source/provider/title/cover metadata instead of the phase-1
  identifier-only fallback.
- Preserve static feature/recommendation/search/album/artist contexts, not only the dynamic queue
  context currently retained by playback.

## Phase 3: frequently played and rolling statistics

Expose a generic time range instead of hard-coding individual screens:

- rolling N days;
- calendar month;
- calendar year;
- arbitrary start/end range;
- all time.

Initial UI presets should be `7 天 / 30 天 / 90 天 / 今年 / 全部`.

Aggregate the same events across multiple resource types:

- tracks and videos: qualified play count, played duration, last played time;
- artists and albums: derived through event-resource relations;
- playlists/features: context session count, qualified child item count, played duration;
- local directories: derived through resource relations;
- future episodes/podcasts: use the same event and relation model without changing the base event table.

Default frequent ranking should remain explainable: qualified count first, then played duration, then
last played time. Time-decay scoring can be added later if product behavior warrants it.

## Phase 4: listening insights and personalization

Once enough history exists, derive higher-level product capabilities without changing the write path:

- monthly/yearly Replay-style summaries;
- active listening days and total listening duration;
- frequently played artists/albums/playlists;
- source/provider share of actual playback;
- user-selected vs automatic playback weighting;
- recommendation seeds and overplay suppression;
- listening trends over time.

## Identity and smart replacement

Phase 1 stores provider/resource identity and keeps both logical and resolved tracks. Later
`CanonicalTrackIdentity` can group equivalent tracks across NetEase, QQ Music, YouTube Music,
Bilibili, local files, and replacement sources.

Canonical identity should be introduced above raw history rather than rewriting old events. That keeps
history auditable and lets matching quality improve over time.

## Privacy, backup, and lifecycle

Listening history is local application data. Later product work should add:

- clear listening history;
- export/import as part of the broader Fuo backup format;
- optional private-session / do-not-record mode;
- iOS process bootstrap wiring;
- retention policy only if real storage pressure appears; SQLite can comfortably retain years of
  normal playback events.
