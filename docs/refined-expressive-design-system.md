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

## Phase boundaries

Phase 1 introduces/uses design tokens and shared primitives. Home layout, full player composition, platform-specific window architecture and feature flows belong to later phases. Validate on Android and desktop, and with light/dark/dynamic and fixed palettes before marking this change ready for review.
