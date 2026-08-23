# P5 provider modularization

P5 turns concrete music sources into independently compilable Kotlin Multiplatform modules. The goal is a static provider-plugin boundary, not runtime plugin discovery.

## Dependency direction

The provider graph is:

```text
androidApp / iOS host
        |
        v
      shared
 app shell / UI / composition
        |
        +------------+------------+------------+
        v            v            v            v
provider:netease provider:qqmusic provider:bilibili provider:ytmusic
        |            |            |            |
        +------------+------^-----+------------+
                            |
                            v
                    provider:runtime
                      |           |
                      v           v
                 provider:api  playback:api
                      |
                      v
                   core:model
```

Concrete provider modules may depend on `:provider:runtime` and lower stable contracts. They must not depend on `:shared`, `:feature:*`, application modules, persistence modules, or sibling concrete providers.

## Static construction SPI

`:provider:runtime` owns the provider construction SPI:

- `ProviderRuntimeDependencies` carries the shared HTTP client and credential store;
- `KotlinProviderFactory` creates one provider from those runtime dependencies;
- concrete modules export one factory entry;
- `shared/ProviderComposition.kt` is the only production composition point that imports all concrete provider factories.

Registration is intentionally compile-time. FuoEvolve does not use `ServiceLoader`, reflection-based discovery, Koin/Hilt, or a runtime plugin loader for provider construction.

## Concrete modules

### `:provider:bilibili`

Owns Bilibili provider/content implementations and provider-specific tests. The Bilibili extraction established the module template and moved genuinely shared hashing helpers into `:provider:runtime`.

### `:provider:netease`

Owns NetEase provider, exploration/WeAPI implementation and secure-random abstraction. The Android/iOS `expect`/`actual` secure-random implementations move with the provider so the module is independently KMP-compilable.

### `:provider:qqmusic`

Owns QQ Music provider/content/explore/user-library/artist-detail/QRC implementation, platform QRC inflater implementations and provider-specific tests.

QQ-specific account-library and artist-enrichment behavior is composed by a module-local `QQMusicCompositeProvider`; these helpers do not leak into the shared application layer.

### `:provider:ytmusic`

Owns YTMusic provider, content provider, OAuth client and provider-specific tests. Device-code OAuth is exposed through the provider-neutral `ProviderDeviceAuthorizationCapability` in `:provider:runtime`, so shared orchestration never casts to YTMusic concrete types.

## Shared lower utilities

Rich provider lyric transport composition (`composeRichLyrics`) lives in `:core:model` because NetEase and QQ Music both produce that stable transport format. UI parsing and localized presentation remain application-owned.

`:provider:runtime` retains reusable network/cache/retry/credential/failure infrastructure. Provider-specific algorithms stay in their concrete module unless reuse is demonstrated by more than one provider.

## Application composition

`KotlinProviderRepository` receives a collection/map of providers created through `ProviderComposition` rather than constructing concrete providers itself. Provider lookup is collection-driven by provider id; application orchestration does not branch on concrete provider classes.

The only supported-provider list is the static factory list in `ProviderComposition.kt`. Adding another provider therefore requires a new provider module/factory plus one registration entry, not edits throughout repository/UI orchestration.

## Tests and architecture fitness

Each concrete provider module owns an architecture gate that rejects dependencies back to `:shared`, apps/features/persistence, or sibling concrete provider modules and rejects moving its implementation back under `shared/provider/...`.

Provider-specific tests run from their provider module. `:shared:allTests` depends on all concrete provider test suites so Android/iOS CI validates provider modules together with application integration.

P5 is complete when:

- all four concrete providers live outside `:shared`;
- each provider module can compile/test without depending on `:shared`;
- only the application composition point knows all concrete provider factories;
- provider-specific platform `expect`/`actual` implementations live with their provider;
- provider-neutral OAuth/device authorization is expressed through runtime/API capabilities instead of concrete casts;
- Android release/tests and iOS tests/simulator builds pass with the new graph.
