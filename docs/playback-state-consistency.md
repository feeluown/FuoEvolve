# Playback state consistency

This document records the invariants enforced by the playback architecture while the runtime/queue migration is in progress.

## Invariants

- Platform engine state is authoritative for the media item that is actually playing.
- Queue/presentation state may lag behind the platform temporarily, but it must never replace a newer engine media identity.
- Lyrics are only exposed when their logical track identity matches the current engine track.
- Playback generation identifies one playback transaction. Track transitions inside the same generation are valid and expected for preloaded auto-advance.
- States from older generations must never replace the active transaction.

## Migration direction

The remaining queue bridge in `DefaultPlaybackRuntime` is temporary. Queue entries should eventually carry stable per-entry identity so duplicate logical tracks can be distinguished without searching by `trackId`.
