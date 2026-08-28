# M6 — Polish & Parity

See [README.md](README.md) for shared conventions.

## REI integration

`compat/rei/BoilerplateREIPlugin : REIClientPlugin`, registered per-loader — REI plugin discovery is a loader-level service-loader hook that Architectury doesn't unify, so this needs one thin registration shim in `fabric` and `neoforge` each, not a single common-module registration. Recipe-fill from REI into crafting-terminal requests (M4), and warehouse-terminal search (M3) surfaced through REI's usage/working-station hooks.

Must runtime-guard on REI actually being present. Verify REI's dependency scope in `build.gradle.kts` once this milestone starts — it's currently pulled in transitively via Archie's full artifact, which does not guarantee it's available at a scope this module can safely reference; may need its own explicit `modCompileOnly`/`modLocalRuntime` declaration.

## Config screens

Config values are declared incrementally as each earlier milestone introduces them (extraction interval and pipe speed in M1, priority tiers in M2, warehouse volume cap and scan budget in M3, crafting DAG depth limits in M4, pressure costs/multiplier curve in M5) via Archie's `ConfigContainer`/`ConfigSpec`/`CategorySpec` — not deferred wholesale to this milestone. M6's actual job is exposing Archie's auto-generated config screen (cloth-config-backed, per `../../AGENTS.md`'s Archie/cloth-config dependency note) through the mod's own UI entrypoint, not authoring the config values themselves.

## Wireless/remote terminal

`WirelessTerminalItem` using `ComposeItemContainerMenu<T>` (the item-backed analogue of `ComposeBlockContainerMenu`), NBT-bound via `NBTHolder.item(stack)`. Same search/withdraw/craft UI as the block terminal (M3/M4), gated by a small on-item `ArchieEnergyStorage` "charge" consumed per use — ties back into M5's pressure system for both theme and balance consistency (charging the terminal draws from the pressure network, same as everything else).

## VFX/SFX

Steam-puff particles and hiss/clank sounds triggered from tick hooks that already exist by this point (pipe hop from M1, gantry move from M3, pressure consumption from M5) via standard `ADeferredRegistryHolder<ParticleType<*>>`/`ADeferredRegistryHolder<SoundEvent>` registries — decoration layered onto hooks that already exist, not new plumbing.
