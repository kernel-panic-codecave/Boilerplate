# M6 — Polish & Parity

See [README.md](README.md) for shared conventions.

## Recipe-viewer integration (JEI / REI / EMI)

All three recipe viewers are supported, not REI alone — each is independently optional at runtime, and a player only ever has some subset installed. Recipe-fill from any of the three into crafting-terminal requests (M4), and warehouse-terminal search (M3) surfaced through each viewer's own usage/working-station hooks, same feature set across all three.

Dependency shape differs per viewer, confirmed against MC 1.21.1:

- **REI** (`me.shedaniel:RoughlyEnoughItems-api`, pinned to `16.0.788` to match Archie's own `modApi` dependency exactly — a second, differently-versioned copy on the classpath is asking for trouble): already resolves transitively via Archie's published POM, but `build.gradle.kts` needs its own explicit `modCompileOnly` declaration in `common` regardless — relying on a transitive compile-time dependency nobody explicitly declared is fragile the moment Archie's own dependency graph shifts. Its API is loader-agnostic, so both the plugin class and its registration can live in `common` — only the actual `REIClientPlugin` service-loader discovery (a loader-level hook Architectury doesn't unify) needs a one-line registration shim per loader (`fabric`'s own `rei_client` entrypoint, `neoforge`'s `META-INF/services` entry) pointing at the common plugin class.
- **JEI** (`mezz.jei:jei-1.21.1-common-api`, version `19.44.0.413`): also ships a genuine shared loader-agnostic api artifact, same shape as REI's — `mezz.jei.api.IModPlugin` and every registration interface it takes live entirely in this one artifact, not split per loader the way an earlier pass through this doc assumed. Needs no discovery shim at all, unlike REI: JEI finds its own plugins by scanning installed mods' classes for the `@JeiPlugin` annotation, no `META-INF/services`/entrypoint declaration required on either loader. `modLocalRuntime(libs.jei.fabric` `/neoforge)` per loader module is still needed for the real mod jar at local dev-test time. Needs a new `maven.blamejared.com` entry added to the root repo list.
- **EMI** (`dev.emi:emi-fabric`/`dev.emi:emi-neoforge`, `:api` classifier, version `1.1.24+1.21.1`): the one of the three with no shared common artifact published at all - confirmed by diffing both loaders' `:api` jars' class lists: 76 of 77-78 classes are identical, only the two loader-specific ingredient-wrapper classes (`FabricEmiStack` vs `NeoForgeEmiStack`/`NeoForgeEmiIngredient`) differ. `BoilerplateEmiPlugin`'s actual logic still has to live in `fabric`/`neoforge` separately (compiled once per loader's own `:api` jar, `modLocalRuntime` for the full runtime jar) rather than `common` - near-duplicate source, not shared, since nothing safely bridges the two loaders' own differently-versioned artifacts at `common`'s single-dependency-graph compile time even though the actual API surface used is identical. Already on `maven.terraformersmc.com` (already in the root repo list).

Shared, loader-independent logic (resolving a `Pattern`/`CraftingRequest` into whatever ingredient/result stacks a recipe display needs) stays in `common` as plain functions every plugin calls into, so EMI's unavoidable per-loader duplication is only ever the recipe-viewer-specific plumbing, never the domain logic itself.

Each of the three must runtime-guard on its own actual presence — none of the three is a hard dependency, and a player may have any combination of them (including none) installed. REI and JEI's own discovery mechanisms (service-loader/entrypoint, and annotation scanning respectively) never load the plugin class at all unless the real mod is present, so neither needs an extra guard; EMI's own equivalent (see its own plugin file) works the same way.

## Config screens

Config values are declared incrementally as each earlier milestone introduces them (extraction interval and pipe speed in M1, priority tiers in M2, warehouse volume cap and scan budget in M3, crafting DAG depth limits in M4, pressure costs/multiplier curve in M5) via Archie's `ConfigContainer`/`ConfigSpec`/`CategorySpec` — not deferred wholesale to this milestone. M6's actual job is exposing Archie's auto-generated config screen (cloth-config-backed, per `../../AGENTS.md`'s Archie/cloth-config dependency note) through the mod's own UI entrypoint, not authoring the config values themselves.

## Wireless/remote terminal

`WirelessTerminalItem` using `ComposeItemContainerMenu<T>` (the item-backed analogue of `ComposeBlockContainerMenu`), NBT-bound via `NBTHolder.item(stack)`. Same search/withdraw/craft UI as the block terminal (M3/M4), gated by a small on-item `ArchieEnergyStorage` "charge" consumed per use — ties back into M5's pressure system for both theme and balance consistency (charging the terminal draws from the pressure network, same as everything else).

## VFX/SFX

Steam-puff particles and hiss/clank sounds triggered from tick hooks that already exist by this point (pipe hop from M1, gantry move from M3, pressure consumption from M5) via standard `ADeferredRegistryHolder<ParticleType<*>>`/`ADeferredRegistryHolder<SoundEvent>` registries — decoration layered onto hooks that already exist, not new plumbing.
