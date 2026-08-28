# Shared common-source architecture

This file applies to `shared/src/commonMain/kotlin/org/feeluown/mobile/**`.

## Directory dependency direction

The Kotlin package is intentionally still `org.feeluown.mobile`, so package visibility does not enforce the logical layers. Treat source location as an architectural boundary:

`app/ -> feature/ -> core/`

- `app/` owns application composition, typed navigation, app-scoped state and adapters that bind feature/core contracts.
- `feature/` owns feature UI, state projection and feature-local composition context.
- `core/` owns reusable cross-feature contracts and shared UI primitives/context.

Code in `core/` must not read declarations owned by `app/`. Feature code should receive app behavior through explicit callbacks/ports or consume a lower-layer UI contract; it must not use app-shell declarations as a service locator.

## CompositionLocal ownership

A `CompositionLocal` belongs to the lowest layer that owns the contract, not the highest layer that happens to provide its runtime value.

- Cross-feature UI context belongs under `core/ui/`.
- Feature-private UI context belongs in that feature.
- App-only context may remain under `app/` only when no feature/core consumer reads it.
- `AppShell` may provide core/feature-owned locals; providing a value does not make the contract app-owned.

Examples of cross-feature core UI context include layout information, share handling and shared-transition scopes. Do not move these declarations into `app/` merely because `AppShell` supplies their values.

## Verification

Run:

```text
./gradlew checkArchitectureBoundaries
```

The architecture gate discovers `Local*` values declared with `staticCompositionLocalOf` or `compositionLocalOf` under `app/` and rejects references to those app-owned ambients from `feature/` or `core/`. The check is run by both Android and iOS CI.
