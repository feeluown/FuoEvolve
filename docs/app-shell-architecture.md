# App shell architecture

The common app shell separates state ownership, presentation wiring, back coordination and platform capabilities.

## Ownership

- `FuoAppViewModel` owns only app-scoped startup/theme/navigation projection, global app feedback and application lifecycle/navigation events.
- `AppUiGraph` is composition-only wiring. It groups existing feature owners and narrow UI ports without owning business state or dispatching generic actions.
- `AppBackCoordinator` owns transient-overlay back precedence and typed-route close coordination so Android/iOS hosts do not know individual overlay implementations.
- `AppPlatformBindings` is the explicit boundary for permissions, file pickers/export/share and provider web-login callbacks supplied by platform hosts.

## Compose shell

`AppRoot` is the bootstrap entry only. After initialization/onboarding it delegates to `AppShell`.

`AppShell` installs composition-local UI graphs and contains four focused pieces:

- `AppNavHost` — exhaustive typed-route rendering and navigation transitions;
- `AppGlobalOverlays` — FullPlayer and app-global dialogs/pickers;
- `AppFeedbackHost` — lifecycle-aware aggregation of feature-local transient feedback;
- `AppShellComposition` — responsive-layout and mini-player visibility policy.

Feature state remains in the feature owner. The shell only observes state required to compose routes, global overlays and layout policy.

## Composition roots

`AndroidAppContainer` and `IosAppContainer` construct the feature owners, `AppUiGraph`, `AppBackCoordinator` and the narrowed `FuoAppViewModel`. Platform activities/view-controller hosts consume `AppUiGraph` directly for platform integration instead of treating the ViewModel as a feature service locator.

The dependency direction remains:

`platform composition root -> app/shared integration -> feature owner -> stable core/api/persistence contracts`
