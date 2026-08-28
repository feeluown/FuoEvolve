# UI Motion System

FuoEvolve uses Material 3 Expressive motion as the source of truth for app interaction and transition animation.

## Goals

- Make frequent direct manipulation feel responsive without adding decorative motion everywhere.
- Keep custom transitions consistent with Material components.
- Avoid screen-local duration/easing constants when a semantic motion role exists.
- Keep one theme-level seam so animation pacing can later be changed from Settings.

## Motion roles

Custom UI should request animation specs through `FuoMotion` instead of constructing local `tween`/`spring` specs.

| Role | Intended use |
| --- | --- |
| `fastSpatialSpec` | press feedback, small control movement, compact state changes |
| `defaultSpatialSpec` | page navigation, overlays, component size/layout changes |
| `slowSpatialSpec` | large visual movement such as artwork changes |
| `fastEffectsSpec` | small opacity/progress feedback |
| `defaultEffectsSpec` | content replacement and artwork fades |
| `slowEffectsSpec` | deliberately prominent visual effects; use sparingly |

Spatial specs are for movement, size, bounds, and shape-related transitions. Effects specs are for visual-property changes such as alpha or progress.

## Interaction priority

Motion should be added in this order:

1. Playback controls, mini player, seek/progress and queue actions.
2. Primary navigation, search entry points and settings/actions used from the app shell.
3. Common clickable cards and list rows.
4. Content-state changes such as loading, selected state and expandable content.
5. Low-frequency detail screens only where motion improves continuity.

Pure display content should remain still unless animation communicates a state transition.

## Press feedback

Common interactive surfaces use `fuoPressFeedback` with the same `MutableInteractionSource` as the clickable/selectable modifier. The default scale is intentionally subtle so full-width rows do not visually jump.

Use the more prominent scale only for compact, visually raised surfaces such as the mini player. Material buttons keep their built-in interaction behavior unless a custom interaction specifically needs extra feedback.

## Hero transitions

Hero transitions are reserved for a stable visual object that exists on both sides of a navigation or presentation change. They complement the existing page transition instead of replacing it.

- Provider playlist, album, and artist cards share only their cover image with the corresponding detail header.
- Resource identity remains resource type + provider ID + resource ID. A per-card source instance token disambiguates duplicate renderings of the same resource on one page so forward and pop transitions return to the card that was actually clicked.
- Source instance tokens are derived deterministically from the resource container's stable Compose composition key plus the resource occurrence within that container. Recreating the source page therefore produces the same shared-element key instead of a new random token.
- The source instance token belongs only to the UI Hero layer and is not added to provider/domain models or navigation routes.
- Navigation Hero elements use the app-level `SharedTransitionLayout` together with Navigation3's `LocalNavAnimatedContentScope`, so normal pop and predictive-back navigation can drive the same shared element.
- Playlist, album, and artist detail loading progress is overlay-only and does not remeasure the Hero header. The destination keeps a stable target position while refreshed metadata arrives.
- The full player intentionally does not participate in shared-cover Hero transitions; it keeps the normal overlay enter/exit motion.

Feature entries without a real cover and track rows whose primary click action is playback should not be forced into a Hero transition merely for decoration.

## Animation speed setting

A future setting should change the motion scheme provided at the app theme boundary rather than multiply durations inside individual composables. `FuoMotion` consumers then inherit the selected pacing automatically.

Suggested user-facing presets:

- Reduced: remove non-essential movement and make state changes immediate or near-immediate.
- Faster: remap default motion toward fast roles.
- Standard: Material 3 Expressive defaults.
- Relaxed: remap fast/default motion toward slower roles while keeping interaction feedback responsive.

Before exposing the setting, migrate the remaining legacy duration tokens and screen-local animation specs so the preference has predictable app-wide coverage.

## Migration rule

New UI code should not introduce a fixed animation duration unless the timing represents a domain requirement rather than presentation. Existing fixed durations can be migrated incrementally; `FuoMotion` keeps temporary legacy constants only until those call sites are removed.
