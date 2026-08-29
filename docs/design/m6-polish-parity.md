# M6 — Polish & Parity

See [README.md](README.md) for shared conventions.

## Recipe-viewer integration (JEI / REI / EMI)

All three recipe viewers are supported, not REI alone — each is independently optional at runtime, and a player only ever has some subset installed. Recipe-fill from any of the three into crafting-terminal requests (M4), and warehouse-terminal search (M3) surfaced through each viewer's own usage/working-station hooks, same feature set across all three.

Dependency shape differs per viewer, confirmed against MC 1.21.1:

- **REI** (`me.shedaniel:RoughlyEnoughItems-api`, pinned to `16.0.788` to match Archie's own `modApi` dependency exactly — a second, differently-versioned copy on the classpath is asking for trouble): already resolves transitively via Archie's published POM, but `build.gradle.kts` needs its own explicit `modCompileOnly` declaration in `common` regardless — relying on a transitive compile-time dependency nobody explicitly declared is fragile the moment Archie's own dependency graph shifts. Its API is loader-agnostic, so both the plugin class and its registration can live in `common` — only the actual `REIClientPlugin` service-loader discovery (a loader-level hook Architectury doesn't unify) needs a one-line registration shim per loader (`fabric`'s own `rei_client` entrypoint, `neoforge`'s `META-INF/services` entry) pointing at the common plugin class.
- **JEI** (`mezz.jei:jei-1.21.1-common-api`, version `19.44.0.413`): also ships a genuine shared loader-agnostic api artifact, same shape as REI's — `mezz.jei.api.IModPlugin` and every registration interface it takes live entirely in this one artifact, not split per loader the way an earlier pass through this doc assumed. Needs no discovery shim at all, unlike REI: JEI finds its own plugins by scanning installed mods' classes for the `@JeiPlugin` annotation, no `META-INF/services`/entrypoint declaration required on either loader. `modLocalRuntime(libs.jei.fabric` `/neoforge)` per loader module is still needed for the real mod jar at local dev-test time. Needs a new `maven.blamejared.com` entry added to the root repo list.
- **EMI** (`dev.emi:emi-xplat-intermediary`, `:api` classifier, version `1.1.24+1.21.1`): its own main coordinates (`emi-fabric`/`emi-neoforge`) aren't loader-agnostic - confirmed by diffing both loaders' `:api` jars' class lists, 76 of 77-78 classes are identical, only the two loader-specific ingredient-wrapper classes (`FabricEmiStack` vs `NeoForgeEmiStack`/`NeoForgeEmiIngredient`) differ - but that shared 76-class core is *also* published on its own, as `emi-xplat-intermediary` (an earlier pass through this doc missed this and wrongly concluded EMI needed real per-loader duplication). Same shape as Archie's own published modules: an intermediary-mapped artifact Architectury Loom remaps to whichever real platform is building, so `BoilerplateEmiPlugin` lives in `common` too, no different from REI's/JEI's. Discovery still genuinely differs per loader, though: Fabric needs the `"emi"` entrypoint in `fabric.mod.json` (pointing at the common class); NeoForge's own annotation scanning looks for `@EmiEntrypoint` directly on the class instead, which is safe to leave on unconditionally - neither loader's own EMI implementation scans for the other's mechanism. Already on `maven.terraformersmc.com` (already in the root repo list).

Shared, loader-independent logic (resolving a `Pattern`/`CraftingRequest` into whatever ingredient/result stacks a recipe display needs) stays in `common` as plain functions every plugin calls into.

Each of the three must runtime-guard on its own actual presence — none of the three is a hard dependency, and a player may have any combination of them (including none) installed. All three's own discovery mechanisms (REI's service-loader/entrypoint, JEI's `@JeiPlugin` annotation scan, EMI's entrypoint/`@EmiEntrypoint` pair) never load the plugin class at all unless the real mod is present, so none of them need an extra guard.

### Recipe-fill also pulls from storage and the terminal's own inbox

Each viewer's own "transfer recipe" click only ever fills the Crafting Terminal's grid from the player's carried inventory by default - the terminal itself sits on the pipe network, so it can do better. Each plugin wraps its viewer's own (otherwise unmodified) fill logic: before delegating, it works out the recipe's own ingredient list (viewer-specific - REI's `Display.getInputEntries()`, JEI's vanilla `CraftingRecipe.getIngredients()`, EMI's `EmiRecipe.getInputs()`) and fires `CraftingTerminalHookMenu.requestIngredientSupply` with one representative `ItemResource` per ingredient.

Server-side (`CraftingTerminalHookMenu.supplyIngredients`), for anything the player doesn't already carry: pull one first from the terminal's own inbox (`TerminalHookState.output` - same tile, instant), then fall back to requesting more from whatever's reachable on the network (`RequestFulfillment.request`) - which lands in the inbox for a *later* attempt rather than helping the current click, since a network delivery is never instant the way an in-pipe item's own travel isn't. A transfer click needing a network-sourced ingredient therefore reports "missing ingredients" the same as any real shortfall on its first press, succeeding on a second once it's arrived - an accepted tradeoff (confirmed with the user) rather than blocking the viewer's own synchronous, client-side transfer call on a round trip these APIs were never built to await.

A from-scratch, AE2/RS-style "ctrl-click to autocraft anything missing via a known pattern" was considered and deliberately deferred - out of scope for the recipe-*fill* feature itself, since it would mean bridging into the pattern-resolution/Crafting-CPU job system (`CraftingRequest.resolve`/`submitCraft`) directly from a recipe-viewer click rather than the terminal's own grid, a genuinely separate feature.

## Config screens

Config values are declared incrementally as each earlier milestone introduces them (extraction interval and pipe speed in M1, priority tiers in M2, warehouse volume cap and scan budget in M3, crafting DAG depth limits in M4, pressure costs/multiplier curve in M5) via Archie's `ConfigContainer`/`ConfigSpec`/`CategorySpec` — not deferred wholesale to this milestone. M6's actual job is exposing Archie's auto-generated config screen (cloth-config-backed, per `../../AGENTS.md`'s Archie/cloth-config dependency note) through the mod's own UI entrypoint, not authoring the config values themselves.

## Wireless/remote terminal

`WirelessTerminalItem` using `ComposeItemContainerMenu<T>` (the item-backed analogue of `ComposeBlockContainerMenu`), NBT-bound via `NBTHolder.item(stack)`. Same search/withdraw/craft UI as the block terminal (M3/M4), gated by a small on-item `ArchieEnergyStorage` "charge" consumed per use — ties back into M5's pressure system for both theme and balance consistency (charging the terminal draws from the pressure network, same as everything else).

## VFX/SFX

Steam-puff particles and hiss/clank sounds triggered from tick hooks that already exist by this point (pipe hop from M1, gantry move from M3, pressure consumption from M5) via standard `ADeferredRegistryHolder<ParticleType<*>>`/`ADeferredRegistryHolder<SoundEvent>` registries — decoration layered onto hooks that already exist, not new plumbing.
