# Refined Expressive — design foundation

Material 3 Expressive remains the component and motion foundation. This document defines FuoEvolve's visual semantics; it does not introduce a separate palette or override user-selected theme settings.

## Principles

1. Music artwork is the focal point. Avoid global blur, animated background layers and ornamental elevation.
2. Use Material `ColorScheme` roles rather than hard-coded light or dark surfaces; preserve system dynamic colors, all user presets and contrast checks.
3. Make hierarchy visible through type, whitespace and tone before adding dividers, borders or shadows.
4. Keep action targets at least 48 dp and preserve readable text at increased font scale.
5. Reserve expressive shapes and large motion for meaningful transitions and primary playback interactions.

## Token ownership

`FuoVisualTokens` in `core/ui/FuoVisualTokens.kt` owns shape and typography values. `FuoSurfaceColors` maps semantic surfaces to the **active** Material color scheme. `FuoElevation` is used only for transient/floating UI. Shared components should use those tokens rather than creating their own shape/color/elevation constants.

| Role | Shape | Color role | Elevation |
| --- | --- | --- | --- |
| Artwork | 12 dp | actual artwork | 0 dp |
| Content surface | 16 dp | `surfaceContainerLow` | 0 dp |
| Group surface | 20 dp | `surfaceContainerLow` | 0 dp |
| Interactive surface | component shape | `surfaceContainer` | 0 dp |
| Selected element | component shape | `secondaryContainer` | 0 dp |
| Floating player | 24 dp | `surfaceContainerHigh` | 2 dp |
| Modal overlay | 28 dp | Material dialog/sheet colors | 3 dp where required |

Spacing follows the existing `FuoSpacing` scale of 4, 8, 12, 16, 24 and 32 dp; prefer a predictable 16 dp content gutter rather than ad-hoc values. Type emphasis is limited to display, headline and title; body and metadata stay regular to keep long music titles readable.

## Color behavior

Fuo Green (`#246B43`) is the brand seed, not a literal control/surface color. Continue generating semantic colors with Material Kolor, and do not force green on users who choose dynamic wallpaper colors or another palette. Album-art-derived colors are scoped to playback, with existing loading and contrast safeguards preserved.

## Motion

Consume `FuoMotion` and `MaterialTheme.motionScheme` for new components. Avoid introducing further hard-coded tweens; keep existing constants only for compatibility until their call sites are migrated. Do not animate large blurred surfaces or start permanent animations in list cells.

## Implementation phases

### Phase 1 — foundation

The shared `MaterialExpressiveTheme` installs the typography and shape tokens. Shared section cards, list rows and empty states use semantic surfaces and group shapes, without changing feature state or navigation.

### Phase 2 — first impressions

- The recommendation and discovery pages now begin with a compact editorial introduction; existing source content, actions, login prompts and pull-to-refresh remain intact.
- Content modules use predictable 16 dp spacing on compact screens and 24 dp on wide layouts. Song rows rely on spacing rather than a divider after every track.
- Personalized recommendation tiles use two columns on compact screens, and at most five on wide layouts. Large decorative icon tiles use neutral interactive surfaces so album artwork carries the visual emphasis.
- The mini player uses the floating surface role, 24 dp rounded container, 12 dp artwork corners and 2 dp elevation. Its measured height and the existing shared-element/lyrics/playback behavior remain intact.
- Shared full-player controls use a prominent rounded-square play button, Material motion for artwork and progress, muted timing labels, and semantic surfaces for playback parts. Full-player navigation, queue and seek callbacks are unchanged.

Phase 2 deliberately avoids platform-window changes, heavy blur and changes to playback/domain state. Further full-screen layout refinements and other feature pages can be handled separately rather than mixing them with the visual-system foundation.

## Verification

Run the repository PR test workflows on Android, iOS, Linux, macOS and Windows, and inspect compact/wide layouts, large text, light/dark themes, system dynamic color, alternate theme presets and cover-derived player colors before declaring the visual update ready for review.
