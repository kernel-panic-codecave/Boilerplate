# M6 — Polish & Parity

See [README.md](README.md) for shared conventions.

## Recipe-viewer integration (JEI / REI / EMI)

All three recipe viewers are supported, not REI alone — each is independently optional at runtime, and a player only ever has some subset installed. Recipe-fill from any of the three into crafting-terminal requests (M4), and warehouse-terminal search (M3) surfaced through each viewer's own usage/working-station hooks, same feature set across all three.

Dependency shape differs per viewer, confirmed against MC 1.21.1:

- **REI** (`me.shedaniel:RoughlyEnoughItems-api`, pinned to `16.0.788` to match Archie's own `modApi` dependency exactly — a second, differently-versioned copy on the classpath is asking for trouble): already resolves transitively via Archie's published POM, but `build.gradle.kts` needs its own explicit `modCompileOnly` declaration in `common` regardless — relying on a transitive compile-time dependency nobody explicitly declared is fragile the moment Archie's own dependency graph shifts. Its API is loader-agnostic, so both the plugin class and its registration can live in `common` — only the actual `REIClientPlugin`/`REIServerPlugin` service-loader discovery (a loader-level hook Architectury doesn't unify) needs a one-line registration shim per loader (`fabric`/`neoforge`, `META-INF/services` entry pointing at the common plugin class).
- **EMI** (`dev.emi:emi-fabric`/`dev.emi:emi-neoforge`, `:api` classifier, version `1.1.24+1.21.1`): no shared loader-agnostic api artifact exists, unlike REI. `modCompileOnly` against the loader-specific jar, `modLocalRuntime` for local dev testing, one per loader module. Already on `maven.terraformersmc.com` (already in the root repo list).
- **JEI** (`mezz.jei:jei-1.21.1-fabric-api`/`jei-1.21.1-neoforge-api`, version `19.44.0.413`): same shape as EMI — no shared api artifact, `modCompileOnly`/`modLocalRuntime` per loader. Needs a new `maven.blamejared.com` entry added to the root repo list.

Since EMI and JEI both require loader-specific compilation, their plugin/registration code lives in `fabric`/`neoforge` directly (not `common`) — near-duplicate source compiled twice against each loader's own jar, the same shim shape the REI service-loader registration already needs, just carrying real logic instead of one line. Shared, loader-independent logic (resolving a `Pattern`/`CraftingRequest` into whatever ingredient/result stacks a recipe display needs) stays in `common` as plain functions all three call into, so the per-loader glue is only ever the recipe-viewer-specific plumbing, never the domain logic itself.

Each of the three must runtime-guard on its own actual presence — none of the three is a hard dependency, and a player may have any combination of them (including none) installed.

## Config screens

Config values are declared incrementally as each earlier milestone introduces them (extraction interval and pipe speed in M1, priority tiers in M2, warehouse volume cap and scan budget in M3, crafting DAG depth limits in M4, pressure costs/multiplier curve in M5) via Archie's `ConfigContainer`/`ConfigSpec`/`CategorySpec` — not deferred wholesale to this milestone. M6's actual job is exposing Archie's auto-generated config screen (cloth-config-backed, per `../../AGENTS.md`'s Archie/cloth-config dependency note) through the mod's own UI entrypoint, not authoring the config values themselves.

## Wireless/remote terminal

`WirelessTerminalItem` using `ComposeItemContainerMenu<T>` (the item-backed analogue of `ComposeBlockContainerMenu`), NBT-bound via `NBTHolder.item(stack)`. Same search/withdraw/craft UI as the block terminal (M3/M4), gated by a small on-item `ArchieEnergyStorage` "charge" consumed per use — ties back into M5's pressure system for both theme and balance consistency (charging the terminal draws from the pressure network, same as everything else).

## VFX/SFX

Steam-puff particles and hiss/clank sounds triggered from tick hooks that already exist by this point (pipe hop from M1, gantry move from M3, pressure consumption from M5) via standard `ADeferredRegistryHolder<ParticleType<*>>`/`ADeferredRegistryHolder<SoundEvent>` registries — decoration layered onto hooks that already exist, not new plumbing.
