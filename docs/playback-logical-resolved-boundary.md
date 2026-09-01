# Logical track and resolved playback source

Playback has two different identities that must not be represented by the same mutable `MusicTrack`.

- **Logical track**: the queue item the user selected. Queue navigation, history primary identity, lyrics association and track actions use this identity.
- **Resolved playback source**: the physical media selected to render that logical track. Smart/manual replacement, provider source, URL and replacement score belong here.

The feature playback invariant is:

> `PlaybackQueueController` and `PlaybackState.currentTrack` contain logical identity only. Physical source identity is exposed through `PlaybackState.resolvedSource`.

`MusicTrack` still contains the legacy `original*` / `replacement*` fields for compatibility with provider resolution, Android Media3 session metadata and persisted data created by older versions. Those fields are resolver/platform adapter inputs only and must be normalized with `logicalPlaybackTrack()` before entering queue/business state.

During this migration, platform engines may still emit a replacement-decorated `MusicTrack`. `DefaultPlaybackFeatureOwner` treats this as a compatibility transport format: it derives `ResolvedPlaybackSource`, normalizes the track back to logical identity, then publishes the feature-owned playback state.

This separation intentionally precedes the startup/restore state-machine migration. Queue snapshot and Android resume-session unification remain a separate follow-up so source identity cleanup does not become coupled to persistence restoration behavior.
