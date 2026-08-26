# Listening history and statistics roadmap

## Goal

FuoEvolve owns a provider-independent listening history that powers recent playback, frequently played
resources, rolling statistics, listening insights, recommendation inputs, backup, and later cross-device
continuity.

The storage model is event-first. We persist what actually happened during playback and derive
user-facing rankings from those facts instead of permanently collapsing history into a single
`playCount`.

## Phase 1: persistence foundation — implemented

- Add a provider-neutral `ListeningHistoryRecord` contract in `:core:model`.
- Record one logical track playback transaction from playback state transitions; replaying or actively
  selecting the same logical track creates another event, while a true resume continues the event.
- Keep multipart playback-part transitions inside the same logical event; multipart queue behavior is
  not converted into artificial extra track plays.
- Accumulate only time spent in `PlayerStatus.Playing`; paused, loading, and buffering time is excluded.
- Checkpoint active sessions every 30 seconds and on pause/non-playing transitions so process death
  loses at most the latest in-memory interval in normal operation.
- Finalize sessions on natural end, track change, a new playback transaction, stop/idle, or playback error.
- Keep smart replacement as one logical track while also storing the resolved replacement as a
  separate relation.
- Store resource dimensions independently from events so a single event can relate to a track,
  artists, album, playlist context, feature context, search context, local directory, and resolved source.
- Persist playback start reason when it is available from the queue transaction.
- Introduce `:persistence:listening` using SQLDelight 2.3.2. The schema and storage implementation are
  Kotlin Multiplatform; Android is wired into the current process composition and an iOS driver factory
  is available for later iOS bootstrap wiring.

### Qualified playback

Raw events are never discarded just because they are short. `qualified` is a derived flag stored with
each checkpoint/final record:

- known duration longer than 30 seconds: qualified after 50% or 4 minutes, whichever comes first;
- known duration of 30 seconds or less: not qualified;
- unknown duration: use 30 seconds of actual playing time as a fallback.

Recent-history UI can therefore retain short activity while frequent/statistical views use
`qualified = true`.

### Legacy playlist stats

`AppSettings.playlistPlaybackStats` is not migrated. It does not contain event timestamps or listening
duration, so synthesizing history would corrupt rolling-window statistics. The new listening database
starts fresh. The old map remains only as a compatibility input for the pre-existing Mine playlist
ordering and can be deleted independently once that ordering is moved to the new read model.

## Phase 2: recent playback read model — implemented

The same database now exposes provider-neutral read contracts; no second history store is introduced.

- `ListeningHistoryRepository.recentEvents` provides the raw chronological activity timeline.
- `recentResources` provides latest-use projections for tracks and related resource dimensions.
- Mine has a dedicated `听歌` entry with `最近 / 常听 / 统计` pages.
- Recent activity is grouped by UTC civil day and can be filtered to songs, artists, albums, playlists,
  and feature/recommendation resources.
- Playback now carries an explicit ephemeral `PlaybackContextSnapshot` plus a monotonically increasing
  context sequence. Child tracks from one source session share a stable `contextSessionKey`; starting a
  new source context creates another context session.
- Up-next insertions deliberately do not inherit the playlist/feature context session.
- Static feature/recommendation contexts and search contexts are preserved. Local playlist callers pass
  stable source/title metadata through the rich playlist overload; legacy playlist callers retain an
  identifier fallback for source compatibility.
- `PlaybackContextSnapshot` contains source, resource id, title, subtitle, and cover so provider/detail
  callers can enrich playlist/album/artist contexts without changing the event schema.

## Phase 3: frequently played and rolling statistics — implemented

`ListeningTimeRange` is the generic inclusive-start/exclusive-end read boundary. It supports rolling
N-day windows, calendar month/year ranges supplied by callers, arbitrary start/end ranges, and all time.
The Mine UI exposes the planned presets: `7 天 / 30 天 / 90 天 / 今年 / 全部`.

All rankings are derived from the same events and relations:

- tracks: qualified play count, actual played duration, event count, and last played time;
- artists and albums: derived through event-resource relations;
- playlists/features: qualified child count, played duration, and distinct `contextSessionKey` count;
- local directories: derived through the local-directory relation;
- future videos/episodes/podcasts can use the same event/read contracts without a new history table.

Default frequent ordering is intentionally explainable: qualified count first, then played duration,
then last played time. Raw short events remain visible in recent history but do not inflate frequent
rankings.

## Phase 4: listening insights and personalization — implemented read model

`ListeningHistoryRepository.insights` derives higher-level data without changing the write path:

- Replay-style summaries for the selected range, including the `今年` yearly view;
- active listening days and total actual listening duration;
- frequently played tracks/artists/albums/playlists/features through the Phase 3 projection;
- actual playback source share. Smart-replaced events are attributed to the resolved source when one
  exists rather than incorrectly crediting the logical source;
- user-selected (`UserSelection` / `PlaylistReplace`) versus automatic (`AutoNext`) playback counts;
- fixed-size time buckets for listening trends;
- recommendation seeds from explainable top qualified tracks;
- an explicit high-frequency set (currently qualified plays >= 10 inside the selected range) that can
  be used as an overplay-suppression/down-ranking input.

The Mine `统计` page surfaces these primitives directly. Recommendation algorithms remain consumers of
this read model; they do not write another taste-history format.

## Identity and smart replacement

Raw history keeps provider/resource identity and both logical and resolved tracks. A later
`CanonicalTrackIdentity` can group equivalent tracks across NetEase, QQ Music, YouTube Music,
Bilibili, local files, and replacement sources.

Canonical identity belongs above raw history rather than rewriting old events. That keeps history
auditable and lets matching quality improve over time.

## Privacy, backup, and lifecycle

Listening history is local application data. Remaining lifecycle work is intentionally independent of
Phases 2–4:

- expose clear-history in settings (the repository already implements `clear()`);
- export/import as part of the broader Fuo backup format;
- optional private-session / do-not-record mode;
- wire the existing SQLDelight native driver into the iOS process composition instead of the current
  explicit no-op history repository;
- add a retention policy only if real storage pressure appears; SQLite can comfortably retain years of
  normal playback events.
